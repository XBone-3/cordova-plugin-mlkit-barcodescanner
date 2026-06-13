import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
// Types only — the runtime implementation is the Cordova plugin, clobbered onto
// `cordova.plugins.mlkit.barcodeScanner` on `deviceready`.
import type {
  IError,
  IOptions,
  IResult,
  MLKitBarcodeScanner,
} from 'cordova-plugin-mlkit-barcodescanner';

/**
 * Injectable Angular wrapper around the global Cordova barcode scanner plugin,
 * exposing a `Promise` for one-shot scans and an `Observable` stream for
 * continuous scanning.
 *
 * Provided in root, so it can be injected anywhere without an NgModule:
 *
 * ```ts
 * constructor(private scanner: MlkitBarcodeScanner) {}
 *
 * async read() {
 *   const result = await this.scanner.scan({ beepOnSuccess: true });
 *   console.log(result.text);
 * }
 * ```
 */
@Injectable({ providedIn: 'root' })
export class MlkitBarcodeScanner {
  /**
   * Opens the scanner, returns the first detected barcode and closes the
   * camera. With `multiple: true` the promise resolves with an array of every
   * barcode in the frame. Rejects with an {@link IError} (e.g. when the user
   * cancels). The `continuous` option is ignored here — use {@link scanStream}.
   */
  scan(options: IOptions = {}): Promise<IResult | IResult[]> {
    return new Promise<IResult | IResult[]>((resolve, reject) => {
      this.plugin().scan({ ...options, continuous: false }, resolve, reject);
    });
  }

  /**
   * Opens the scanner in continuous mode and emits every newly detected barcode
   * (or an array per frame with `multiple: true`). The stream completes when the
   * user closes the scanner (back button) and errors on any other failure.
   *
   * Note: unsubscribing does not close the native camera — Cordova has no way to
   * cancel an in-progress scan from JS; the user closes it with the back button.
   */
  scanStream(options: IOptions = {}): Observable<IResult | IResult[]> {
    return new Observable<IResult | IResult[]>((subscriber) => {
      this.plugin().scan(
        { ...options, continuous: true },
        (result: IResult | IResult[]) => subscriber.next(result),
        (error: IError) => {
          // A cancellation simply means the scanner was closed: complete the
          // stream rather than surfacing it as an error.
          if (error && error.cancelled) {
            subscriber.complete();
          } else {
            subscriber.error(error);
          }
        },
      );
    });
  }

  /** Resolves the clobbered plugin instance, or throws a helpful error. */
  private plugin(): MLKitBarcodeScanner {
    const scanner = (window as unknown as { cordova?: { plugins?: { mlkit?: { barcodeScanner?: MLKitBarcodeScanner } } } })
      ?.cordova?.plugins?.mlkit?.barcodeScanner;

    if (!scanner) {
      throw new Error(
        '[MlkitBarcodeScanner] cordova-plugin-mlkit-barcodescanner is not ' +
          'available. Make sure the plugin is installed and that you call ' +
          'the scanner after the Cordova `deviceready` event (e.g. via ' +
          "Ionic's Platform.ready()).",
      );
    }
    return scanner;
  }
}
