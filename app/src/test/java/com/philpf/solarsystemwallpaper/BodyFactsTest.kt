package com.philpf.solarsystemwallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyFactsTest {

    @Test
    fun earthOrbitNumbers() {
        assertEquals(29.78, BodyFacts.orbitalSpeedKmS(1.0, 1.0), 0.05)
        assertEquals(1.0, BodyFacts.periodYears(1.0), 1e-12)
        assertEquals(11.86, BodyFacts.periodYears(5.2026), 0.02)
    }

    @Test
    fun earthIsAboutOneAuFromTheSunAllYear() {
        val earth = PLANETS.first { it.key == "earth" }
        for (k in 0 until 12) {
            val jd = 2460676.5 + k * 30.0
            val r = BodyFacts.length(planetPositionAt(earth, jd))
            assertTrue("r=$r", r in 0.98..1.02)
        }
    }

    @Test
    fun formatting() {
        assertEquals("1.000 AU · 149.6 million km", BodyFacts.formatAu(1.0))
        assertEquals("8.3 light-minutes", BodyFacts.formatLightTime(1.0))
        assertEquals("4.2 light-hours", BodyFacts.formatLightTime(30.0))
        assertEquals("365 days", BodyFacts.formatPeriod(1.0))
        assertEquals("248 years", BodyFacts.formatPeriod(247.9))
        assertEquals("170 m", BodyFacts.formatRadius(0.17))
    }

    @Test
    fun earthCardHasNoDistanceFromItself() {
        val info = BodyFacts.orbiting("Earth", "Rocky planet", Vec3(1.0, 0.0, 0.0), null, 1.0, 0.0167, 0.0, 6371.0)
        assertTrue(info.rows.none { it.first == "From Earth" })
        val mars = BodyFacts.orbiting("Mars", "Rocky planet", Vec3(1.5, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), 1.52, 0.09, 1.85, 3389.5)
        assertTrue(mars.rows.first { it.first == "From Earth" }.second.startsWith("0.500 AU"))
    }
}
