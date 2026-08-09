# RawLite

RawLite is an Android camera prototype focused on manual RAW video capture,
minimal interaction design and exportable post-production workflows. The current
hardware reference target is the Google Pixel 6.

The application supports three capture workflows:

- CinemaDNG FULL: full-resolution DNG image sequences.
- RAW10 2K: packed Bayer RAW10 clips with frame-level metadata.
- PURE 10: hardware-encoded HEVC Main10 HLG video with AAC audio in the final
  MP4.

RawLite was developed as a product architecture and AI-assisted engineering
project. Product direction, interaction design and visual language were defined
by Prem Avalos; implementation was iterated with Codex as the engineering
assistant.

## Product Direction

RawLite prioritizes a low-noise camera interface for manual video capture. The
visual system uses a black workspace, white controls, yellow selection accents
and red only for recording or destructive actions.

The primary interaction model is built around one-handed operation. A D-pad
controls parameter navigation and adjustment directly from the camera view,
including exposure, ISO, white balance, focus, frame rate, lens selection and
capture format.

The project emphasizes:

- Minimal interface density.
- Direct manual control.
- Energy-aware capture behavior.
- Preservation of original RAW data.
- Practical export paths for color grading and editorial workflows.

## Capture Pipeline

RawLite uses Android Camera2 directly. The app manages capture requests,
metadata collection, storage and playback without relying on the stock camera
application pipeline.

For RAW workflows, RawLite stores Bayer sensor data with per-frame metadata. For
PURE 10, RawLite records 10-bit HEVC Main10 HLG through hardware encoding and
muxes audio into the final MP4. Synchronization metadata is retained for
inspection and recovery workflows.

Each recording is stored in its own take directory:

```text
Android/data/com.example.rawlite/files/Movies/RawLite_YYYYMMDD_HHMMSS/
```

Typical take contents include:

- `clip.json`: capture format, sensor profile, color metadata and session
  configuration.
- `frames.csv`: per-frame timing and camera settings.
- `telemetry.csv`: storage, thermal and runtime telemetry.
- `sync.json`: audio/video synchronization metadata.
- `audio.wav`: PCM audio intermediate or backup when applicable.
- `clip.raw10`, `frame_XXXXXX.dng`, `pure10.mp4` or `pure10_final.mp4`,
  depending on the selected capture mode.

## Gallery and Playback

RawLite includes an internal gallery and review player for captured takes.

Supported review features include:

- Hardware HEVC Main10 playback for PURE 10.
- Timed RAW10 and CinemaDNG sequence playback.
- Pause, seek, frame stepping and mute.
- Thumbnail generation.
- Synchronized audio playback where available.
- Clip deletion with confirmation.

RAW playback is intentionally conservative. Original capture files are not
modified, and demanding RAW sequences may skip frames during review to preserve
timeline synchronization.

## RAW10 and CinemaDNG Workflow

CinemaDNG sequences can be imported directly into DaVinci Resolve.

RAW10 is a RawLite-native packed capture format. It is designed to preserve
sensor data efficiently during recording and can be converted to CinemaDNG from
the internal gallery with the `CONVERTIR` action. Conversion creates a separate
CinemaDNG take while preserving the original RAW10 recording.

A desktop converter is also available for inspection, recovery and batch
workflows:

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements-converter.txt
.venv/bin/python tools/rawlite_to_dng.py /path/to/RawLite_YYYYMMDD_HHMMSS
```

The converter exports a `dng/` folder suitable for CinemaDNG import and includes
synchronized audio when available.

## Current Status

RawLite is an alpha-stage technical prototype. It is currently optimized around
the Pixel 6 hardware profile and should not be treated as a production Android
camera replacement.

Known constraints:

- RAW capture and playback are storage- and I/O-intensive.
- Sustained performance depends on frame rate, resolution, thermal state and
  available storage.
- Device capability handling is under active development.
- The Pixel 6 profile is the reference implementation for current validation.

## Build and Validation

Use JDK 21 for Android builds. Android Lint is expected to run under JDK 21.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :app:assembleDebug
```

Recommended local validation:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:testDebugUnitTest :app:lintDebug
```

Development installs should preserve existing takes:

```bash
tools/install_debug_safely.sh app/build/outputs/apk/debug/app-debug.apk
```

The install script uses `adb install -r` and avoids uninstalling the package,
because uninstalling an Android app removes its private app storage.

## Credits

Product architecture, interaction model and visual direction:

```text
Prem Avalos
```

Engineering implementation:

```text
AI-assisted development with Codex
```
