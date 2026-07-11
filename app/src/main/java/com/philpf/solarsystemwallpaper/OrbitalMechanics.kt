package com.philpf.solarsystemwallpaper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Faithful port of the orbital mechanics reference in the design handoff
 * (`Solar System Tracker.dc.html`): J2000 mean elements + per-century rates,
 * Newton-Raphson Kepler solve, and the three-Euler-angle orbital-plane to
 * ecliptic rotation. Values are the standard published JPL/Standish 1992
 * coefficients from the reference `PLANETS` array.
 */

const val K_GAUSS = 0.01720209895
private const val DEG = PI / 180.0

data class Vec3(val x: Double, val y: Double, val z: Double)

/** a, e, i, L, wbar (longitude of perihelion), om (longitude of ascending node), each [base, ratePerCentury]. */
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
)

/** Elements resolved at a given time T (centuries since J2000): a, e, i, om, w (argument of perihelion), M (mean anomaly, degrees). */
data class ResolvedElements(val a: Double, val e: Double, val i: Double, val om: Double, val w: Double, val M: Double)

val PLANETS: List<PlanetElements> = listOf(
    PlanetElements(
        "mercury", "Mercury", "#9a9a9a",
        doubleArrayOf(0.38709927, 0.00000037), doubleArrayOf(0.20563593, 0.00001906),
        doubleArrayOf(7.00497902, -0.00594749), doubleArrayOf(252.25032350, 149472.67411175),
        doubleArrayOf(77.45779628, 0.16047689), doubleArrayOf(48.33076593, -0.12534081),
    ),
    PlanetElements(
        "venus", "Venus", "#e8d9a0",
        doubleArrayOf(0.72333566, 0.00000390), doubleArrayOf(0.00677672, -0.00004107),
        doubleArrayOf(3.39467605, -0.00078890), doubleArrayOf(181.97909950, 58517.81538729),
        doubleArrayOf(131.60246718, 0.00268329), doubleArrayOf(76.67984255, -0.27769418),
    ),
    PlanetElements(
        "earth", "Earth", "#4da6ff",
        doubleArrayOf(1.00000261, 0.00000562), doubleArrayOf(0.01671123, -0.00004392),
        doubleArrayOf(-0.00001531, -0.01294668), doubleArrayOf(100.46457166, 35999.37244981),
        doubleArrayOf(102.93768193, 0.32327364), doubleArrayOf(0.0, 0.0),
    ),
    PlanetElements(
        "mars", "Mars", "#e07a5f",
        doubleArrayOf(1.52371034, 0.00001847), doubleArrayOf(0.09339410, 0.00007882),
        doubleArrayOf(1.84969142, -0.00813131), doubleArrayOf(-4.55343205, 19140.30268499),
        doubleArrayOf(-23.94362959, 0.44441088), doubleArrayOf(49.55953891, -0.29257343),
    ),
    PlanetElements(
        "jupiter", "Jupiter", "#d9b382",
        doubleArrayOf(5.20288700, -0.00011607), doubleArrayOf(0.04838624, -0.00013253),
        doubleArrayOf(1.30439695, -0.00183714), doubleArrayOf(34.39644051, 3034.74612775),
        doubleArrayOf(14.72847983, 0.21252668), doubleArrayOf(100.47390909, 0.20469106),
    ),
    PlanetElements(
        "saturn", "Saturn", "#e8dcb0",
        doubleArrayOf(9.53667594, -0.00125060), doubleArrayOf(0.05386179, -0.00050991),
        doubleArrayOf(2.48599187, 0.00193609), doubleArrayOf(49.95424423, 1222.49362201),
        doubleArrayOf(92.59887831, -0.41897216), doubleArrayOf(113.66242448, -0.28867794),
    ),
    PlanetElements(
        "uranus", "Uranus", "#a0e8e0",
        doubleArrayOf(19.18916464, -0.00196176), doubleArrayOf(0.04725744, -0.00004397),
        doubleArrayOf(0.77263783, -0.00242939), doubleArrayOf(313.23810451, 428.48202785),
        doubleArrayOf(170.95427630, 0.40805281), doubleArrayOf(74.01692503, 0.04240589),
    ),
    PlanetElements(
        "neptune", "Neptune", "#5f7fe0",
        doubleArrayOf(30.06992276, 0.00026291), doubleArrayOf(0.00859048, 0.00005105),
        doubleArrayOf(1.77004347, 0.00035372), doubleArrayOf(-55.12002969, 218.45945325),
        doubleArrayOf(44.96476227, -0.32241464), doubleArrayOf(131.78422574, -0.00508664),
    ),
)

// Osculating elements for (20137) 1996 PX8 Angeljorba live in the generated `AsteroidElements`
// object (see app/build.gradle.kts :app:generateAsteroidElements) — fetched from JPL's
// Small-Body Database at build time, falling back to a baked-in catalog orbit if unreachable.

fun mod360(x: Double): Double {
    var m = x % 360.0
    if (m < 0) m += 360.0
    return m
}

fun solveKepler(mDeg: Double, e: Double): Double {
    val m = mod360(mDeg) * DEG
    var bigE = if (e < 0.8) m else PI
    for (i in 0 until 10) {
        val dE = (bigE - e * sin(bigE) - m) / (1 - e * cos(bigE))
        bigE -= dE
        if (abs(dE) < 1e-9) break
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

fun planetElementsAt(p: PlanetElements, t: Double): ResolvedElements {
    val a = p.a[0] + p.a[1] * t
    val e = p.e[0] + p.e[1] * t
    val i = p.i[0] + p.i[1] * t
    val l = p.L[0] + p.L[1] * t
    val wbar = p.wbar[0] + p.wbar[1] * t
    val om = p.om[0] + p.om[1] * t
    val w = wbar - om
    val m = l - wbar
    return ResolvedElements(a, e, i, om, w, m)
}

fun positionFromElements(a: Double, e: Double, i: Double, om: Double, w: Double, mDeg: Double): Vec3 {
    val bigE = solveKepler(mDeg, e)
    val nu = trueAnomalyFromE(bigE, e)
    val r = a * (1 - e * cos(bigE))
    return orbitToEcliptic(nu, r, i, om, w)
}

fun orbitPath(a: Double, e: Double, i: Double, om: Double, w: Double, steps: Int): List<Vec3> {
    val pts = ArrayList<Vec3>(steps + 1)
    for (k in 0..steps) {
        val nu = (k.toDouble() / steps) * 2 * PI
        val r = a * (1 - e * e) / (1 + e * cos(nu))
        pts.add(orbitToEcliptic(nu, r, i, om, w))
    }
    return pts
}
