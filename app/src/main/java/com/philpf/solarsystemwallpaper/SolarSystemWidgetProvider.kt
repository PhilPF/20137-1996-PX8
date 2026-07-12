package com.philpf.solarsystemwallpaper

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Home-screen widget rendering the exact same scene as the live wallpaper (same
 * [SolarSystemRenderer], same [WallpaperPrefs] — saved camera, accent color, asteroid orbit),
 * at whatever size the launcher gives it. Widgets don't get a continuous render surface like a
 * wallpaper does, so this draws to a [Bitmap] and pushes it via [RemoteViews] instead: on the
 * OS's own ~30-minute floor (android:updatePeriodMillis), on resize, and whenever
 * [WallpaperPrefs] notifies of a change (settings, saved camera, a fresh trajectory fetch).
 *
 * Deliberately excludes the wallpaper's ambient azimuth drift and swipe parallax — those are
 * continuous-animation effects tied to a running render loop / touch offsets, neither of which
 * has a meaningful equivalent for a periodically-refreshed static image. Everything else (planet
 * positions at the current simulated time, the saved base camera, all appearance settings)
 * matches exactly.
 */
class SolarSystemWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        /** Call after any change that should be visible on the widget right away. */
        fun requestUpdate(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, SolarSystemWidgetProvider::class.java))
            ids.forEach { id -> updateWidget(context, appWidgetManager, id) }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val density = context.resources.displayMetrics.density
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            // MIN_WIDTH/MIN_HEIGHT report the widget's current on-screen size in the device's
            // present orientation (the naming is a historical artifact — MAX_WIDTH/MAX_HEIGHT
            // are the size in the *other* orientation, not useful here).
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            val widthPx = (widthDp * density).toInt().coerceAtLeast(1)
            val heightPx = (heightDp * density).toInt().coerceAtLeast(1)

            // RGB_565: the scene always fully covers the bitmap with an opaque background, so no
            // alpha channel is needed — halves memory and the RemoteViews IPC payload, which
            // matters since a widget can be resized arbitrarily large.
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)

            val prefs = WallpaperPrefs(context)
            val renderer = SolarSystemRenderer(density)
            val camera = SolarSystemRenderer.Camera(
                azimuthDeg = prefs.cameraAzimuthDeg,
                tiltDeg = prefs.cameraTiltDeg,
                zoom = prefs.cameraZoom,
            )
            val style = SolarSystemRenderer.Style(
                monochrome = prefs.monochrome,
                showPlanetLabels = prefs.showPlanetLabels,
                showAsteroidLabel = prefs.showAsteroidLabel,
                asteroidColorHex = prefs.asteroidColor,
            )
            renderer.render(
                canvas, widthPx.toFloat(), heightPx.toFloat(),
                prefs.simulatedMillis(), camera, style, prefs.asteroidOrbit,
            )

            val views = RemoteViews(context.packageName, R.layout.widget_solar_system)
            views.setImageViewBitmap(R.id.widgetImage, bitmap)

            val openAppIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, appWidgetId, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetImage, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
