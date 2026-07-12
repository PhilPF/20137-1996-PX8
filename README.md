# 20137-1996-PX8 — Android Live Wallpaper

A native Android live wallpaper showing the Sun, all 8 planets, and near-Earth
asteroid **(20137) Angeljorba**, animated with real Keplerian orbital mechanics
(J2000 mean elements, Newton-Raphson Kepler solve, full 3D ecliptic projection).

This is a from-scratch Kotlin/Android port of the browser prototype in
[`design_handoff_solar_system_wallpaper/`](design_handoff_solar_system_wallpaper/)
— see that folder's `README.md` for the original design spec. `support.js` and the
HTML/React-like scaffolding in that prototype were **not** reused; only the
orbital math and visual design were ported natively.

## App design

The app is a single screen: a full-bleed live preview you can drag to rotate and
pinch to zoom (same gesture mapping as the HTML reference's camera controls),
with every option overlaid directly on top — no separate settings or preview
screens to navigate between. This mirrors the reference's own bottom control
bar, adapted into stacked rows since a phone doesn't have the desktop width for
one long row of controls.

- **Title** is the app name, shown top-left over the canvas.
- **Always visible**: accent color swatches and the two primary actions,
  **Options** and **Set as Live Wallpaper** — kept deliberately minimal so the
  live preview isn't crowded on open.
- **Options** (collapsed by default; tap to expand): toggle chips (monochrome
  mode, planet labels, asteroid label, ambient camera drift, home-screen swipe
  parallax), time drive (Real-time / Custom, with a start date/time picker and
  a speed selector — 0.5–400 simulated days/sec, the original prototype's speed
  presets — once Custom is selected), and Reset View.
- **Accent color swatches**: picks the asteroid's color *and* the app's own UI
  accent (buttons, title, active-state highlights) at once, so they always
  match — six options (amber, coral, sky blue, pink, mint, violet).
- **Set as Live Wallpaper** jumps straight to the system's live-wallpaper
  preview/set screen via `ACTION_CHANGE_LIVE_WALLPAPER`, bypassing OEM
  wallpaper pickers that don't surface a live wallpapers category.

Dragging/pinching the preview auto-saves the camera angle as soon as the
gesture ends — there's no separate "save" step, since positioning happens on
the same screen used to set the wallpaper.

## Home-screen widget

A resizable widget (`SolarSystemWidgetProvider.kt`) shows the same scene as
the wallpaper — same renderer, same saved camera, accent color, and asteroid
orbit from `WallpaperPrefs` — at whatever size you place/resize it to on the
home screen. It excludes the wallpaper's ambient azimuth drift and swipe
parallax, since those are continuous-animation effects with no equivalent for
a periodically-refreshed static image; everything else (planet positions,
camera angle, appearance settings) matches exactly.

Widgets don't get a continuous render surface the way a wallpaper does —
Android enforces a 30-minute minimum background refresh interval platform-wide
for battery reasons, which isn't something an app can configure around
without requesting the "exact alarms" special permission (deliberately not
added here, to keep the permission footprint minimal). To keep it useful
despite that floor, the widget also redraws immediately whenever: you resize
it, any setting changes, you save a new camera angle, or a trajectory fetch
completes — `WallpaperPrefs` calls `SolarSystemWidgetProvider.requestUpdate()`
after every mutation. Tapping the widget opens the app.

## How the trajectories are computed

All position math lives in `OrbitalMechanics.kt`, in plain functions with no
Android dependencies (easy to unit-test or read standalone). Same algorithm
for planets and the asteroid — only where the six input numbers come from
differs.

1. **The six orbital elements**, for any orbiting body at a specific moment
   (its "epoch"): `a` (semi-major axis, in AU — orbit size), `e` (eccentricity
   — 0 is circular, closer to 1 is a stretched ellipse), `i` (inclination —
   tilt of the orbital plane vs. Earth's), `om`/Ω (longitude of ascending
   node — where the orbit crosses Earth's plane going north), `w`/ω (argument
   of perihelion — where along the orbit the closest-to-Sun point sits,
   measured from that crossing), `ma`/M (mean anomaly — where the body sits
   along its orbit at the epoch, as an angle).
   - Planets use published J2000 mean elements plus a per-century drift rate
     for each (the `PLANETS` array), so `planetElementsAt(planet, T)` just
     linearly extrapolates `a, e, i, L, ϖ, Ω` to centuries-since-J2000 `T`,
     then derives `w = ϖ − Ω` and `M = L − ϖ`.
   - The asteroid uses real osculating elements for a specific epoch (fetched
     live from JPL, or the build-time-baked fallback — see above), then its
     mean anomaly is propagated forward in time via `M = ma + n·(jd − epoch)`,
     where `n` (mean motion, degrees/day) comes directly from Kepler's third
     law: `n = (k·180/π) / a^1.5`, `k` being the Gaussian gravitational
     constant.
2. **Mean anomaly → true position in the orbital plane.** `M` is an angle that
   advances at a constant rate, but a body actually moves faster near
   perihelion — so `solveKepler()` numerically solves Kepler's equation
   `M = E − e·sin(E)` for the eccentric anomaly `E` (Newton-Raphson, ~10
   iterations), then `trueAnomalyFromE()` converts `E` to the true anomaly `ν`
   (the real angle from perihelion) and the radius `r = a(1 − e·cos E)`.
3. **Orbital plane → 3D ecliptic coordinates.** `orbitToEcliptic()` rotates
   the flat `(r, ν)` position by three Euler angles — `w`, `i`, `Ω` — the
   standard textbook rotation matrix for going from "the orbit's own 2D plane"
   to "3D space centered on the Sun, in Earth's reference plane."
4. **The faint orbit *paths*** (as opposed to each body's current dot) are
   drawn by `orbitPath()`, which reuses the same ecliptic rotation but skips
   Kepler's equation — it samples the true anomaly `ν` uniformly over a full
   turn and gets `r` directly from the polar ellipse equation
   `r = a(1−e²)/(1+e·cos ν)`, since there's no "time" involved in tracing the
   whole shape.
5. **3D → 2D screen projection**, in `SolarSystemRenderer.project()`: each
   point's distance from the Sun is compressed by `sqrt()` (so Neptune isn't
   pushed off-screen relative to Mercury), then rotated by the camera's
   azimuth and tilted by its elevation angle — the same camera you set by
   dragging/pinching the preview.

## Project layout

- `app/src/main/java/com/philpf/solarsystemwallpaper/OrbitalMechanics.kt` — planet
  elements table + Kepler solver + ecliptic projection, ported line-for-line from
  the reference `PLANETS` array and math functions.
- `app/src/main/java/com/philpf/solarsystemwallpaper/SolarSystemRenderer.kt` — the
  Canvas2D-style drawing code (ported from the reference `draw()`), shared by the
  live wallpaper and the in-app preview so both stay pixel-identical.
- `app/src/main/java/com/philpf/solarsystemwallpaper/MainActivity.kt` +
  `SolarSystemPreviewView.kt` — the single app screen described above: the
  interactive preview (drag 0.35/0.25 sensitivity, tilt clamped 0–85°, zoom
  clamped 0.3–6×, matching the reference's `setupCameraControls()`) plus all
  the option chips/swatches/pickers built and styled dynamically in code so
  they can react to the live-selected accent color.
- `app/src/main/java/com/philpf/solarsystemwallpaper/SolarSystemWallpaperService.kt`
  — the `WallpaperService.Engine` that owns the render loop (`SurfaceHolder` +
  `Canvas`, ~30fps, paused when not visible) and layers ambient azimuth drift /
  swipe parallax on top of the saved camera.
- `app/src/main/java/com/philpf/solarsystemwallpaper/SolarSystemWidgetProvider.kt`
  — the resizable home-screen widget; renders the same scene to a `Bitmap` and
  pushes it via `RemoteViews` (widgets don't get a `Canvas` render surface).
- `app/src/main/java/com/philpf/solarsystemwallpaper/WallpaperPrefs.kt` — all
  persisted state (appearance, time drive, saved camera). Custom time mode and
  the saved camera are both anchored via persisted values here rather than
  in-memory engine/activity state, so they survive the wallpaper engine being
  torn down and recreated (screen off/on, etc.) without drifting or resetting.
- `app/src/main/java/com/philpf/solarsystemwallpaper/SettingsActivity.kt` — a
  thin redirect to `MainActivity`, kept only because `res/xml/wallpaper.xml`'s
  `android:settingsActivity` needs a valid target for OS wallpaper pickers whose
  settings gear icon launches it directly.

## Decisions made per the handoff's "developer to decide" list

- **Renderer**: Canvas2D-style `SurfaceHolder`/`Canvas` (not OpenGL) — the
  recommended default.
- **Asteroid trajectory verified live from the app, not just baked at build
  time**: `MainActivity` fetches real elements from JPL's Small-Body Database
  on open (`AsteroidOrbitFetcher.kt`, same direct-request-then-relay chain as
  the HTML reference's `fetchAsteroid()`), shows a status line so you can see
  whether it's live or fallback, and lets you tap it to retry. The result is
  cached in `WallpaperPrefs` so the wallpaper engine's next frame picks it up
  too. This does require `INTERNET` permission (the one place in the app that
  needs it) — but only `MainActivity` ever makes the network call; the
  `WallpaperService` itself never does, it only reads whatever was last
  cached, and falls back to the build-time-baked `AsteroidElements.kt`
  (generated by `:app:generateAsteroidElements` in `app/build.gradle.kts`,
  same JPL source, fetched once at build time) if no live fetch has ever
  succeeded. So the shipped wallpaper still can't end up broken if the device
  is offline — it just won't have as fresh an orbit.
- **Touch/drag camera dropped from the wallpaper itself** — live wallpapers don't
  reliably own touch — but not from the app: the main screen reproduces the HTML
  reference's drag/pinch camera controls, and the resulting angle is saved as
  the wallpaper's fixed camera (default 22° tilt if never adjusted). Azimuth can
  additionally drift slowly and/or respond to home-screen swipe offset on top of
  the saved angle (both toggleable inline).
- **Time drive**: defaults to real wall-clock time, with a "Custom" mode that
  lets you pick a start date/time and a speed (0.5–400 simulated days/sec) for
  more visible ambient motion or jumping to an arbitrary date.
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
app from your app drawer, drag/pinch to position the view, adjust any options,
and tap **Set as Live Wallpaper** — this jumps straight to the system's
live-wallpaper preview/set screen, which works even on OEM launchers whose own
wallpaper picker doesn't surface a "Live wallpapers" category. (The OS picker
at **Settings > Wallpaper > Live wallpapers**, where available, works too.)
