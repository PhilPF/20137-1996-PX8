# Handoff: Solar System Asteroid Tracker — Android Live Wallpaper

## Overview
An animated, interactive view of the solar system (Sun + 8 planets) tracking near-Earth asteroid **(20137) Angeljorba**, computed from real Keplerian orbital elements. Built as a browser prototype; the ask is to reimplement it as a native **Android Live Wallpaper (APK)**.

## About the Design Files
The bundled file (`Solar System Tracker.dc.html`) is a **design/behavior reference built in HTML + Canvas2D + a lightweight internal React-like runtime (`support.js`)** — it is not production Android code and should not be embedded as-is. `support.js` is a proprietary prototyping runtime with no Android equivalent; ignore it entirely and reimplement the *behavior* natively.

Recommended native approach: a `WallpaperService.Engine` that owns a render loop, drawn either with **Android `Canvas`** (`SurfaceHolder.lockCanvas()`) — closest 1:1 port of the 2D drawing code below — or a `GLSurfaceView`/OpenGL ES renderer for better battery efficiency on always-on rendering. Canvas2D is the simpler/faster port; recommend it unless perf profiling says otherwise.

## Fidelity
**High-fidelity for the visual system and orbital math (exact algorithm below); low-fidelity for the interaction model**, since live wallpapers on Android generally do NOT accept touch input the way an app screen does (the home screen intercepts touches). See "Wallpaper-Specific Adaptations" for what to keep vs. drop.

## Visual Design

**Background**: solid dark navy, `oklch(0.15 0.02 258)` (~`#171a24`, cool near-black).

**Palette**:
- Text/UI foreground: `oklch(0.92 0.01 258)` (near-white, cool)
- Sun: radial gradient `#fff3c4` → transparent, core `#ffe9a8`
- Orbit lines: `oklch(0.75 0.01 258 / 0.35)` (faint cool gray), 1px stroke
- Planet dots (3.6px radius circles), one hue each:
  - Mercury `#9a9a9a`, Venus `#e8d9a0`, Earth `#4da6ff`, Mars `#e07a5f`,
    Jupiter `#d9b382`, Saturn `#e8dcb0`, Uranus `#a0e8e0`, Neptune `#5f7fe0`
- Asteroid: accent color (default `#ffd166` amber; alternates `#ff6b4a`, `#5fd0ff`, `#ff5fa0`), drawn as a 4px filled dot + 8px ring outline at 50% alpha, orbit path stroked at 55% alpha
- Optional **monochrome mode**: everything (planets, orbits, asteroid, sun) renders in white/gray only — useful as a wallpaper variant that works over any home-screen icon color scheme

**Typography** (only relevant if you keep any on-screen label/HUD):
`-apple-system, 'Helvetica Neue', Helvetica, Arial, sans-serif` — substitute the Android system font (Roboto) 1:1. A "console" theme variant swaps to a monospace font, green-on-black (`#33ff66` on `#000`), uppercase labels, 0px corner radius — treat as a distinct theme toggle, not a default.

**Layout**: full-bleed canvas. Sun and orbits are drawn in an isometric-ish projection: rotate positions around the vertical (Z) axis by a camera azimuth, then tilt by a fixed elevation angle (default 22–23°), then scale radially from the Sun. Camera/view is otherwise static for the wallpaper use case (see below).

## Orbital Mechanics — reimplement exactly (algorithm reference, language-agnostic)

This is real celestial mechanics, not a stylized animation — port the math faithfully so planets/asteroid stay astronomically accurate over time.

1. **Planetary elements**: use standard J2000 mean orbital elements + per-century rates for all 8 planets (semi-major axis `a`, eccentricity `e`, inclination `i`, mean longitude `L`, longitude of perihelion `ϖ` ("wbar"), longitude of ascending node `Ω` — see the `PLANETS` array in the reference file for exact numeric coefficients, these are standard published values, e.g. from JPL/Standish 1992).
2. **Time**: convert wallclock date → Julian Date (`JD = unixMillis/86400000 + 2440587.5`), then centuries since J2000: `T = (JD − 2451545.0) / 36525`.
3. **Elements at time T**: `a,e,i,L,ϖ,Ω` each `= base + rate*T`. Derive `ω = ϖ − Ω` (argument of perihelion) and `M = L − ϖ` (mean anomaly).
4. **Kepler's equation**: solve `M = E − e·sin(E)` for eccentric anomaly `E` via Newton-Raphson (~10 iterations, converges to 1e-9 rad).
5. **True anomaly**: `ν = 2·atan2(√(1+e)·sin(E/2), √(1−e)·cos(E/2))`; radius `r = a(1 − e·cos E)`.
6. **Orbital-plane → ecliptic (3D) coordinates**: standard rotation by `ω`, `i`, `Ω` (three Euler rotations) — see `orbitToEcliptic()` in the reference for the exact matrix form.
7. **Orbit path** (the faint ellipse line): re-run steps 4–6 sampling `ν` uniformly over 0–2π (180–220 samples) instead of solving Kepler's equation, using `r = a(1−e²)/(1+e·cos ν)` directly.
8. **Projection to screen**: radial distance from Sun `r3 = |p|` maps to on-screen pixels via either `sqrt(r3)` or linear scaling (both supported; sqrt compresses outer planets closer, better for a wallpaper composition so Neptune isn't way off-screen) against a `maxAU = 31` reference distance. Then apply the azimuth rotation + tilt (see Visual Design) to get final x/y.
9. **Asteroid (20137) Angeljorba**: same math as planets, using orbital elements fetched live from JPL's Small-Body Database API (`ssd-api.jpl.nasa.gov/sbdb.api?sstr=20137&full-prec=1`) at load time, mean anomaly propagated forward from the element epoch using `n = (GaussianK · 180/π) / a^1.5` degrees/day. **For an offline wallpaper, do not depend on this live fetch** — see below.

## Wallpaper-Specific Adaptations (decisions for the developer to make, not carried over 1:1)

- **Drop the touch/drag-to-rotate camera and the bottom control bar** (play/pause, date picker, speed slider, Real-time/Now/Reset buttons) — live wallpapers don't own touch input reliably; ship a fixed, pleasant default camera angle (tilt ~22°, slow constant azimuth drift is a nice ambient touch, entirely optional).
- **Time drive**: default to real-time (1 real second = 1 real day is *too fast* for planets to visibly move on human timescales for anything but the asteroid/inner planets — recommend real-world time, i.e. positions update as actual wall-clock time passes, OR a slow constant multiplier like 20–50 days/sec so motion is perceptible as ambient wallpaper animation without a UI to control it).
- **Asteroid orbital elements should be baked in at build time**, not fetched from JPL at wallpaper runtime — a wallpaper shouldn't require network/INTERNET permission or risk a broken state if the API is unreachable. Hardcode the fallback elements already in the reference file (`a=2.2895838, e=0.3045701, i=5.86541, om=150.65575`, epoch = build date) as the shipped values, or do a one-time fetch+embed during the build/CI step if fresher elements are wanted.
- **Battery/perf**: pause the render loop in `onVisibilityChanged(false)` / `onSurfaceDestroyed` (standard Android live-wallpaper lifecycle) — don't redraw when the wallpaper isn't visible (home screen not shown). Target 30fps, not 60 — orbital motion is slow, no need for high frame rate.
- **Optional nice-to-have**: use the home-screen page-swipe offset (`onOffsetsChanged`) to drive a *subtle* camera parallax/azimuth shift instead of drag — this is the one interaction pattern live wallpapers commonly support well.
- **Settings**: if you want user-configurable options (asteroid color, monochrome mode, show/hide labels, planet-label toggle — these exist as tweakable props in the reference), implement via a Live Wallpaper "Settings" activity (`android:settingsActivity` in the wallpaper's XML metadata) rather than on-canvas controls.

## Design Tokens Summary
- Background: `#171a24` (oklch 0.15 0.02 258)
- Foreground/UI: `#e8e9ee`-ish (oklch 0.92 0.01 258)
- Orbit line: cool gray @ 35% alpha
- Accent/asteroid default: `#ffd166` (alternates `#ff6b4a`, `#5fd0ff`, `#ff5fa0`)
- Radius (UI chrome, if ported): 6px, or 0px in "console" theme
- Font: system sans (Roboto on Android), or monospace in "console" theme

## Assets
No external image/icon assets — everything is vector/procedural (Canvas2D arcs, gradients, lines). No fonts to bundle beyond system defaults.

## Files
- `Solar System Tracker.dc.html` — full reference implementation (all orbital math, drawing, and interaction logic in one file). The `PLANETS` array and every function above `class Component` is plain, portable JS with no framework dependency — this is the part to port most literally.
