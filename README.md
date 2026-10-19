# 20137-1996-PX8 — Android Live Wallpaper

A native Android live wallpaper showing the Sun, all 8 planets, and near-Earth
asteroid **(20137) Angeljorba**, animated with real Keplerian orbital mechanics
(JPL's 3000 BC–3000 AD planetary elements, JPL Horizons element tables for the
asteroid, Newton-Raphson Kepler solve, full 3D ecliptic projection).

This is a from-scratch Kotlin/Android port of the browser prototype in
[`design_handoff_solar_system_wallpaper/`](design_handoff_solar_system_wallpaper/)
— see that folder's `README.md` for the original design spec. `support.js` and the
HTML/React-like scaffolding in that prototype were **not** reused; only the
orbital math and visual design were ported natively.

## App design

One screen. The live scene fills it edge-to-edge (under the status and
navigation bars); drag to rotate, pinch to zoom — the same gesture mapping as
the HTML reference, and the camera the wallpaper and widget use. It auto-saves
when a gesture ends.

The controls float on top as flat, frosted "Liquid Glass"-style panels
(`GlassUi.kt`). Android has no system glass material, so each panel re-draws
the live scene behind itself, offset to line up with the full-screen preview,
and blurs it with a `RenderEffect` on Android 12+ — the planets genuinely move
behind the glass. On top of the blur there is only a faint white gradient and a
thin light rim; no sheen, bevels or shadows. Controls are flat too: translucent
white tracks, a lighter glass pill for the selected segment, switches with a
white capsule thumb that fill with the accent when on. Older Android versions
fall back to a translucent fill.

- **Top capsule** — the app name, a dot for the asteroid data status (green:
  JPL data, amber: fetching, orange: built-in fallback) and the simulated date
  (marked "≈ extrapolated" outside the range the data covers). Tap it to jump
  to the Data tab.
- **Dock** — the options button and **Set as Wallpaper**, which opens the
  system's live-wallpaper preview/set screen directly
  (`ACTION_CHANGE_LIVE_WALLPAPER`), bypassing OEM wallpaper pickers that don't
  show a live wallpapers category.
- **Options sheet** — slides up above the dock and shows one tab at a time:
  - *Time*: Real-time or Custom. Custom has a start date (1000–2999 AD), a
    "Start from now" shortcut, a speed (0.5–400 simulated days per second) and
    a loop length (1–100 years).
  - *Style*: accent color, proportional sizes, planet labels, asteroid label,
    monochrome.
  - *View*: slow camera drift, home-screen swipe parallax, reset camera.
  - *Data*: status, source and last update of the asteroid data, a refresh
    button and a short explanation.

The accent color tints the asteroid *and* the app's own controls, so they
always match. All panels fade while you drag the scene, so they don't hide
what you're positioning.

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
   - **Planets** use JPL's long-span approximate elements (Standish, table
     2a/2b), fitted for 3000 BC – 3000 AD: a value at J2000 plus a rate per
     century for each element, and extra long-period terms for Jupiter–Neptune.
     `planetElementsAt(planet, T)` evaluates them at centuries-since-J2000 `T`
     and derives `w = ϖ − Ω` and `M = L − ϖ`. (The design handoff used table 1,
     fitted only for 1800–2050.) Outside 3000 BC – 3000 AD, the slow drift of
     each orbit's shape and orientation is held at the edge of the fit rather
     than extrapolated, while the planet keeps moving along its orbit at its
     mean motion — so positions become approximate but orbits never deform.
     Extrapolating linearly instead gives nonsense: by the year 20,000 it
     gives Saturn a negative eccentricity.
   - **The asteroid** uses a *table* of osculating elements from JPL Horizons,
     sampled every few months across up to 1600–2500 AD. JPL produced those by
     numerically integrating the orbit with the planets' pull included. For any
     date, `nearestOrbit()` picks the closest sample and propagates from it via
     `M = ma + n·(jd − epoch)`, with `n` (degrees/day) from the table or from
     Kepler's third law. Propagating a few weeks from a nearby sample is
     accurate; propagating decades from a single set of elements, as before,
     is not, because Jupiter and close passes by Mars keep changing the orbit.
     If Horizons is unreachable, a single JPL SBDB element set is used, and
     failing that, the one baked in at build time.
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
5. **3D → 2D screen projection**, in `ViewProjection.kt`: each point's
   distance from the Sun is compressed by `sqrt()` (so Neptune isn't pushed
   off-screen relative to Mercury), then rotated by the camera's azimuth and
   viewed from its tilt angle — the same camera you set by dragging/pinching
   the preview. The camera sits above the ecliptic with north up, so the near
   side of an orbit is lower on screen. (The handoff's projection had the
   vertical sign flipped, which made inclined orbits lean the wrong way.) The
   projection also returns each point's depth toward the viewer; the renderer
   sorts the Sun, planets and asteroid by it and draws far to near, so a body
   behind another is correctly hidden by it. Covered by `ViewProjectionTest.kt`.
6. **Sizes.** By default every planet is the same small dot. *Proportional
   sizes* (Style tab) scales each by its real radius, compressed as
   `radius^0.42` so Mercury stays visible and Jupiter doesn't swamp the inner
   planets, and enlarges the Sun. Distances are never to scale (see step 5).

## Project layout

- `app/src/main/java/com/philpf/solarsystemwallpaper/OrbitalMechanics.kt` — planet
  element table, Kepler solver, ecliptic rotation and nearest-epoch lookup. Plain
  Kotlin, covered by `app/src/test/.../OrbitalMechanicsTest.kt` (real opposition
  dates, far-future sanity, clock and parser behaviour).
- `SimulationClock.kt` — maps real time to simulated time (see Time drive below).
- `AsteroidOrbitFetcher.kt`, `EphemerisFormats.kt`, `AsteroidEphemeris.kt` —
  fetch the JPL element table, parse it, and store/cache it on the device.
- `GlassUi.kt` — the glass panel, backdrop blur, switch, segmented control and
  buttons.
- `ViewProjection.kt` — the camera projection and depth (plain Kotlin, tested).
- `app/src/main/java/com/philpf/solarsystemwallpaper/SolarSystemRenderer.kt` — the
  Canvas2D-style drawing code (ported from the reference `draw()`), shared by the
  live wallpaper and the in-app preview so both stay pixel-identical.
- `app/src/main/java/com/philpf/solarsystemwallpaper/MainActivity.kt` +
  `SolarSystemPreviewView.kt` — the single app screen described above. The
  preview (drag 0.35/0.25 sensitivity, tilt clamped 0–85°, zoom 0.3–6×, as in
  the reference's `setupCameraControls()`) is also the scene source the glass
  panels blur.
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
- **Asteroid data fetched by the app**: on open (at most weekly, or whenever
  you tap the top capsule), `MainActivity` fetches the JPL Horizons table in
  the background. It tries the widest date span first, falls back to narrower
  ones, and tries a direct request and then four read-only relays. The table is
  stored on the device, so the wallpaper and widget use it without any network
  access of their own. This needs the `INTERNET` permission, but only the app
  screen uses it; if the device is offline, the last stored data (or the
  build-time fallback) is used.
- **Touch/drag camera dropped from the wallpaper itself** — live wallpapers don't
  reliably own touch — but not from the app: the main screen reproduces the HTML
  reference's drag/pinch camera controls, and the resulting angle is saved as
  the wallpaper's fixed camera (default 22° tilt if never adjusted). Azimuth can
  additionally drift slowly and/or respond to home-screen swipe offset on top of
  the saved angle (both toggleable inline).
- **Time drive**: defaults to real wall-clock time. Custom mode plays forward
  from a start date at the chosen speed and **loops back to the start** after
  the chosen span. The clock is anchored to a stored real-time instant so it
  survives the wallpaper restarting, which means it also advances while the app
  is closed; without the loop, a day away at 30 days/sec would put the scene
  ~7,000 years in the future, far outside any valid model. Changing speed or
  loop length keeps the current simulated date instead of jumping.
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
and tap **Set as Wallpaper** — this jumps straight to the system's
live-wallpaper preview/set screen, which works even on OEM launchers whose own
wallpaper picker doesn't surface a "Live wallpapers" category. (The OS picker
at **Settings > Wallpaper > Live wallpapers**, where available, works too.)
