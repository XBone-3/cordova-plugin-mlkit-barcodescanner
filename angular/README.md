# cordova-plugin-mlkit-barcodescanner-angular

Angular wrapper for
[`cordova-plugin-mlkit-barcodescanner`](https://github.com/XBone-3/cordova-plugin-mlkit-barcodescanner),
exposing an injectable service with a `Promise` API for one-shot scans and an
`Observable` stream for continuous scanning.

The core Cordova plugin is framework-agnostic; this package adds the Angular
ergonomics on top. They are released as two npm packages from one monorepo.

## Install

```sh
# the native plugin (adds it to your Cordova/Ionic app)
cordova plugin add cordova-plugin-mlkit-barcodescanner
# or: ionic cordova plugin add cordova-plugin-mlkit-barcodescanner

# the Angular wrapper
npm install cordova-plugin-mlkit-barcodescanner-angular
```

`@angular/core`, `rxjs` and the core plugin are peer dependencies.

## Usage

The service is `providedIn: 'root'`, so just inject it — no NgModule needed.

```ts
import { Component } from '@angular/core';
import { MlkitBarcodeScanner, IResult } from 'cordova-plugin-mlkit-barcodescanner-angular';

@Component({ /* ... */ })
export class ScanPage {
  constructor(private scanner: MlkitBarcodeScanner) {}

  // One-shot scan (Promise)
  async scanOnce() {
    try {
      const result = (await this.scanner.scan({ beepOnSuccess: true })) as IResult;
      console.log('scanned', result.text, result.format, result.type);
    } catch (err) {
      console.log('cancelled or failed', err);
    }
  }

  // Detect every barcode in the frame at once
  async scanFrame() {
    const results = (await this.scanner.scan({ multiple: true })) as IResult[];
    console.log(`found ${results.length} codes`);
  }

  // Continuous scanning (Observable stream)
  scanContinuously() {
    const sub = this.scanner
      .scanStream({ drawDetectionBorder: true })
      .subscribe({
        next: (r) => console.log('barcode', r),
        complete: () => console.log('scanner closed'),
        error: (e) => console.error(e),
      });
    // The native scanner is closed by the user (back button); the stream then
    // completes. Unsubscribing does not close the camera.
  }
}
```

> Call the scanner after Cordova's `deviceready` (e.g. Ionic's
> `Platform.ready()`). Until then the underlying plugin is not yet on
> `window.cordova` and the service throws a descriptive error.

See the [core plugin README](https://github.com/XBone-3/cordova-plugin-mlkit-barcodescanner#readme)
for the full list of options (`barcodeFormats`, `detectorSize`, `continuous`,
`multiple`, `drawDetectionBorder`, `confirmation`, `beepOnSuccess`, …).

## API

| Member | Returns | Notes |
| --- | --- | --- |
| `scan(options?)` | `Promise<IResult \| IResult[]>` | One-shot. `continuous` is forced off. Array when `multiple: true`. |
| `scanStream(options?)` | `Observable<IResult \| IResult[]>` | Forces `continuous: true`. Emits per detection; completes on close. |

## Build & publish (maintainers)

This package is built with [ng-packagr](https://github.com/ng-packagr/ng-packagr)
into Angular Package Format. From the repo root:

```sh
npm install            # installs root + workspace deps
npm run build:angular  # or: npm run build -w angular
```

The publishable package is emitted to `angular/dist/`. Publish from there:

```sh
cd angular/dist && npm publish
```
