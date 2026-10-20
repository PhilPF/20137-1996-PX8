package com.philpf.solarsystemwallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraObjectsTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val list = ExtraObjects.presets + ExtraObjects.custom("3200 Phaethon", emptyList())!!
        assertEquals(list, ExtraObjects.decode(ExtraObjects.encode(list)))
        assertEquals(emptyList<ExtraObject>(), ExtraObjects.decode(null))
        assertEquals(emptyList<ExtraObject>(), ExtraObjects.decode(""))
    }

    @Test
    fun presetIdsAreUniqueAndFileSafe() {
        val ids = ExtraObjects.presets.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { id -> id.all { it.isLetterOrDigit() || it == '_' } })
    }

    @Test
    fun customQueriesBecomeHorizonsCommands() {
        assertEquals("3200;", ExtraObjects.custom("3200", emptyList())!!.horizonsCommand)
        assertEquals("Phaethon;", ExtraObjects.custom("  Phaethon ", emptyList())!!.horizonsCommand)
        val encke = ExtraObjects.custom("2P/Encke", emptyList())!!
        assertEquals("DES=2P;CAP;", encke.horizonsCommand)
        assertEquals("2P", encke.sbdbQuery)
        assertEquals(ExtraKind.COMET, encke.kind)
        assertEquals(ExtraKind.COMET, ExtraObjects.custom("C/1995 O1", emptyList())!!.kind)
        assertEquals(ExtraKind.ASTEROID, ExtraObjects.custom("2004 MN4", emptyList())!!.kind)
    }

    @Test
    fun unsafeOrEmptyInputIsRejected() {
        assertNull(ExtraObjects.custom("", emptyList()))
        assertNull(ExtraObjects.custom("1;2", emptyList()))
        assertNull(ExtraObjects.custom("a'b", emptyList()))
        assertNull(ExtraObjects.custom("x|y", emptyList()))
        assertNull(ExtraObjects.custom("COMMAND=1", emptyList()))
    }

    @Test
    fun customIdsDoNotCollide() {
        val first = ExtraObjects.custom("Phaethon", emptyList())!!
        val second = ExtraObjects.custom("phaethon", listOf(first))!!
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun viewFitsFarObjectsWithinLimits() {
        assertEquals(31.0, ExtraObjects.fitMaxAu(emptyList()), 0.0)
        assertEquals(31.0, ExtraObjects.fitMaxAu(listOf(3.0)), 0.0)
        assertEquals(49.3 * 1.03, ExtraObjects.fitMaxAu(listOf(49.3)), 1e-9)
        assertEquals(60.0, ExtraObjects.fitMaxAu(listOf(500.0)), 0.0)
    }

    @Test
    fun classification() {
        // Angeljorba: q = 1.59 AU, so a Mars-crosser rather than a near-Earth asteroid.
        assertEquals("Mars-crosser asteroid", ExtraObjects.classify(2.2896, 0.3046))
        assertEquals("Near-Earth asteroid (Aten)", ExtraObjects.classify(0.9224, 0.1914)) // Apophis
        assertEquals("Near-Earth asteroid (Amor)", ExtraObjects.classify(1.458, 0.2229)) // Eros
        assertEquals("Main-belt asteroid", ExtraObjects.classify(2.767, 0.0785)) // Ceres
        assertEquals("Trans-Neptunian object", ExtraObjects.classify(39.48, 0.2488)) // Pluto
    }
}
