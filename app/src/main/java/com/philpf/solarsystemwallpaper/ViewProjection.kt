package com.philpf.solarsystemwallpaper

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Heliocentric ecliptic position → screen. Plain Kotlin, no Android dependencies.
 *
 * Distances from the Sun are compressed with `sqrt` (so Neptune isn't pushed off-screen
 * relative to Mercury), then viewed by an orthographic camera: rotated by [azimuthDeg] about the
 * ecliptic pole, and raised [tiltDeg] from straight overhead (0° = top-down, 90° = edge-on).
 *
 * The camera sits above the ecliptic on the side nearest the bottom of the screen, with north
 * pointing up: things nearer the viewer are lower on screen and north of the ecliptic is higher,
 * which is how a tilted view is naturally read. (The design handoff's projection flipped the
 * vertical axis, so inclined orbits leaned the wrong way.) [project] also returns the depth
 * toward the viewer, used to draw far bodies before near ones.
 */
class ViewProjection(
    private val centerX: Float,
    private val centerY: Float,
    private val maxRadiusPx: Float,
    azimuthDeg: Double,
    tiltDeg: Double,
    private val maxAu: Double = 31.0,
) {
    private val cosAz = cos(Math.toRadians(azimuthDeg))
    private val sinAz = sin(Math.toRadians(azimuthDeg))
    private val cosTilt = cos(Math.toRadians(tiltDeg))
    private val sinTilt = sin(Math.toRadians(tiltDeg))
    private val sqrtMaxAu = sqrt(maxAu)

    /** Writes screen x, screen y, and depth (larger = nearer the viewer, in px) into [out]. */
    fun project(p: Vec3, out: FloatArray) {
        val r = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
        val factor = if (r > 1e-9) (sqrt(r) / sqrtMaxAu) * maxRadiusPx / r else 0.0
        val sx = p.x * factor
        val sy = p.y * factor
        val sz = p.z * factor
        val rx = sx * cosAz - sy * sinAz
        val ry = sx * sinAz + sy * cosAz
        val up = ry * cosTilt + sz * sinTilt
        val depth = sz * cosTilt - ry * sinTilt
        out[0] = (centerX + rx).toFloat()
        out[1] = (centerY - up).toFloat()
        out[2] = depth.toFloat()
    }
}
