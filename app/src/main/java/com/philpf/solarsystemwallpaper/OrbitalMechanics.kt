package com.philpf.solarsystemwallpaper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orbital mechanics shared by the wallpaper, the in-app preview and the widget. Plain Kotlin,
 * no Android dependencies.
 *
 * Planets use JPL's long-span approximate elements (E. M. Standish, "Keplerian Elements for
 * Approximate Positions of the Major Planets", table 2a/2b), fitted for 3000 BC – 3000 AD.
 * The design handoff's reference used table 1, which is only fitted for 1800–2050 and drifts
 * noticeably outside it. Table 2 adds long-period correction terms (b, c, s, f) to the mean
 * anomaly of Jupiter–Neptune.
 *
 * Outside 3000 BC – 3000 AD the slow secular drift of each orbit's shape and orientation is
 * frozen at the edge of the fitted range instead of extrapolated (that drift is only valid
 * near the fit), while each planet keeps moving along its orbit at its mean motion. Positions
 * there are approximate, but orbits never deform into nonsense.
 */

const val K_GAUSS = 0.01720209895
private const val DEG = PI / 180.0

/** Centuries since J2000 covered by the planet element fit (3000 BC .. 3000 AD). */
const val PLANET_THEORY_T_MIN = -50.0
const val PLANET_THEORY_T_MAX = 10.0

data class Vec3(val x: Double, val y: Double, val z: Double)

/**
 * a, e, i, L (mean longitude), wbar (longitude of perihelion), om (longitude of ascending
 * node), each `[valueAtJ2000, ratePerCentury]`. [extra] holds table 2b's `b, c, s, f` mean
 * anomaly correction terms for the outer planets.
 */
data class PlanetElements(
    val key: String,
    val label: String,
    val colorHex: String,
    val a: DoubleArray,
    val e: DoubleArray,
    val i: DoubleArray,
    val L: DoubleArray,
    val wbar: DoubleArray,
    val om: DoubleArray,
    val extra: DoubleArray? = null,
)

/** Elements resolved at a given time T (centuries since J2000): a, e, i, om, w (argument of perihelion), M (mean anomaly, degrees). */
data class ResolvedElements(val a: Double, val e: Double, val i: Double, val om: Double, val w: Double, val M: Double)

/**
 * Osculating elements of a small body at [epochJd] (heliocentric, ecliptic and equinox J2000,
 * degrees and AU). [n] is the mean motion in degrees/day when the source provides it; otherwise
 * it is derived from [a] with Kepler's third law.
 */
data class AsteroidOrbit(
    val epochJd: Double,
    val a: Double,
    val e: Double,
    val i: Double,
    val om: Double,
    val w: Double,
    val ma: Double,
    val n: Double? = null,
) {
    val meanMotionDegPerDay: Double get() = n ?: ((K_GAUSS * 180.0 / PI) / a.pow(1.5))

    fun meanAnomalyAt(jd: Double): Double = ma + meanMotionDegPerDay * (jd - epochJd)
}

val PLANETS: List<PlanetElements> = listOf(
    PlanetElements(
        "mercury", "Mercury", "#9a9a9a",
        doubleArrayOf(0.38709843, 0.00000000), doubleArrayOf(0.20563661, 0.00002123),
        doubleArrayOf(7.00559432, -0.00590158), doubleArrayOf(252.25166724, 149472.67486623),
        doubleArrayOf(77.45771895, 0.15940013), doubleArrayOf(48.33961819, -0.12214182),
    ),
    PlanetElements(
        "venus", "Venus", "#e8d9a0",
        doubleArrayOf(0.72332102, -0.00000026), doubleArrayOf(0.00676399, -0.00005107),
        doubleArrayOf(3.39777545, 0.00043494), doubleArrayOf(181.97970850, 58517.81560260),
        doubleArrayOf(131.76755713, 0.05679648), doubleArrayOf(76.67261496, -0.27274174),
    ),
    PlanetElements(
        "earth", "Earth", "#4da6ff",
        doubleArrayOf(1.00000018, -0.00000003), doubleArrayOf(0.01673163, -0.00003661),
        doubleArrayOf(-0.00054346, -0.01337178), doubleArrayOf(100.46691572, 35999.37306329),
        doubleArrayOf(102.93005885, 0.31795260), doubleArrayOf(-5.11260389, -0.24123856),
    ),
    PlanetElements(
        "mars", "Mars", "#e07a5f",
        doubleArrayOf(1.52371243, 0.00000097), doubleArrayOf(0.09336511, 0.00009149),
        doubleArrayOf(1.85181869, -0.00724757), doubleArrayOf(-4.56813164, 19140.29934243),
        doubleArrayOf(-23.91744784, 0.45223625), doubleArrayOf(49.71320984, -0.26852431),
    ),
    PlanetElements(
        "jupiter", "Jupiter", "#d9b382",
        doubleArrayOf(5.20248019, -0.00002864), doubleArrayOf(0.04853590, 0.00018026),
        doubleArrayOf(1.29861416, -0.00322699), doubleArrayOf(34.33479152, 3034.90371757),
        doubleArrayOf(14.27495244, 0.18199196), doubleArrayOf(100.29282654, 0.13024619),
        extra = doubleArrayOf(-0.00012452, 0.06064060, -0.35635438, 38.35125000),
    ),
    PlanetElements(
        "saturn", "Saturn", "#e8dcb0",
        doubleArrayOf(9.54149883, -0.00003065), doubleArrayOf(0.05550825, -0.00032044),
        doubleArrayOf(2.49424102, 0.00451969), doubleArrayOf(50.07571329, 1222.11494724),
        doubleArrayOf(92.86136063, 0.54179478), doubleArrayOf(113.63998702, -0.25015002),
        extra = doubleArrayOf(0.00025899, -0.13434469, 0.87320147, 38.35125000),
    ),
    PlanetElements(
        "uranus", "Uranus", "#a0e8e0",
        doubleArrayOf(19.18797948, -0.00020455), doubleArrayOf(0.04685740, -0.00001550),
        doubleArrayOf(0.77298127, -0.00180155), doubleArrayOf(314.20276625, 428.49512595),
        doubleArrayOf(172.43404441, 0.09266985), doubleArrayOf(73.96250215, 0.05739699),
        extra = doubleArrayOf(0.00058331, -0.97731848, 0.17689245, 7.67025000),
    ),
    PlanetElements(
        "neptune", "Neptune", "#5f7fe0",
        doubleArrayOf(30.06952752, 0.00006447), doubleArrayOf(0.00895439, 0.00000818),
        doubleArrayOf(1.77005520, 0.00022400), doubleArrayOf(304.22289287, 218.46515314),
        doubleArrayOf(46.68158724, 0.01009938), doubleArrayOf(131.78635853, -0.00606302),
        extra = doubleArrayOf(-0.00041348, 0.68346318, -0.10162547, 7.67025000),
    ),
)

fun isWithinPlanetTheory(t: Double): Boolean = t in PLANET_THEORY_T_MIN..PLANET_THEORY_T_MAX

fun mod360(x: Double): Double {
    var m = x % 360.0
    if (m < 0) m += 360.0
    return m
}

fun solveKepler(mDeg: Double, e: Double): Double {
    val m = mod360(mDeg) * DEG
    var bigE = if (e < 0.8) m else PI
    for (i in 0 until 15) {
        val dE = (bigE - e * sin(bigE) - m) / (1 - e * cos(bigE))
        bigE -= dE
        if (abs(dE) < 1e-10) break
    }
    return bigE
}

fun trueAnomalyFromE(bigE: Double, e: Double): Double {
    return 2 * atan2(sqrt(1 + e) * sin(bigE / 2), sqrt(1 - e) * cos(bigE / 2))
}

fun orbitToEcliptic(nuRad: Double, r: Double, iDeg: Double, omDeg: Double, wDeg: Double): Vec3 {
    val i = iDeg * DEG
    val om = omDeg * DEG
    val w = wDeg * DEG
    val xp = r * cos(nuRad)
    val yp = r * sin(nuRad)
    val cosom = cos(om); val sinom = sin(om)
    val cosw = cos(w); val sinw = sin(w)
    val cosi = cos(i); val sini = sin(i)
    val x = xp * (cosom * cosw - sinom * sinw * cosi) - yp * (cosom * sinw + sinom * cosw * cosi)
    val y = xp * (sinom * cosw + cosom * sinw * cosi) - yp * (sinom * sinw - cosom * cosw * cosi)
    val z = xp * (sinw * sini) + yp * (cosw * sini)
    return Vec3(x, y, z)
}

fun jdFromUnixMillis(millis: Long): Double = millis / 86400000.0 + 2440587.5

/** Julian centuries since J2000.0 for a Julian Date. */
fun centuriesSinceJ2000(jd: Double): Double = (jd - 2451545.0) / 36525.0

fun planetElementsAt(p: PlanetElements, t: Double): ResolvedElements {
    // Secular drift of shape/orientation is only valid within the fit; mean longitude is not clamped.
    val tc = t.coerceIn(PLANET_THEORY_T_MIN, PLANET_THEORY_T_MAX)
    val a = p.a[0] + p.a[1] * tc
    val e = p.e[0] + p.e[1] * tc
    val i = p.i[0] + p.i[1] * tc
    val l = p.L[0] + p.L[1] * t
    val wbar = p.wbar[0] + p.wbar[1] * tc
    val om = p.om[0] + p.om[1] * tc
    val w = wbar - om
    var m = l - wbar
    p.extra?.let { (b, c, s, f) ->
        m += b * tc * tc + c * cos(f * tc * DEG) + s * sin(f * tc * DEG)
    }
    return ResolvedElements(a, e, i, om, w, m)
}

fun positionFromElements(a: Double, e: Double, i: Double, om: Double, w: Double, mDeg: Double): Vec3 {
    val bigE = solveKepler(mDeg, e)
    val nu = trueAnomalyFromE(bigE, e)
    val r = a * (1 - e * cos(bigE))
    return orbitToEcliptic(nu, r, i, om, w)
}

fun planetPositionAt(p: PlanetElements, jd: Double): Vec3 {
    val el = planetElementsAt(p, centuriesSinceJ2000(jd))
    return positionFromElements(el.a, el.e, el.i, el.om, el.w, el.M)
}

fun asteroidPositionAt(orbit: AsteroidOrbit, jd: Double): Vec3 =
    positionFromElements(orbit.a, orbit.e, orbit.i, orbit.om, orbit.w, orbit.meanAnomalyAt(jd))

fun orbitPath(a: Double, e: Double, i: Double, om: Double, w: Double, steps: Int): List<Vec3> {
    val pts = ArrayList<Vec3>(steps + 1)
    for (k in 0..steps) {
        val nu = (k.toDouble() / steps) * 2 * PI
        val r = a * (1 - e * e) / (1 + e * cos(nu))
        pts.add(orbitToEcliptic(nu, r, i, om, w))
    }
    return pts
}

/**
 * The tabulated osculating elements closest in time to [jd] ([table] sorted by epoch). Two-body
 * propagation from the nearest epoch keeps planetary perturbations (Jupiter, close Mars passes)
 * out of the error budget; a single epoch extrapolated for decades does not.
 */
fun nearestOrbit(table: List<AsteroidOrbit>, jd: Double): AsteroidOrbit {
    require(table.isNotEmpty())
    var lo = 0
    var hi = table.size - 1
    if (jd <= table[lo].epochJd) return table[lo]
    if (jd >= table[hi].epochJd) return table[hi]
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (table[mid].epochJd <= jd) lo = mid else hi = mid
    }
    return if (jd - table[lo].epochJd <= table[hi].epochJd - jd) table[lo] else table[hi]
}
