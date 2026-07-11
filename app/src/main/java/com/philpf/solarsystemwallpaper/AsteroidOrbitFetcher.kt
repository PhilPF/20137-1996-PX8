package com.philpf.solarsystemwallpaper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Osculating elements for a small body: same shape/units as the [PlanetElements]-derived math expects. */
data class AsteroidOrbit(
    val a: Double,
    val e: Double,
    val i: Double,
    val om: Double,
    val w: Double,
    val ma: Double,
    val epochJd: Double,
)

sealed class AsteroidFetchResult {
    data class Success(val orbit: AsteroidOrbit, val sourceLabel: String) : AsteroidFetchResult()
    data class Failure(val reason: String) : AsteroidFetchResult()
}

/**
 * Fetches real osculating elements for (20137) 1996 PX8 Angeljorba from JPL's Small-Body
 * Database, same source and retry chain as the design handoff's reference `fetchAsteroid()`
 * (direct request, then two read-only CORS relays, matching JPL's own no-browser-embedding
 * policy — relayed the same way here since Android's HttpURLConnection hits the same 403s
 * JPL returns to unrecognized clients). Must be called off the main thread.
 */
object AsteroidOrbitFetcher {

    private const val TARGET = "https://ssd-api.jpl.nasa.gov/sbdb.api?sstr=20137&full-prec=1"

    private val sources: List<Pair<String, String>> by lazy {
        val encoded = URLEncoder.encode(TARGET, "UTF-8")
        listOf(
            "direct" to TARGET,
            "allorigins relay" to "https://api.allorigins.win/raw?url=$encoded",
            "corsproxy relay" to "https://corsproxy.io/?url=$encoded",
        )
    }

    fun fetch(): AsteroidFetchResult {
        for ((label, url) in sources) {
            try {
                val orbit = parse(get(url))
                if (orbit != null) return AsteroidFetchResult.Success(orbit, label)
            } catch (_: Exception) {
                // try the next source
            }
        }
        return AsteroidFetchResult.Failure("all JPL SBDB network paths failed (network/CORS/timeout)")
    }

    private fun get(urlStr: String): String {
        val connection = URI(urlStr).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Accept", "application/json")
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: String): AsteroidOrbit? {
        val orbit = JSONObject(json).optJSONObject("orbit") ?: return null
        val elements = orbit.optJSONArray("elements") ?: return null
        val values = HashMap<String, Double>()
        for (idx in 0 until elements.length()) {
            val el = elements.optJSONObject(idx) ?: continue
            val name = el.optString("name")
            val value = el.optString("value").toDoubleOrNull()
            if (name.isNotEmpty() && value != null) values[name] = value
        }
        val epoch = orbit.optString("epoch").toDoubleOrNull()
        val a = values["a"]
        val e = values["e"]
        val i = values["i"]
        val om = values["om"]
        val w = values["w"]
        val ma = values["ma"]
        if (a == null || e == null || i == null || om == null || w == null || ma == null || epoch == null) return null
        return AsteroidOrbit(a, e, i, om, w, ma, epoch)
    }
}
