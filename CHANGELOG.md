# Changelog

All notable changes to this project are documented in this file. The format is
based on [Keep a Changelog](https://keepachangelog.com/), and this project
adheres to [Semantic Versioning](https://semver.org/).

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

[4.0.0]: https://github.com/XBone-3/cordova-plugin-mlkit-barcodescanner/releases/tag/v4.0.0
