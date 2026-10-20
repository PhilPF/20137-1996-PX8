package com.philpf.solarsystemwallpaper

import kotlin.math.max
import kotlin.math.min

enum class ExtraKind(val key: String) {
    DWARF_PLANET("dwarf"),
    ASTEROID("asteroid"),
    COMET("comet");

    companion object {
        fun fromKey(key: String): ExtraKind = entries.firstOrNull { it.key == key } ?: ASTEROID
    }
}

/**
 * A body the user added on top of the built-in Sun, planets and Angeljorba (experimental).
 * Plain Kotlin so the catalog and its storage format are unit-tested.
 *
 * Only bodies orbiting the Sun are supported: dwarf planets, asteroids and comets. Their
 * elements come from JPL exactly like Angeljorba's (Horizons table, then SBDB single epoch).
 */
data class ExtraObject(
    /** Stable and file-name safe; also names the stored element table. */
    val id: String,
    val name: String,
    /** Horizons COMMAND value (without the surrounding quotes). */
    val horizonsCommand: String,
    /** SBDB `sstr` for the single-epoch fallback. */
    val sbdbQuery: String,
    val colorHex: String,
    val kind: ExtraKind,
    val radiusKm: Double? = null,
)

object ExtraObjects {

    const val MAX_OBJECTS = 8

    /** Views fit the farthest aphelion of added objects, but never beyond this (AU). */
    const val MAX_FIT_AU = 60.0

    /** The default view radius: Neptune's orbit, with a little margin. */
    const val DEFAULT_MAX_AU = 31.0

    val presets = listOf(
        ExtraObject("pluto", "Pluto", "999", "134340", "#c9b49a", ExtraKind.DWARF_PLANET, 1188.3),
        ExtraObject("ceres", "Ceres", "1;", "1", "#b9b4ac", ExtraKind.DWARF_PLANET, 469.7),
        ExtraObject("vesta", "Vesta", "4;", "4", "#d9c9a3", ExtraKind.ASTEROID, 262.7),
        ExtraObject("pallas", "Pallas", "2;", "2", "#a9bccf", ExtraKind.ASTEROID, 256.0),
        ExtraObject("eros", "433 Eros", "433;", "433", "#d99a62", ExtraKind.ASTEROID, 8.4),
        ExtraObject("apophis", "99942 Apophis", "99942;", "99942", "#e2785c", ExtraKind.ASTEROID, 0.17),
        ExtraObject("halley", "Halley's Comet", "DES=1P;CAP;", "1P", "#9fd6ff", ExtraKind.COMET, 5.5),
    )

    private val customPalette = listOf("#c3a6ff", "#7fe0c4", "#ffb0c8", "#f2d27a", "#8fb8ff", "#ff9f7a")

    private val numberedComet = Regex("^(\\d+)[PDI](/.*)?$", RegexOption.IGNORE_CASE)
    private val provisionalComet = Regex("^[PCDXI]/\\d{3,4}\\s+\\S+.*$", RegexOption.IGNORE_CASE)

    /**
     * Builds an object from what the user typed: a number ("3200"), a name ("Phaethon"), a
     * provisional designation ("2004 MN4") or a comet ("2P", "2P/Encke", "C/1995 O1").
     * Returns null for empty or unsafe input.
     */
    fun custom(query: String, existing: List<ExtraObject>): ExtraObject? {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty() || q.length > 40 || q.any { it in "|;'\"\\\n\r=" }) return null
        val numbered = numberedComet.find(q)
        val isComet = numbered != null || provisionalComet.matches(q)
        val command = when {
            numbered != null -> "DES=${numbered.groupValues[1]}${q[numbered.groupValues[1].length].uppercaseChar()};CAP;"
            isComet -> "DES=${q.uppercase()};CAP;"
            else -> "$q;"
        }
        val base = "custom_" + q.lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }.joinToString("")
        var id = base
        var n = 2
        while (existing.any { it.id == id }) id = base + "_" + n++
        val color = customPalette[existing.count { it.id.startsWith("custom_") } % customPalette.size]
        val sbdb = numbered?.let { "${it.groupValues[1]}${q[it.groupValues[1].length].uppercaseChar()}" } ?: q
        return ExtraObject(id, q, command, sbdb, color, if (isComet) ExtraKind.COMET else ExtraKind.ASTEROID)
    }

    // ---- Storage: one object per line, fields separated by '|' ---------------------------------

    fun encode(objects: List<ExtraObject>): String = objects.joinToString("\n") { o ->
        listOf(o.id, clean(o.name), o.horizonsCommand, o.sbdbQuery, o.colorHex, o.kind.key, o.radiusKm?.toString() ?: "")
            .joinToString("|")
    }

    fun decode(text: String?): List<ExtraObject> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lines().mapNotNull { line ->
            val f = line.split("|")
            if (f.size < 6 || f[0].isEmpty()) return@mapNotNull null
            ExtraObject(f[0], f[1], f[2], f[3], f[4], ExtraKind.fromKey(f[5]), f.getOrNull(6)?.toDoubleOrNull())
        }
    }

    private fun clean(s: String) = s.replace("|", "/").replace("\n", " ").replace("\r", " ").trim()

    /** Distance from the Sun (AU) that should map to the edge of the view. */
    fun fitMaxAu(aphelia: List<Double>): Double {
        val far = aphelia.filter { it.isFinite() && it > 0 }.maxOrNull() ?: return DEFAULT_MAX_AU
        return max(DEFAULT_MAX_AU, min(MAX_FIT_AU, far * 1.03))
    }

    /** Standard small-body orbit classes, from perihelion/aphelion/semi-major axis (AU). */
    fun classify(a: Double, e: Double): String {
        val q = a * (1 - e)
        val bigQ = a * (1 + e)
        return when {
            q < 1.3 && bigQ < 0.983 -> "Near-Earth asteroid (Atira)"
            q < 1.3 && a < 1.0 -> "Near-Earth asteroid (Aten)"
            q < 1.017 -> "Near-Earth asteroid (Apollo)"
            q < 1.3 -> "Near-Earth asteroid (Amor)"
            q < 1.666 && a < 3.2 -> "Mars-crosser asteroid"
            a in 2.0..3.3 -> "Main-belt asteroid"
            a in 5.05..5.35 -> "Jupiter trojan"
            a > 30.1 -> "Trans-Neptunian object"
            a > 5.5 -> "Centaur"
            else -> "Asteroid"
        }
    }
}
