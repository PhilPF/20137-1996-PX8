package com.philpf.solarsystemwallpaper

/**
 * Maps real time to simulated time. Plain Kotlin, no Android dependencies.
 *
 * Custom mode plays forward from a chosen start date at `speed` simulated days per real second,
 * and wraps back to the start after `loopDays`. The loop matters: the clock is anchored to a
 * persisted real-time instant (so it survives the wallpaper engine restarting), which means it
 * keeps advancing while the app is closed. Without a loop, a day away at 30 days/s would put the
 * simulation ~7,000 years in the future.
 */
object SimulationClock {

    const val DAY_MS = 86_400_000.0

    /** Simulated days elapsed since the start date, already wrapped into `[0, loopDays)`. */
    fun offsetDays(nowMillis: Long, anchorWallMillis: Long, speedDaysPerSec: Double, loopDays: Double): Double {
        val elapsedSec = (nowMillis - anchorWallMillis).coerceAtLeast(0L) / 1000.0
        val raw = elapsedSec * speedDaysPerSec
        return if (loopDays > 0.0) raw % loopDays else raw
    }

    fun simulatedMillis(
        nowMillis: Long,
        realTime: Boolean,
        anchorWallMillis: Long,
        startMillis: Long,
        speedDaysPerSec: Double,
        loopDays: Double,
    ): Long {
        if (realTime) return nowMillis
        val offset = offsetDays(nowMillis, anchorWallMillis, speedDaysPerSec, loopDays)
        return startMillis + (offset * DAY_MS).toLong()
    }

    /**
     * New anchor that keeps the simulated date exactly where it is when the speed or loop length
     * changes. Without this, a new speed would be applied retroactively to all the real time
     * elapsed since the anchor and the date would jump.
     */
    fun reanchor(
        nowMillis: Long,
        anchorWallMillis: Long,
        oldSpeedDaysPerSec: Double,
        oldLoopDays: Double,
        newSpeedDaysPerSec: Double,
        newLoopDays: Double,
    ): Long {
        var offset = offsetDays(nowMillis, anchorWallMillis, oldSpeedDaysPerSec, oldLoopDays)
        if (newLoopDays > 0.0) offset %= newLoopDays
        if (newSpeedDaysPerSec <= 0.0) return nowMillis
        return nowMillis - (offset / newSpeedDaysPerSec * 1000.0).toLong()
    }
}
