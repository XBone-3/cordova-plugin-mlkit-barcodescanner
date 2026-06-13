import { barcodeFormat, barcodeType } from './Detector';
import {
  IBarcodeFormats,
  IConfig,
  IError,
  IOptions,
  IResult,
} from './Interface';
import { defaultOptions } from './Options';
import { keyByValue } from './util/Object';

export class MLKitBarcodeScanner {
  private getBarcodeFormat(format: number): string {
    return keyByValue(barcodeFormat, format);
  }

  private getBarcodeType(type: number): string {
    return keyByValue(barcodeType, type);
  }

  private getBarcodeFormatFlags(barcodeFormats?: IBarcodeFormats): number {
    let barcodeFormatFlag = 0;
    let key: keyof typeof barcodeFormat;
    const formats = barcodeFormats || defaultOptions.barcodeFormats;

    // eslint-disable-next-line no-restricted-syntax
    for (key in formats) {
      if (
        barcodeFormat.hasOwnProperty(key) &&
        formats.hasOwnProperty(key) &&
        formats[key]
      ) {
        barcodeFormatFlag += barcodeFormat[key];
      }
    }
    return barcodeFormatFlag;
  }

  scan(
    userOptions: IOptions,
    success: (result: IResult | IResult[]) => unknown,
    failure: (error: IError) => unknown,
  ): void {
    const barcodeFormats =
      userOptions?.barcodeFormats || defaultOptions.barcodeFormats;
    const config: IConfig = {
      ...defaultOptions,
      ...userOptions,
      barcodeFormats: this.getBarcodeFormatFlags(barcodeFormats),
    };

    this.sendScanRequest(config, success, failure);
  }

  private sendScanRequest(
    config: IConfig,
    successCallback: (result: IResult | IResult[]) => unknown,
    failureCallback: (error: IError) => unknown,
  ): void {
    type Triple = [string, number, number];
    cordova.exec(
      (data: Triple | Triple[]) => {
        // A single scan can arrive as a flat triple [text, format, type]
        // (iOS / legacy), while multi and continuous scans send an array of
        // such triples. Normalise to an array either way.
        const triples: Triple[] = Array.isArray(data[0])
          ? (data as Triple[])
          : [data as Triple];

        const results: IResult[] = triples.map(([text, format, type]) => ({
          text,
          format: this.getBarcodeFormat(format),
          type: this.getBarcodeType(type),
        }));

        successCallback(config.multiple ? results : results[0]);
      },
      (err: string | (string | null)[] | null) => {
        // Plugin errors arrive as an array ([code, ...]), but Cordova
        // framework errors (e.g. "Class not found") arrive as a plain
        // string. Normalise to the error code/message either way.
        const code = Array.isArray(err) ? err[0] : err;

        switch (code) {
          case null:
          case undefined:
          case 'USER_CANCELLED':
            failureCallback({
              cancelled: true,
              message: 'The scan was cancelled.',
            });
            break;
          case 'SCANNER_OPEN':
            failureCallback({
              cancelled: false,
              message: 'Scanner already open.',
            });
            break;
          default:
            failureCallback({
              cancelled: false,
              message: code || 'Unknown Error',
            });
            break;
        }
      },
      'cordova-plugin-mlkit-barcodescanner',
      'startScan',
      [config],
    );
  }
}

const barcodeScanner = new MLKitBarcodeScanner();
module.exports = barcodeScanner;
