# RawLite

RawLite is an experimental Android camera app for manual RAW video capture on
Google Pixel 6.

The project explores a minimal, one-hand camera interface for recording:

- CinemaDNG image sequences.
- Packed RAW10 clips.
- PURE 10 video: HEVC Main10 HLG with AAC audio in the final MP4.

RawLite was designed as a product architecture and vibe-coding project: the
product direction, interaction model and visual language were defined by Prem
Avalos, with implementation iterated through AI-assisted development.

## What It Does

RawLite focuses on manual image capture with as little interface noise as
possible. The camera view uses a black workspace, white controls, yellow
selection accents and red only for recording or destructive actions.

The main interface is built around a D-pad so the app can be operated with one
hand. Exposure, focus, white balance, frame rate, lens and capture mode are
controlled without leaving the camera view.

Supported capture modes:

- CinemaDNG FULL: full-resolution DNG frame sequence.
- RAW10 2K: packed Bayer RAW10 stream with frame metadata.
- PURE 10: 10-bit HEVC Main10 HLG video using hardware encoding.

The app also includes an internal gallery and player for recorded clips.

## How It Works

RawLite uses Android Camera2 directly instead of relying on the stock camera
pipeline. The app keeps manual control over capture settings and stores enough
metadata to inspect, replay or convert each take later.

For RAW workflows, RawLite records Bayer sensor data and per-frame metadata. For
PURE 10, it records hardware-encoded 10-bit HEVC video and stores audio as a
separate PCM WAV file. Audio and video are synchronized with a `sync.json` file
based on Android clock timestamps.

Each take is stored as its own folder under the app's private Movies directory:

```text
Android/data/com.example.rawlite/files/Movies/RawLite_YYYYMMDD_HHMMSS/
```

Typical files include:

- `clip.json`: sensor, format, color and capture metadata.
- `frames.csv`: per-frame timing and camera settings.
- `telemetry.csv`: thermal and storage telemetry.
- `audio.wav`: PCM audio backup/intermediate when audio is enabled.
- `sync.json`: audio/video timing information.
- `frame_XXXXXX.dng`, `clip.raw10`, `pure10.mp4` or `pure10_final.mp4`, depending on the mode.

## Internal Player

RawLite can review its own clips without leaving the app.

The player supports:

- PURE 10 playback with hardware HEVC Main10 decoding.
- RAW10 and CinemaDNG playback as timed image sequences.
- Pause, seek, frame stepping and mute.
- WAV audio sync.
- Thumbnail gallery.
- Deletion with confirmation.

RAW playback is intentionally conservative: the original capture files are not
modified, and heavy RAW sequences may skip frames during playback to preserve
timing.

## DaVinci Resolve Workflow

CinemaDNG sequences can be imported directly into DaVinci Resolve.

Packed RAW10 clips can be converted from the internal gallery with the
`CONVERTIR` action. The conversion creates a separate CinemaDNG take next to
the original RAW10 folder, preserving the original capture.

The desktop converter remains available for inspection and recovery workflows:

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements-converter.txt
.venv/bin/python tools/rawlite_to_dng.py /path/to/RawLite_YYYYMMDD_HHMMSS
```

The converter creates a `dng/` folder that can be imported as a CinemaDNG
sequence. It also exports synchronized audio for the timeline when audio is
available.

RAW10 is a RawLite-native recording format. It is preserved as raw capture data
and converted only when needed for editing software.

## Current Scope

RawLite is an alpha-stage technical prototype. It is currently tuned around the
Pixel 6 hardware profile and is not presented as a general Android camera
replacement.

The project prioritizes:

- Minimal interface design.
- Manual capture control.
- Energy-aware operation.
- Preserving raw image data.
- Exportable workflows for color grading.

The main known limitation is that RAW capture and playback are demanding. Smooth
real-time playback depends on clip format, resolution, frame rate and device
thermal state.

## Build

Use JDK 21 for Android builds:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:assembleDebug
```

For local validation:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:testDebugUnitTest :app:lintDebug
```

Development installs should preserve existing takes:

```bash
tools/install_debug_safely.sh app/build/outputs/apk/debug/app-debug.apk
```

That script installs with `adb install -r` and avoids uninstalling the app,
because Android removes private app storage when a package is uninstalled.

## Project Role

Product architecture, visual direction and interaction concept:

```text
Prem Avalos
```

Engineering implementation:

```text
AI-assisted development with Codex
```
