# RawLite UI Language

RawLite is a one-hand cinema camera interface. The image remains primary; controls must be
readable by position, color and touch without forcing the operator to inspect a settings panel.

## Reference Grid

- Canonical portrait canvas: `1179 x 2556`.
- Canonical landscape canvas: `2556 x 1179`.
- Geometry is calculated in canonical coordinates and scaled uniformly at runtime.
- Android keeps a portrait control canvas so launching the app while the phone is already sideways
  cannot move the bezel controls. Content rotates independently from control geometry. The UI
  supports portrait, landscape-left and landscape-right, never reverse portrait.
- Primary D-pad: `310 x 310` canonical pixels, aligned to the record control.

## Visual Tokens

- Typeface: Open Sans throughout.
- Background: `#000000`.
- Primary text and inactive controls: `#F7F7F7`.
- Active, selected and confirmed state: `#FFC100`.
- Recording state and destructive actions: `#FF2B2B`.
- Disabled content: white at 45-62% opacity.
- Technical surfaces use neutral gray or translucent black. They must reveal the preview when HUD
  backgrounds are hidden.
- Corner radii stay restrained: `4-6 dp` for pills and panels; circles remain circular.
- One-pixel dividers establish the selector grid. They are structural, not decorative.

## State Language

- White means available or inactive.
- Yellow means selected, enabled, captured or locked.
- Red is reserved for active recording and destructive actions.
- Dimmed means unavailable in the current camera/session state.
- `AUTO`, `RAW` and lock controls are buttons: yellow is on, white is off.
- The active parameter is yellow in its selector, on the ruler and briefly in the preview HUD.
- A parameter change shows only its name in the preview HUD. A value change uses two levels:
  the parameter name in a smaller white label and its current value at the established large size
  in yellow.

## Interaction

- The D-pad is the primary one-hand control.
- Its parameter axis stops at the first and last selectable parameter.
- Its value axis supports taps, holds and velocity-limited swipes.
- Axis lock is acquired after the initial directional gesture and remains until release.
- Haptic ticks confirm every parameter step and every value detent.
- Left-handed mode mirrors the complete control geometry: selector strip, ruler, bezel tools,
  D-pad, Push Auto, gallery and the horizontal recording timer. Text and icons are
  counter-reflected so they always remain readable, and control semantics follow the mirrored
  ruler and selector order.
- Push Auto temporarily meters exposure, white balance and focus, then commits the measured values
  to the manual wheels.
- Shutter uses discrete video stops rather than an arbitrary continuous scale. Its slowest value is
  limited by frame cadence and its fastest exposed value is `1/8000` when the sensor supports it.
- During recording, only unlocked parameter groups may change. Lens, resolution and frame rate are
  session controls and remain fixed.

## Motion

- Every visual transition uses cubic Bezier easing equivalent to `(0.4, 0, 0.2, 1)`.
- State and label transitions: `120-180 ms`.
- Layout/HUD transitions: `220-300 ms`.
- The recording halo breathes over `720 ms` per half-cycle while the shutter remains white.
- In portrait recording, `TOMAS` fades out; when the HUD is hidden it travels into the exact
  position formerly occupied by `AUTO` and returns along the same Bezier curve.
- Orientation rotates content and modal panels together; control positions follow their
  orientation-specific grid.

## Capture Hierarchy

1. Preview and framing.
2. Record button and recording state.
3. D-pad and active parameter ruler.
4. Parameter selectors and AUTO.
5. Push Auto, gallery, lock, rotation and settings.

## Review And Playback

- Portrait gallery uses one full-width contact-sheet card per take: a `188 dp` image stage,
  three short metadata lines and a `56 dp` playback target. Landscape retains two columns with
  fixed `112 x 78 dp` thumbnails. Long metadata is abbreviated or ellipsized.
- PURE 10 uses Android's hardware HEVC Main10 decoder and a separate synchronized WAV player.
- RAW10 and CinemaDNG use a clocked sequence player. It preserves timeline gaps, skips stale
  frames if decoding falls behind and shows both stored-frame and source-timeline counters.
- The transport uses the same black, white, yellow and red state language as the camera. Playback,
  seeking, frame stepping, audio mute and destructive deletion remain in fixed positions.
- RAW thumbnails and review frames are generated only inside the gallery/player. No review proxy
  is added to the recording session, keeping capture heat and bandwidth unchanged.
