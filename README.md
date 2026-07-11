# Solar System Tracker — Android Live Wallpaper

A native Android live wallpaper showing the Sun, all 8 planets, and near-Earth
asteroid **(20137) Angeljorba**, animated with real Keplerian orbital mechanics
(J2000 mean elements, Newton-Raphson Kepler solve, full 3D ecliptic projection).

This is a from-scratch Kotlin/Android port of the browser prototype in
[`design_handoff_solar_system_wallpaper/`](design_handoff_solar_system_wallpaper/)
— see that folder's `README.md` for the original design spec. `support.js` and the
HTML/React-like scaffolding in that prototype were **not** reused; only the
orbital math and visual design were ported natively.

## Project layout

- `app/src/main/java/com/philpf/solarsystemwallpaper/OrbitalMechanics.kt` — planet
  elements table + Kepler solver + ecliptic projection, ported line-for-line from
  the reference `PLANETS` array and math functions.
- `app/src/main/java/com/philpf/solarsystemwallpaper/MainActivity.kt` — launcher
  screen with a "Set as Live Wallpaper" button that opens the system's
  live-wallpaper preview/set screen directly via `ACTION_CHANGE_LIVE_WALLPAPER`,
  bypassing OEM wallpaper pickers that don't surface a live wallpapers category.
- `app/src/main/java/com/philpf/solarsystemwallpaper/SolarSystemWallpaperService.kt`
  — the `WallpaperService.Engine` that owns the render loop (`SurfaceHolder` +
  `Canvas`, ~30fps, paused when not visible).
- `app/src/main/java/com/philpf/solarsystemwallpaper/SettingsActivity.kt` +
  `WallpaperPrefs.kt` — the wallpaper picker's "Settings" screen (monochrome mode,
  label toggles, asteroid accent color, ambient camera drift, swipe parallax) and
  the time-drive controls: **Real-time** vs **Custom**, a date/time picker to set
  where the simulation starts, and a speed selector (0.5–400 simulated days per
  real second, matching the original prototype's speed presets). Custom mode is
  anchored via a persisted (real-time, sim-time) pair in `WallpaperPrefs`, so the
  extrapolated position survives the wallpaper engine being torn down and
  recreated (screen off/on, etc.) without drifting or resetting.

## Decisions made per the handoff's "developer to decide" list

- **Renderer**: Canvas2D-style `SurfaceHolder`/`Canvas` (not OpenGL) — the
  recommended default.
- **No network/INTERNET permission**: the asteroid's orbital elements are baked
  in at build time (`AsteroidAngeljorba` in `OrbitalMechanics.kt`), using the
  same fallback catalog values as the reference file.
- **Touch/drag camera and control bar dropped** — live wallpapers don't reliably
  own touch. The camera is a fixed 22° tilt; azimuth optionally drifts slowly and
  optionally responds to home-screen swipe offset (both toggleable in Settings).
- **Time drive**: defaults to real wall-clock time, with a "Custom" mode in
  Settings that lets you pick a start date/time and a speed (0.5–400 simulated
  days/sec) for more visible ambient motion or jumping to an arbitrary date.
- **Target 30fps**, render loop stops in `onVisibilityChanged(false)` /
  `onSurfaceDestroyed`.

## Building the APK

This sandbox environment has no Android SDK and its network policy blocks
`dl.google.com`, so the APK can't be compiled here. Two ways to actually get
a build:

1. **GitHub Actions (already set up)** — `.github/workflows/build-apk.yml` is a
   manual-only workflow (`workflow_dispatch`, no auto-trigger on push, to avoid
   burning CI minutes on every commit). Run it from the repo's **Actions** tab
   ("Build APK" > "Run workflow"). It builds a debug APK and publishes it
   directly to the **`debug-build`** release (not a zipped Actions artifact —
   public releases don't require a GitHub login and the `.apk` downloads
   straight from a phone browser, ready to tap-install).
2. **Locally with Android Studio** — open the project root, let it sync, then
   `Build > Build Bundle(s) / APK(s) > Build APK(s)`, or from a terminal with the
   Android SDK installed: `./gradlew assembleDebug`.

Install with `adb install app/build/outputs/apk/debug/app-debug.apk`. Open the
**Solar System Tracker** app from your app drawer and tap **Set as Live
Wallpaper** — this jumps straight to the system's live-wallpaper preview/set
screen, which works even on OEM launchers whose own wallpaper picker doesn't
surface a "Live wallpapers" category. (The OS picker at **Settings > Wallpaper
> Live wallpapers > Solar System Tracker** works too, where available.)
