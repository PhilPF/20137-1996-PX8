package com.philpf.solarsystemwallpaper

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** What the info card shows for a tapped body. */
data class BodyInfo(val title: String, val subtitle: String, val rows: List<Pair<String, String>>)

/**
 * Live facts about a body at a given instant, for the info card. Plain Kotlin (unit-tested).
 *
 * Positions come from the same mechanics the scene is drawn with. Speed uses the vis-viva
 * equation and the period Kepler's third law, both for an orbit around the Sun alone.
 */
object BodyFacts {
    const val AU_KM = 149_597_870.7
    const val GM_SUN_KM3_S2 = 1.32712440018e11
    const val SUN_RADIUS_KM = 696_340.0

    /** Light takes 499.005 s to cross 1 AU. */
    private const val LIGHT_MINUTES_PER_AU = 499.004784 / 60.0

    val PLANET_RADIUS_KM = mapOf(
        "mercury" to 2439.7, "venus" to 6051.8, "earth" to 6371.0, "mars" to 3389.5,
        "jupiter" to 69911.0, "saturn" to 58232.0, "uranus" to 25362.0, "neptune" to 24622.0,
    )

    private val PLANET_KIND = mapOf(
        "mercury" to "Rocky planet", "venus" to "Rocky planet", "earth" to "Rocky planet · home",
        "mars" to "Rocky planet", "jupiter" to "Gas giant", "saturn" to "Gas giant",
        "uranus" to "Ice giant", "neptune" to "Ice giant",
    )

    fun distance(p: Vec3, q: Vec3): Double {
        val dx = p.x - q.x
        val dy = p.y - q.y
        val dz = p.z - q.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun length(p: Vec3): Double = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)

    /** km/s at distance [rAu] on an orbit with semi-major axis [aAu]. */
    fun orbitalSpeedKmS(rAu: Double, aAu: Double): Double =
        sqrt(GM_SUN_KM3_S2 * (2.0 / (rAu * AU_KM) - 1.0 / (aAu * AU_KM)))

    fun periodYears(aAu: Double): Double = aAu.pow(1.5)

    // ---- Formatting ---------------------------------------------------------------------------

    private fun f(pattern: String, vararg args: Any) = String.format(Locale.US, pattern, *args)

    fun formatAu(au: Double): String {
        val km = au * AU_KM
        val kmText = when {
            km >= 1e9 -> f("%.2f billion km", km / 1e9)
            km >= 1e6 -> f("%.1f million km", km / 1e6)
            else -> f("%,.0f km", km)
        }
        val auText = if (au >= 10) f("%.1f AU", au) else f("%.3f AU", au)
        return "$auText · $kmText"
    }

    fun formatLightTime(au: Double): String {
        val minutes = au * LIGHT_MINUTES_PER_AU
        return when {
            minutes < 1 -> f("%.0f light-seconds", minutes * 60)
            minutes < 120 -> f("%.1f light-minutes", minutes)
            else -> f("%.1f light-hours", minutes / 60)
        }
    }

    fun formatPeriod(years: Double): String = when {
        years < 1.5 -> f("%.0f days", years * 365.25)
        years < 100 -> f("%.2f years", years)
        else -> f("%.0f years", years)
    }

    fun formatRadius(km: Double): String = when {
        km < 1 -> f("%.0f m", km * 1000)
        km < 100 -> f("%.1f km", km)
        else -> f("%,.0f km", km)
    }

    // ---- Cards --------------------------------------------------------------------------------

    fun sun(earth: Vec3): BodyInfo {
        val d = length(earth)
        return BodyInfo(
            "Sun",
            "Star · type G2V",
            listOf(
                "Radius" to formatRadius(SUN_RADIUS_KM),
                "From Earth" to formatAu(d),
                "Sunlight reaches Earth in" to formatLightTime(d),
            ),
        )
    }

    /**
     * Card for anything orbiting the Sun. [earth] is null when the body is Earth itself.
     * [extra] rows (e.g. the data source) go last.
     */
    fun orbiting(
        title: String,
        subtitle: String,
        position: Vec3,
        earth: Vec3?,
        a: Double,
        e: Double,
        iDeg: Double,
        radiusKm: Double?,
        extra: List<Pair<String, String>> = emptyList(),
    ): BodyInfo {
        val r = length(position)
        val rows = ArrayList<Pair<String, String>>()
        rows += "From the Sun" to formatAu(r)
        if (earth != null) {
            val d = distance(position, earth)
            rows += "From Earth" to (formatAu(d) + "\n" + formatLightTime(d))
        }
        rows += "Orbital speed" to f("%.1f km/s", orbitalSpeedKmS(r, a))
        rows += "Year (orbit)" to formatPeriod(periodYears(a))
        rows += "Orbit" to f("e %.3f · tilt %.1f°", e, abs(iDeg))
        if (radiusKm != null) rows += "Radius" to formatRadius(radiusKm)
        rows += extra
        return BodyInfo(title, subtitle, rows)
    }

    fun planetSubtitle(key: String): String = PLANET_KIND[key] ?: "Planet"
}
