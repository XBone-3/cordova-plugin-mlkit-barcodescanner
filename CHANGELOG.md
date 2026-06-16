# Changelog

All notable changes to this project are documented in this file. The format is
based on [Keep a Changelog](https://keepachangelog.com/), and this project
adheres to [Semantic Versioning](https://semver.org/).

## [5.0.0] - 2026-06-17

### Added

- `autoZoom` option (Android) — gradually zooms the camera in when nothing
  decodes for a moment, so small or distant barcodes read without moving the
  device closer. Stops on a successful read or when the user adjusts zoom.
- `showZoomSlider` option (Android) — a zoom slider in the camera UI for manual
  zoom (pinch-to-zoom still works too).
- `galleryButton` option (Android) — pick an image from the gallery and scan it.
  The picked image is shown full-screen with corner brackets over each detected
  code and a Confirm/Retry prompt (Confirm returns the result, Retry goes back to
  the camera). On a whole-image miss the plugin retries on overlapping
  full-resolution tiles to catch small or distant codes (e.g. a QR shot from far
  away).
- Continuous mode now shows a **Done** button to end the session explicitly.
  Tapping it closes the scanner with
  `{ cancelled: true, message: 'Scan completed.' }` — a graceful close (not an
  error), with a distinct message so it can be told apart from a back-press
  cancel.
- Cleaner camera UI: a top instruction banner and a bottom control bar holding
  the gallery, Done and torch controls; the live chrome is hidden while a frozen
  frame or a picked gallery image is on screen.

### Changed

- **Breaking:** `drawDetectionBorder` now defaults to `true` and draws **corner
  brackets** that trace each detected barcode's corners, instead of a full
  closed outline. The viewfinder focus box is likewise drawn as corner brackets
  for a cleaner look.
- The ML Kit client is now built once and reused for both live frames and
  gallery images.
- The confirmation prompt is restyled to match the new UI (rounded card,
  consistent buttons).

### Notes / Limitations

- ML Kit only reports barcodes it has fully decoded; it cannot report a barcode
  that is present but unreadable. `autoZoom` is therefore a heuristic (zoom in
  until something reads) rather than a literal detect-then-zoom. See the README
  "Reliability notes & limitations" section.
- The new reliability/UI features are **Android only**; iOS keeps its existing
  behaviour.
- Follow-up not included here: feeding ML Kit the camera's YUV frame via
  `InputImage.fromMediaImage(...)` instead of converting each frame to a bitmap,
  to further reduce CPU and improve distance reads.

## [4.0.0] - 2026-06-13

### Added

- `continuous` option — keep the camera open and stream every newly detected
  barcode (deduplicated) until the user closes the scanner.
- `multiple` option — detect every barcode in a frame; the success callback then
  receives an array of results.
- `drawDetectionBorder` option (Android) — draw a border around each detected
  barcode in the preview, tracing its corner points (follows rotated and 2D
  codes).
- `confirmation` option (Android) — freeze the preview on detection with the
  decoded value and Confirm/Retry buttons; the result is returned only on
  Confirm.
- TypeScript `types` entry so consumers resolve the bundled `.d.ts` declarations
  on `import`.

### Changed

- **Breaking:** `detectorSize` is now the fraction of the **screen** that is
  scanned — a centred rectangle covering that percentage of both the width and
  the height, where `1` scans the whole screen and draws no focus box.
  Previously it was a fixed centred square of the smaller dimension that could
  never cover the full screen.

### Fixed

- Android: corrected the Cordova service name, which caused every scan to fail
  immediately with a single-character `"C"` message (Cordova's
  `"Class not found"` error).
- Android: scans after the first were cancelled before the camera opened, caused
  by a duplicate `setActivityResultCallback` that aborted the in-flight scan.
  Each scan now opens and reads correctly.
- Android: back-press / permission-denied is now reported as a cancellation
  (`{ cancelled: true }`) instead of being silently swallowed, and no longer
  risks a crash on a null result intent.
- Normalised the error callback so Cordova framework errors surface their full
  message rather than just the first character.

### Chore

- Made the `clean` build script cross-platform (`rimraf`) so the build runs on
  Windows as well as POSIX shells.

[5.0.0]: https://github.com/XBone-3/cordova-plugin-mlkit-barcodescanner/releases/tag/v5.0.0
[4.0.0]: https://github.com/XBone-3/cordova-plugin-mlkit-barcodescanner/releases/tag/v4.0.0
