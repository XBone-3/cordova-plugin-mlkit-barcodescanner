/*
 * Public API surface of cordova-plugin-mlkit-barcodescanner-angular.
 */
export * from './lib/mlkit-barcode-scanner.service';

// Re-export the core option/result types for convenience, so consumers only
// need to import from this package.
export type {
  IBarcodeFormats,
  IError,
  IOptions,
  IResult,
} from 'cordova-plugin-mlkit-barcodescanner';
