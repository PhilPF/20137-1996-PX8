package com.philpf.solarsystemwallpaper

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

sealed class AsteroidFetchResult {
    /** [name] is JPL's name for the target, when the response included one. */
    data class Success(val rows: List<AsteroidOrbit>, val label: String, val name: String? = null) : AsteroidFetchResult()
    data class Failure(val reason: String) : AsteroidFetchResult()
}

/** What to ask JPL for: a Horizons COMMAND and an SBDB search string. */
data class FetchTarget(val horizonsCommand: String, val sbdbQuery: String) {
    companion object {
        val ANGELJORBA = FetchTarget("20137;", "20137")
    }
}

/**
 * Fetches real orbital elements for (20137) 1996 PX8 Angeljorba, or any other body orbiting the
 * Sun ([FetchTarget]). Must be called off the main thread.
 *
 * 1. **JPL Horizons element table** (preferred): osculating elements sampled every few months
 *    across centuries, already integrated by JPL with planetary perturbations. The renderer
 *    propagates from whichever sample is nearest in time, so long-range dates stay accurate.
 *    Spans are tried widest first; Horizons refuses spans outside its data, and narrower ones
 *    are the fallback.
 * 2. **JPL SBDB single epoch** (fallback): one osculating set, accurate for a few years around
 *    its epoch, drifting further away from it.
 *
 * Each request goes direct first, then through read-only CORS relays, since JPL's APIs can 403
 * unrecognised clients.
 */
object AsteroidOrbitFetcher {

    private const val SBDB = "https://ssd-api.jpl.nasa.gov/sbdb.api"
    private const val HORIZONS = "https://ssd.jpl.nasa.gov/api/horizons.api"

    private val NOT_A_SINGLE_MATCH = listOf("No matches found", "Matching small-bodies", "Multiple major-bodies match")

    private data class Span(val start: String, val stop: String, val step: String, val label: String)

    private val spans = listOf(
        Span("1600-01-01", "2500-01-01", "180 d", "1600–2500"),
        Span("1800-01-01", "2300-01-01", "90 d", "1800–2300"),
        Span("1950-01-01", "2150-01-01", "30 d", "1950–2150"),
    )

    fun fetch(target: FetchTarget = FetchTarget.ANGELJORBA): AsteroidFetchResult {
        fetchHorizonsTable(target)?.let { return it }
        fetchSbdbSingle(target)?.let { return it }
        return AsteroidFetchResult.Failure("JPL Horizons and SBDB unreachable or object not found")
    }

    private fun relayed(target: String): List<Pair<String, String>> {
        val encoded = URLEncoder.encode(target, "UTF-8")
        return listOf(
            "direct" to target,
            "allorigins relay" to "https://api.allorigins.win/raw?url=$encoded",
            "corsproxy relay" to "https://corsproxy.io/?url=$encoded",
            "codetabs relay" to "https://api.codetabs.com/v1/proxy?quest=$encoded",
            "thingproxy relay" to "https://thingproxy.freeboard.io/fetch/$target",
        )
    }

    private fun horizonsUrl(target: FetchTarget, span: Span): String {
        val params = linkedMapOf(
            "format" to "json",
            "COMMAND" to "'${target.horizonsCommand}'",
            "OBJ_DATA" to "'NO'",
            "MAKE_EPHEM" to "'YES'",
            "EPHEM_TYPE" to "'ELEMENTS'",
            "CENTER" to "'500@10'",
            "REF_PLANE" to "'ECLIPTIC'",
            "REF_SYSTEM" to "'J2000'",
            "OUT_UNITS" to "'AU-D'",
            "CSV_FORMAT" to "'YES'",
            "START_TIME" to "'${span.start}'",
            "STOP_TIME" to "'${span.stop}'",
            "STEP_SIZE" to "'${span.step}'",
        )
        return HORIZONS + "?" + params.entries.joinToString("&") { (k, v) ->
            k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        }
    }

    private fun fetchHorizonsTable(target: FetchTarget): AsteroidFetchResult.Success? {
        // Per source: try spans widest-first. A network failure moves on to the next source; a
        // valid answer with no rows (span outside Horizons' data) moves on to the next span.
        for ((sourceLabel, _) in relayed(HORIZONS)) {
            for (span in spans) {
                val url = relayed(horizonsUrl(target, span)).first { it.first == sourceLabel }.second
                val body = try {
                    get(url, readTimeoutMs = 25_000)
                } catch (_: Exception) {
                    break
                }
                val text = try {
                    JSONObject(body).optString("result", body)
                } catch (_: Exception) {
                    body
                }
                // Unknown or ambiguous name: no other span or relay will answer differently.
                if (NOT_A_SINGLE_MATCH.any { text.contains(it, ignoreCase = true) }) return null
                val rows = EphemerisFormats.parseHorizonsElements(text)
                if (rows.size >= 10) {
                    return AsteroidFetchResult.Success(
                        rows,
                        "JPL Horizons ${span.label} · ${rows.size} epochs · $sourceLabel",
                        horizonsTargetName(text),
                    )
                }
            }
        }
        return null
    }

    private fun fetchSbdbSingle(target: FetchTarget): AsteroidFetchResult.Success? {
        val url = "$SBDB?sstr=" + URLEncoder.encode(target.sbdbQuery, "UTF-8").replace("+", "%20") + "&full-prec=1"
        for ((label, relayedUrl) in relayed(url)) {
            try {
                val json = get(relayedUrl, readTimeoutMs = 10_000)
                val orbit = parseSbdb(json) ?: continue
                val name = try {
                    JSONObject(json).optJSONObject("object")?.optString("fullname")?.trim()?.takeIf { it.isNotEmpty() }
                } catch (_: Exception) {
                    null
                }
                return AsteroidFetchResult.Success(listOf(orbit), "JPL SBDB single epoch · $label", name)
            } catch (_: Exception) {
                // try the next source
            }
        }
        return null
    }

    /** "Target body name: 1 Ceres (A801 AA)   {source: JPL#48}" -> "1 Ceres (A801 AA)". */
    private fun horizonsTargetName(text: String): String? =
        Regex("Target body name:\\s*(.+?)\\s*(\\{|\\n|$)").find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun get(urlStr: String, readTimeoutMs: Int): String {
        val connection = URI(urlStr).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSbdb(json: String): AsteroidOrbit? {
        val orbit = JSONObject(json).optJSONObject("orbit") ?: return null
        val elements = orbit.optJSONArray("elements") ?: return null
        val values = HashMap<String, Double>()
        for (idx in 0 until elements.length()) {
            val el = elements.optJSONObject(idx) ?: continue
            val name = el.optString("name")
            val value = el.optString("value").toDoubleOrNull()
            if (name.isNotEmpty() && value != null) values[name] = value
        }
        val epoch = orbit.optString("epoch").toDoubleOrNull() ?: return null
        return AsteroidOrbit(
            epochJd = epoch,
            a = values["a"] ?: return null,
            e = values["e"] ?: return null,
            i = values["i"] ?: return null,
            om = values["om"] ?: return null,
            w = values["w"] ?: return null,
            ma = values["ma"] ?: return null,
            n = values["n"],
        )
    }
}
