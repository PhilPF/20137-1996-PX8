package com.philpf.solarsystemwallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

class OrbitalMechanicsTest {

    private fun lon(v: Vec3) = (Math.toDegrees(atan2(v.y, v.x)) + 360) % 360

    private fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    /** Gregorian calendar date to Julian Date at 0h UT. */
    private fun jd(y: Int, m: Int, d: Int): Double {
        val a = (14 - m) / 12
        val yy = y + 4800 - a
        val mm = m + 12 * a - 3
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045 - 0.5
    }

    private fun planet(key: String) = PLANETS.first { it.key == key }

    @Test
    fun earthHeliocentricLongitudeAtJ2000() {
        assertEquals(100.46, lon(planetPositionAt(planet("earth"), 2451545.0)), 0.2)
    }

    /** At opposition a planet and Earth share the same heliocentric ecliptic longitude. */
    @Test
    fun oppositionDatesMatch() {
        val oppositions = listOf(
            "mars" to jd(2003, 8, 28), "mars" to jd(2020, 10, 13),
            "jupiter" to jd(2010, 9, 21), "jupiter" to jd(2022, 9, 26),
            "saturn" to jd(2023, 8, 27), "uranus" to jd(2023, 11, 13), "neptune" to jd(2023, 9, 19),
        )
        for ((key, date) in oppositions) {
            val d = angleDiff(lon(planetPositionAt(planet(key), date + 0.5)), lon(planetPositionAt(planet("earth"), date + 0.5)))
            assertTrue("$key at JD $date off by $d deg", abs(d) < 1.5)
        }
    }

    /** Beyond the fitted range, orbits keep a sane shape (old linear extrapolation gave Saturn e < 0 by year 20000). */
    @Test
    fun farFutureOrbitsStaySane() {
        for (p in PLANETS) for (year in listOf(4000, 20000, 100000)) {
            val el = planetElementsAt(p, (year - 2000) / 100.0)
            assertTrue("${p.label} e=${el.e} in $year", el.e in 0.0..0.3)
            assertTrue("${p.label} a=${el.a} in $year", abs(el.a - p.a[0]) < 0.01 * p.a[0])
        }
    }

    @Test
    fun noJumpAtEdgeOfFittedRange() {
        val edge = 2451545.0 + PLANET_THEORY_T_MAX * 36525.0
        for (p in PLANETS) {
            val a = planetPositionAt(p, edge - 1e-6)
            val b = planetPositionAt(p, edge + 1e-6)
            val jump = sqrt((a.x - b.x).pow(2) + (a.y - b.y).pow(2) + (a.z - b.z).pow(2))
            assertTrue("${p.label} jumps $jump AU", jump < 1e-6 * p.a[0])
        }
    }

    @Test
    fun customClockStaysInsideLoopWhileAppIsClosed() {
        val now = 1_800_000_000_000L
        val start = 1_700_000_000_000L
        val loopDays = 10 * 365.25
        val anchoredADayAgo = now - 24L * 3600 * 1000
        val sim = SimulationClock.simulatedMillis(now, false, anchoredADayAgo, start, 30.0, loopDays)
        val offsetDays = (sim - start) / SimulationClock.DAY_MS
        assertTrue("offset $offsetDays", offsetDays in 0.0..loopDays)
    }

    @Test
    fun speedChangeKeepsSimulatedDate() {
        val now = 1_800_000_000_000L
        val start = 1_700_000_000_000L
        val loopDays = 10 * 365.25
        val anchor = now - 60_000L
        val before = SimulationClock.simulatedMillis(now, false, anchor, start, 30.0, loopDays)
        val newAnchor = SimulationClock.reanchor(now, anchor, 30.0, loopDays, 400.0, loopDays)
        val after = SimulationClock.simulatedMillis(now, false, newAnchor, start, 400.0, loopDays)
        assertEquals(0.0, (after - before) / SimulationClock.DAY_MS, 0.5)
        val oneSecondLater = SimulationClock.simulatedMillis(now + 1000, false, newAnchor, start, 400.0, loopDays)
        assertEquals(400.0, (oneSecondLater - after) / SimulationClock.DAY_MS, 0.01)
    }

    private val horizonsSample = """
        *******************************************************************************
         JDTDB,            Calendar Date (TDB),                     EC,                     QR,                     IN,                     OM,                      W,                     Tp,                      N,                     MA,                     TA,                      A,                     AD,                     PR,
        **************************************************************************************************************************
        ${'$'}${'$'}SOE
        2451545.000000000, A.D. 2000-Jan-01 12:00:00.0000,  3.045701000000000E-01,  1.592232750000000E+00,  5.865410000000000E+00,  1.506557500000000E+02,  2.300000000000000E+02,  2.451000000000000E+06,  2.844800000000000E-01,  1.234500000000000E+02,  1.400000000000000E+02,  2.289583800000000E+00,  2.986934900000000E+00,  1.265000000000000E+03,
        2451665.000000000, A.D. 2000-Apr-30 12:00:00.0000,  3.045800000000000E-01,  1.592232750000000E+00,  5.865400000000000E+00,  1.506557000000000E+02,  2.300100000000000E+02,  2.451000000000000E+06,  2.844801000000000E-01,  1.575876000000000E+02,  1.700000000000000E+02,  2.289584000000000E+00,  2.986934900000000E+00,  1.265000000000000E+03,
        ${'$'}${'$'}EOE
        *******************************************************************************
    """.trimIndent()

    @Test
    fun parsesHorizonsElementTable() {
        val rows = EphemerisFormats.parseHorizonsElements(horizonsSample)
        assertEquals(2, rows.size)
        val r = rows[0]
        assertEquals(2451545.0, r.epochJd, 1e-9)
        assertEquals(2.2895838, r.a, 1e-9)
        assertEquals(0.3045701, r.e, 1e-9)
        assertEquals(5.86541, r.i, 1e-9)
        assertEquals(150.65575, r.om, 1e-9)
        assertEquals(230.0, r.w, 1e-9)
        assertEquals(123.45, r.ma, 1e-9)
        assertEquals(0.28448, r.n!!, 1e-12)
        assertTrue(EphemerisFormats.parseHorizonsElements("API ERROR: no ephemeris for target").isEmpty())
    }

    @Test
    fun cacheFormatRoundTripsAndLookupPicksNearestEpoch() {
        val rows = EphemerisFormats.parseHorizonsElements(horizonsSample)
        val (label, decoded) = EphemerisFormats.decode(EphemerisFormats.encode("JPL Horizons test", rows))
        assertEquals("JPL Horizons test", label)
        assertEquals(rows, decoded)
        assertEquals(rows[0], nearestOrbit(rows, 2451000.0))
        assertEquals(rows[0], nearestOrbit(rows, 2451600.0))
        assertEquals(rows[1], nearestOrbit(rows, 2451610.0))
        assertEquals(rows[1], nearestOrbit(rows, 2470000.0))
        // Propagating the first epoch forward lands on the second epoch's mean anomaly.
        assertEquals(rows[1].ma, mod360(rows[0].meanAnomalyAt(rows[1].epochJd)), 0.01)
    }
}
