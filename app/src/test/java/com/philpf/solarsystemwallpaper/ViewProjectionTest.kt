package com.philpf.solarsystemwallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewProjectionTest {

    private fun project(p: Vec3, azimuth: Double = 0.0, tilt: Double): FloatArray =
        FloatArray(3).also { ViewProjection(500f, 500f, 400f, azimuth, tilt, maxAu = 1.0).project(p, it) }

    @Test
    fun topDownViewIsNorthUpMap() {
        val east = project(Vec3(1.0, 0.0, 0.0), tilt = 0.0)
        val north = project(Vec3(0.0, 1.0, 0.0), tilt = 0.0)
        assertTrue("+x is to the right", east[0] > 500f && abs(east[1] - 500f) < 1e-3f)
        assertTrue("+y is up", north[1] < 500f && abs(north[0] - 500f) < 1e-3f)
        assertTrue("ecliptic pole faces the viewer", project(Vec3(0.0, 0.0, 1.0), tilt = 0.0)[2] > 0f)
    }

    @Test
    fun tiltedViewPutsNearSideLowerAndInFront() {
        val near = project(Vec3(0.0, -1.0, 0.0), tilt = 30.0)
        val far = project(Vec3(0.0, 1.0, 0.0), tilt = 30.0)
        assertTrue("near side lower on screen", near[1] > far[1])
        assertTrue("near side drawn in front", near[2] > far[2])
    }

    @Test
    fun northOfTheEclipticIsUp() {
        for (tilt in listOf(10.0, 30.0, 60.0, 85.0)) {
            val above = project(Vec3(0.0, 0.0, 1.0), tilt = tilt)
            val below = project(Vec3(0.0, 0.0, -1.0), tilt = tilt)
            assertTrue("tilt $tilt", above[1] < below[1])
        }
    }

    /** Determinant of the (screen x, screen up, depth) images of the x/y/z unit vectors must be positive. */
    @Test
    fun isProperRotationNotMirror() {
        for (az in listOf(0.0, 37.0, 200.0)) for (tilt in listOf(0.0, 22.0, 70.0)) {
            val cols = listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0)).map {
                val o = project(it, az, tilt)
                doubleArrayOf((o[0] - 500f).toDouble(), (500f - o[1]).toDouble(), o[2].toDouble())
            }
            val (a, b, c) = cols
            val det = a[0] * (b[1] * c[2] - b[2] * c[1]) - b[0] * (a[1] * c[2] - a[2] * c[1]) + c[0] * (a[1] * b[2] - a[2] * b[1])
            assertEquals("az $az tilt $tilt", 400.0 * 400.0 * 400.0, det, 1e-4 * 400.0 * 400.0 * 400.0)
        }
    }

    private fun abs(x: Float) = kotlin.math.abs(x)
}
