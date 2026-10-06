# RawLite

RawLite is an Android camera prototype focused on manual RAW video capture,
minimal interaction design and exportable post-production workflows. The current
hardware reference target is the Google Pixel 6.

The application supports three capture workflows:

- CinemaDNG FULL: full-resolution DNG image sequences.
- RAW10 2K: losslessly compressed Bayer RAW10 clips with frame-level metadata.
- HLG 10: Rec.2020/Rec.2100 HLG video encoded as HEVC Main10 in hardware, with
  AAC audio in the final MP4. Older internal metadata and directory names retain
  the `PURE10` identifier for compatibility.

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
HLG 10, RawLite records 10-bit HEVC Main10 HLG through hardware encoding and
muxes audio into the final MP4. Synchronization metadata is retained for
inspection and recovery workflows.

When the camera exposes lens-shading metadata, RawLite preserves it per frame.
CinemaDNG exports embed the standard DNG gain-map correction without modifying
the original Bayer samples, while HLG 10 applies the corresponding optical
falloff compensation before the Rec.2020/Rec.2100 HLG transform.

On the Pixel 6 reference profile, HLG 10 exposes exact output dimensions rather
than an ambiguous resolution class:

- 1920×1080, 16:9, 15/24/25/30 FPS: measured profile.
- 2032×1536, 4:3, 15/24/25/30 FPS: validated profile.
- 3840×2160, 16:9, 15/24 FPS: experimental profile; sustained cadence is not
  guaranteed.

The encoded output profile is independent from the RAW10 stream selected from
the HAL as its source. Unsupported combinations are omitted for the active
camera.

Each recording is stored in its own take directory:

```text
Android/data/com.example.rawlite/files/Movies/{RAW10|CINEMADNG|PURE10}_YYYYMMDD_HHMMSS/
```

Typical take contents include:

- `clip.json`: capture format, sensor profile, color metadata and session
  configuration.
- `frames.csv`: per-frame timing and camera settings.
- `telemetry.csv`: storage, thermal and runtime telemetry.
- `sync.json`: audio/video synchronization metadata.
- `audio.wav`: PCM audio intermediate or backup when applicable.
- `clip.raw10c`, `frame_XXXXXX.dng`, `pure10.mp4` or `pure10_final.mp4`,
  depending on the selected capture mode.

## Gallery and Playback

RawLite includes an internal gallery and review player for captured takes.

Supported review features include:

- Hardware HEVC Main10 playback for HLG 10.
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

RAW10 is stored in RawLite's versioned `clip.raw10c` container. Each frame is
compressed independently and losslessly, includes integrity checks and remains
recoverable up to the last complete frame after an interrupted recording. The
application also continues to read legacy uncompressed `clip.raw10` takes.

RAW10 can be converted to CinemaDNG from the internal gallery with the
`CONVERTIR` action. Conversion reads the compressed frames directly and creates
a separate CinemaDNG take while preserving the original recording; it does not
first duplicate the complete uncompressed RAW stream.

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

### External microphones

RawLite detects Android audio input connections while the camera is open and
prefers USB receivers (including DJI Mic Mini with its USB-C phone adapter),
then wired microphone inputs, then the built-in microphone. Output-only USB
devices are ignored. Connection changes also update the preferred input of an
ongoing recording without restarting its WAV timeline.

The camera shows `MIC EXT` or `MIC INT`; before recording this indicates the
automatic selection, and during recording it reflects Android's reported
active route. `MIC ?` means the route is not yet confirmed, `MIC INT !` means
the external device is connected but is not the active input, and `MIC ERROR`
means audio capture was interrupted. Settings shows the input's name. Muting
still disables audio regardless of connected devices.

`audio.json` retains the requested input, whether Android accepted the request,
and observed route changes with boot-clock timestamps. These timestamps mark
route observations, not sample-exact transition boundaries. The existing
48 kHz WAV format and HLG audio mux remain unchanged; the PCM bit depth is the
app's recording format, not a claim about the microphone's native resolution.

Direct Bluetooth microphone routing is not implemented. USB compatibility and
hot-plug continuity require testing with the actual phone/receiver: Android
may briefly interrupt audio or invalidate AudioRecord when a device is removed.
An invalidated recorder reports an audio error instead of silently claiming
that capture continued.

Device acceptance checks:

- Connect the receiver before opening RawLite and again with the preview open;
  verify `MIC EXT` and its name in Settings.
- Record speech near the transmitter with the phone far away; review both the
  WAV/HLG audio and `audio.json` to confirm the external route and audible input.
- Connect and disconnect during a take; verify the active route indicator,
  fallback input, recorded sound, and A/V sync around the transition.
- Verify mute, a second take, background/foreground, wired headsets, and an
  output-only USB device. Confirm normal recording with no accessory attached.

### Prototype limitations

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
