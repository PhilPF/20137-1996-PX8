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
- `app/src/main/java/com/philpf/solarsystemwallpaper/SolarSystemWallpaperService.kt`
  — the `WallpaperService.Engine` that owns the render loop (`SurfaceHolder` +
  `Canvas`, ~30fps, paused when not visible).
- `app/src/main/java/com/philpf/solarsystemwallpaper/SettingsActivity.kt` +
  `WallpaperPrefs.kt` — the wallpaper picker's "Settings" screen (monochrome mode,
  label toggles, asteroid accent color, time drive, ambient camera drift, swipe
  parallax).

## Decisions made per the handoff's "developer to decide" list

- **Renderer**: Canvas2D-style `SurfaceHolder`/`Canvas` (not OpenGL) — the
  recommended default.
- **No network/INTERNET permission**: the asteroid's orbital elements are baked
  in at build time (`AsteroidAngeljorba` in `OrbitalMechanics.kt`), using the
  same fallback catalog values as the reference file.
- **Touch/drag camera and control bar dropped** — live wallpapers don't reliably
  own touch. The camera is a fixed 22° tilt; azimuth optionally drifts slowly and
  optionally responds to home-screen swipe offset (both toggleable in Settings).
- **Time drive**: defaults to real wall-clock time, with an "Accelerated
  (30 days/sec)" option in Settings for more visible ambient motion.
- **Target 30fps**, render loop stops in `onVisibilityChanged(false)` /
  `onSurfaceDestroyed`.

## Building the APK

This sandbox environment has no Android SDK and its network policy blocks
`dl.google.com`, so the APK can't be compiled here. Two ways to actually get
a build:

1. **GitHub Actions (already set up)** — `.github/workflows/build-apk.yml` builds
   a debug APK on every push and uploads it as a workflow artifact
   (`solar-system-wallpaper-debug-apk`). Push this branch, open the Actions run,
   and download the artifact.
2. **Locally with Android Studio** — open the project root, let it sync, then
   `Build > Build Bundle(s) / APK(s) > Build APK(s)`, or from a terminal with the
   Android SDK installed: `./gradlew assembleDebug`.

Install with `adb install app/build/outputs/apk/debug/app-debug.apk`, then set
it as your wallpaper via **Settings > Wallpaper > Live wallpapers > Solar System
Tracker**.
