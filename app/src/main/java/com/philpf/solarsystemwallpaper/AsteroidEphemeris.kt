package com.philpf.solarsystemwallpaper

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * On-device store for one body's orbital-element table (see [AsteroidOrbitFetcher]). Kept as a
 * small text file rather than SharedPreferences because a Horizons table holds thousands of rows.
 *
 * Read on every frame by the wallpaper, preview and widget, so the parsed table is cached in
 * memory and the file is only re-checked every couple of seconds. The app and wallpaper share
 * a process, so a [save] is visible immediately anyway.
 */
open class EphemerisStore(private val fileName: String) {

    class Snapshot(val label: String, val rows: List<AsteroidOrbit>, val modified: Long) {
        val firstJd: Double get() = rows.first().epochJd
        val lastJd: Double get() = rows.last().epochJd
        val isTable: Boolean get() = rows.size > 1
    }

    @Volatile private var cached: Snapshot? = null
    @Volatile private var lastCheckElapsed = 0L

    private fun file(context: Context) = File(context.applicationContext.filesDir, fileName)

    fun save(context: Context, label: String, rows: List<AsteroidOrbit>) {
        if (rows.isEmpty()) return
        val target = file(context)
        val tmp = File(target.parentFile, "$fileName.tmp")
        tmp.writeText(EphemerisFormats.encode(label, rows))
        if (!tmp.renameTo(target)) {
            target.writeText(EphemerisFormats.encode(label, rows))
            tmp.delete()
        }
        cached = Snapshot(label, rows.sortedBy { it.epochJd }, target.lastModified())
    }

    fun delete(context: Context) {
        file(context).delete()
        cached = null
    }

    /** The stored table, or null if nothing has been fetched yet. */
    fun snapshot(context: Context): Snapshot? {
        val now = SystemClock.elapsedRealtime()
        val current = cached
        if (current != null && now - lastCheckElapsed < RECHECK_MS) return current
        lastCheckElapsed = now
        val f = file(context)
        if (!f.exists()) return current
        if (current != null && current.modified == f.lastModified()) return current
        return try {
            val (label, rows) = EphemerisFormats.decode(f.readText())
            if (rows.isEmpty()) current else Snapshot(label, rows, f.lastModified()).also { cached = it }
        } catch (_: Exception) {
            current
        }
    }

    fun orbitAtOrNull(context: Context, jd: Double): AsteroidOrbit? =
        snapshot(context)?.let { nearestOrbit(it.rows, jd) }

    /**
     * Whether [jd] is inside the range where the stored data is reliable: within the Horizons
     * table's span, or within ~5 years of a single-epoch element set.
     */
    fun isReliableAt(context: Context, jd: Double): Boolean {
        val s = snapshot(context) ?: return false
        val margin = if (s.isTable) 200.0 else 5 * 365.25
        return jd >= s.firstJd - margin && jd <= s.lastJd + margin
    }

    private companion object {
        const val RECHECK_MS = 2_000L
    }
}

/** Angeljorba's table, with the build-time elements as a last resort so it always has an orbit. */
object AsteroidEphemeris : EphemerisStore("asteroid_ephemeris.csv") {

    private val buildTimeFallback = AsteroidOrbit(
        AsteroidElements.epochJd, AsteroidElements.a, AsteroidElements.e, AsteroidElements.i,
        AsteroidElements.om, AsteroidElements.w, AsteroidElements.ma,
    )

    fun orbitAt(context: Context, jd: Double): AsteroidOrbit = orbitAtOrNull(context, jd) ?: buildTimeFallback
}

/** One store per added extra object, keyed by [ExtraObject.id]. */
object ExtraEphemerides {
    private val stores = HashMap<String, EphemerisStore>()

    @Synchronized
    fun store(id: String): EphemerisStore = stores.getOrPut(id) { EphemerisStore("extra_$id.csv") }
}
