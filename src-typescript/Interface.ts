export interface IBarcodeFormats {
  Aztec: boolean;
  CodaBar: boolean;
  Code39: boolean;
  Code93: boolean;
  Code128: boolean;
  DataMatrix: boolean;
  EAN8: boolean;
  EAN13: boolean;
  ITF: boolean;
  PDF417: boolean;
  QRCode: boolean;
  UPCA: boolean;
  UPCE: boolean;
}

export interface IOptions {
  barcodeFormats?: IBarcodeFormats;
  beepOnSuccess?: boolean;
  vibrateOnSuccess?: boolean;
  detectorSize?: number;
  rotateCamera?: boolean;
  /**
   * Keep the camera open and stream every newly detected barcode back to the
   * success callback (which may be invoked many times). The scanner closes
   * when the user presses back, surfacing a cancelled error. Default: false.
   */
  continuous?: boolean;
  /**
   * Detect every barcode visible in a frame instead of just the first one.
   * When enabled the success callback receives an array of results. Default:
   * false.
   */
  multiple?: boolean;
  /**
   * Draw corner brackets around each barcode as it is detected in the camera
   * preview, following the code's corner points. Default: true. (Android only.)
   */
  drawDetectionBorder?: boolean;
  /**
   * Require the user to confirm a detected barcode before it is returned. The
   * preview freezes on the detected frame with a Confirm/Retry prompt. Ignored
   * in continuous mode. Default: false.
   */
  confirmation?: boolean;
  /**
   * Gradually zoom the camera in when nothing decodes for a moment, to read
   * small or distant barcodes without the user having to move closer. Resets
   * once a code is read or the user adjusts the zoom slider. Default: true.
   * (Android only.)
   */
  autoZoom?: boolean;
  /**
   * Show a zoom slider in the camera UI so the user can zoom in/out manually.
   * Default: true. (Android only.)
   */
  showZoomSlider?: boolean;
  /**
   * Show a button in the camera UI to pick an image from the gallery and scan
   * it instead of using the live camera. Default: true. (Android only.)
   */
  galleryButton?: boolean;
}

export interface IConfig {
  barcodeFormats: number;
  beepOnSuccess: boolean;
  vibrateOnSuccess: boolean;
  detectorSize: number;
  rotateCamera: boolean;
  continuous: boolean;
  multiple: boolean;
  drawDetectionBorder: boolean;
  confirmation: boolean;
  autoZoom: boolean;
  showZoomSlider: boolean;
  galleryButton: boolean;
}

export interface IResult {
  text: string;
  format: string;
  type: string;
}

export interface IError {
  cancelled: boolean;
  message: string;
}
