package com.mobisys.cordova.plugins.mlkit.barcode.scanner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;

import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.Barcode;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.common.InputImage;
import com.mobisys.cordova.plugins.mlkit.barcode.scanner.utils.BitmapUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CaptureActivity extends AppCompatActivity implements SurfaceHolder.Callback {

  public Integer BarcodeFormats;
  public double DetectorSize = .5;

  // JSON string extra holding an array of [text, format, type] triples.
  public static final String BarcodePayload = "MLKitBarcodes";

  private boolean continuous = false;
  private boolean multiple = false;
  private boolean drawDetectionBorder = false;
  private boolean confirmation = false;
  private boolean rotateCamera = false;

  // Barcodes currently drawn on the overlay, so a surface change can redraw them.
  private volatile List<Barcode> overlayBarcodes;

  // Raw values already streamed back in continuous mode, so each barcode is
  // reported only once per session.
  private final Set<String> emittedValues = new HashSet<>();

  // Overlay/view dimensions, captured from the surface so the analyzer thread
  // can compute the image<->view transform.
  private volatile int viewWidth = 0;
  private volatile int viewHeight = 0;

  // Mapping from the cropped frame handed to ML Kit back to view coordinates:
  // viewPoint = (cropOrigin + barcodePoint) * fillScale + fillOffset. Matches
  // PreviewView's default FILL_CENTER scaling.
  private volatile int cropLeft = 0;
  private volatile int cropTop = 0;
  private volatile float fillScale = 1;
  private volatile float fillOffsetX = 0;
  private volatile float fillOffsetY = 0;
  private volatile boolean haveMapping = false;

  // Serialises overlay canvas access between the analyzer thread and the UI
  // thread (surface callbacks, confirm/retry).
  private final Object overlayLock = new Object();

  // While a detected barcode is awaiting Confirm/Retry the analyzer stops
  // processing frames and the preview is frozen.
  private volatile boolean awaitingConfirmation = false;
  private JSONArray pendingPayload;

  private ImageView freezeFrame;
  private View confirmPanel;
  private TextView confirmText;
  private Button confirmButton;
  private Button retryButton;

  private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
  private ExecutorService executor = Executors.newSingleThreadExecutor();
  private PreviewView mCameraView;
  private SurfaceHolder holder;
  private SurfaceView surfaceView;

  private static final int RC_HANDLE_CAMERA_PERM = 2;
  private ImageButton _TorchButton;
  private Camera camera;

  private ScaleGestureDetector _ScaleGestureDetector;
  private GestureDetector _GestureDetector;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(getResources().getIdentifier("capture_activity", "layout", getPackageName()));

    // Create the bounding box
    surfaceView = findViewById(getResources().getIdentifier("overlay", "id", getPackageName()));
    surfaceView.setZOrderOnTop(true);

    holder = surfaceView.getHolder();
    holder.setFormat(PixelFormat.TRANSPARENT);
    holder.addCallback(this);

    // read parameters from the intent used to launch the activity.
    BarcodeFormats = getIntent().getIntExtra("BarcodeFormats", 1234);
    DetectorSize = getIntent().getDoubleExtra("DetectorSize", .5);
    continuous = getIntent().getBooleanExtra("Continuous", false);
    multiple = getIntent().getBooleanExtra("Multiple", false);
    drawDetectionBorder = getIntent().getBooleanExtra("DrawDetectionBorder", false);
    rotateCamera = getIntent().getBooleanExtra("RotateCamera", false);
    // Confirmation is meaningless while continuously streaming results.
    confirmation = getIntent().getBooleanExtra("Confirmation", false) && !continuous;

    // DetectorSize is the fraction of the screen that is scanned (0..1]; 1
    // means the whole screen. Out-of-range values fall back to the default.
    if (DetectorSize <= 0 || DetectorSize > 1) {
      DetectorSize = 0.6;
    }

    setupConfirmationUi();

    int rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);

    if (rc == PackageManager.PERMISSION_GRANTED) {
      // Start Camera
      startCamera();
    } else {
      requestCameraPermission();
    }

    _GestureDetector = new GestureDetector(this, new CaptureGestureListener());
    _ScaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());

    _TorchButton = findViewById(getResources().getIdentifier("torch_button", "id", this.getPackageName()));

    _TorchButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {

        LiveData<Integer> flashState = camera.getCameraInfo().getTorchState();
        if (flashState.getValue() != null) {
          boolean state = flashState.getValue() == 1;
          _TorchButton.setBackgroundResource(getResources().getIdentifier(!state ? "torch_active" : "torch_inactive",
              "drawable", CaptureActivity.this.getPackageName()));
          camera.getCameraControl().enableTorch(!state);
        }

      }
    });

  }

  // ----------------------------------------------------------------------------
  // | Helper classes
  // ----------------------------------------------------------------------------
  private class CaptureGestureListener extends GestureDetector.SimpleOnGestureListener {
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
      return super.onSingleTapConfirmed(e);
    }
  }

  private class ScaleListener implements ScaleGestureDetector.OnScaleGestureListener {
    @Override
    public boolean onScale(ScaleGestureDetector detector) {
      return false;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
      return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {

      if (camera != null) {
        float scale = camera.getCameraInfo().getZoomState().getValue().getZoomRatio() * detector.getScaleFactor();
        camera.getCameraControl().setZoomRatio(scale);
      }
    }
  }

  private void requestCameraPermission() {

    final String[] permissions = new String[] { Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE };

    boolean shouldShowPermission = !ActivityCompat.shouldShowRequestPermissionRationale(this,
        Manifest.permission.CAMERA);
    shouldShowPermission = shouldShowPermission
        && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);

    if (shouldShowPermission) {
      ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM);
      return;
    }

    View.OnClickListener listener = new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        ActivityCompat.requestPermissions(CaptureActivity.this, permissions, RC_HANDLE_CAMERA_PERM);
      }
    };

    findViewById(getResources().getIdentifier("topLayout", "id", getPackageName())).setOnClickListener(listener);
    Snackbar
        .make(surfaceView, getResources().getIdentifier("permission_camera_rationale", "string", getPackageName()),
            Snackbar.LENGTH_INDEFINITE)
        .setAction(getResources().getIdentifier("ok", "string", getPackageName()), listener).show();

  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    if (requestCode != RC_HANDLE_CAMERA_PERM) {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults);
      return;
    }

    if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      startCamera();
      redrawOverlay(overlayBarcodes);
      return;
    }

    DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int id) {
        finish();
      }
    };

    AlertDialog.Builder builder = new AlertDialog.Builder(this);
    builder.setTitle("Camera permission required")
        .setMessage(getResources().getIdentifier("no_camera_permission", "string", getPackageName()))
        .setPositiveButton(getResources().getIdentifier("ok", "string", getPackageName()), listener).show();
  }

  @Override
  public void surfaceCreated(SurfaceHolder surfaceHolder) {

  }

  @Override
  public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int width, int height) {
    viewWidth = width;
    viewHeight = height;
    redrawOverlay(overlayBarcodes);
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder surfaceHolder) {

  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    boolean b = _ScaleGestureDetector.onTouchEvent(e);
    boolean c = _GestureDetector.onTouchEvent(e);

    return b || c || super.onTouchEvent(e);
  }

  @Override
  protected void onPause() {
    super.onPause();

  }

  @Override
  protected void onResume() {
    super.onResume();

  }

  void startCamera() {
    mCameraView = findViewById(getResources().getIdentifier("previewView", "id", getPackageName()));
    mCameraView.setPreferredImplementationMode(PreviewView.ImplementationMode.TEXTURE_VIEW);

    if (rotateCamera) {
      mCameraView.setScaleX(-1F);
      mCameraView.setScaleY(-1F);
    } else {
      mCameraView.setScaleX(1F);
      mCameraView.setScaleY(1F);
    }

    // mCameraView.setScaleType(PreviewView.ScaleType.FIT_CENTER);

    cameraProviderFuture = ProcessCameraProvider.getInstance(this);
    cameraProviderFuture.addListener(new Runnable() {
      @Override
      public void run() {
        try {
          ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
          CaptureActivity.this.bindPreview(cameraProvider);

        } catch (ExecutionException | InterruptedException e) {
          // No errors need to be handled for this Future.
          // This should never be reached.
        }
      }
    }, ContextCompat.getMainExecutor(this));
  }

  /**
   * Binding to camera
   */
  private void bindPreview(ProcessCameraProvider cameraProvider) {

    int barcodeFormat;
    if (BarcodeFormats == 0 || BarcodeFormats == 1234) {
      barcodeFormat = (Barcode.FORMAT_CODE_39 | Barcode.FORMAT_DATA_MATRIX);
    } else {
      barcodeFormat = BarcodeFormats;
    }

    Preview preview = new Preview.Builder().build();

    CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .build();

    preview.setSurfaceProvider(mCameraView.createSurfaceProvider());

    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetAspectRatio(AspectRatio.RATIO_16_9)
        .build();

    BarcodeScanner scanner = BarcodeScanning
        .getClient(new BarcodeScannerOptions.Builder().setBarcodeFormats(barcodeFormat).build());

    imageAnalysis.setAnalyzer(executor, new ImageAnalysis.Analyzer() {
      @SuppressLint("UnsafeExperimentalUsageError")
      @Override
      public void analyze(@NonNull ImageProxy image) {

        if (image == null || image.getImage() == null) {
          return;
        }

        // While the user is confirming a detection the preview is frozen, so
        // there is nothing to analyze.
        if (awaitingConfirmation) {
          image.close();
          return;
        }

        Bitmap bmp = BitmapUtils.getBitmap(image);

        int width = bmp.getWidth();
        int height = bmp.getHeight();

        // The scan area is DetectorSize (0..1) of the screen, centred. Map that
        // view rectangle back into image pixels so ML Kit only sees what the
        // user sees inside the focus box (the whole screen at DetectorSize 1).
        int vw = viewWidth > 0 ? viewWidth : width;
        int vh = viewHeight > 0 ? viewHeight : height;

        // PreviewView FILL_CENTER: scale the image up to cover the view.
        float scale = Math.max((float) vw / width, (float) vh / height);
        float offX = (vw - width * scale) / 2f;
        float offY = (vh - height * scale) / 2f;

        double ds = DetectorSize;
        float fLeftV = (float) (vw * (1 - ds) / 2);
        float fTopV = (float) (vh * (1 - ds) / 2);
        float fRightV = (float) (vw * (1 + ds) / 2);
        float fBotV = (float) (vh * (1 + ds) / 2);

        int left = clampInt((fLeftV - offX) / scale, 0, width);
        int top = clampInt((fTopV - offY) / scale, 0, height);
        int right = clampInt((fRightV - offX) / scale, 0, width);
        int bottom = clampInt((fBotV - offY) / scale, 0, height);

        int boxWidth = right - left;
        int boxHeight = bottom - top;
        if (boxWidth <= 0 || boxHeight <= 0) {
          left = 0;
          top = 0;
          boxWidth = width;
          boxHeight = height;
        }

        Bitmap bitmap = Bitmap.createBitmap(bmp, left, top, boxWidth, boxHeight);

        // BitmapUtils.getBitmap() has already rotated the frame upright, so the
        // crop is upright too; pass rotation 0 so ML Kit reports corner points
        // directly in crop-pixel space. Record the mapping for the overlay.
        cropLeft = left;
        cropTop = top;
        fillScale = scale;
        fillOffsetX = offX;
        fillOffsetY = offY;
        haveMapping = true;
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener(new OnSuccessListener<List<Barcode>>() {
              @Override
              public void onSuccess(List<Barcode> barCodes) {
                // Another frame may have entered confirmation while this one
                // was in flight; drop it.
                if (awaitingConfirmation) {
                  return;
                }

                // Keep the live detection border in sync with what is on screen.
                if (drawDetectionBorder) {
                  redrawOverlay(barCodes);
                }

                if (barCodes.size() == 0) {
                  return;
                }

                try {
                  handleBarcodes(barCodes);
                } catch (JSONException e) {
                  e.printStackTrace();
                }
              }
            }).addOnFailureListener(new OnFailureListener() {
              @Override
              public void onFailure(@NonNull Exception e) {

              }
            }).addOnCompleteListener(new OnCompleteListener<List<Barcode>>() {
              @Override
              public void onComplete(@NonNull Task<List<Barcode>> task) {
                image.close();
              }
            });
      }

    });

    // Release any use cases still bound from a previous scan before rebinding,
    // otherwise reopening the scanner can leave the preview/analyzer detached
    // (the camera appears frozen and every subsequent scan gets cancelled).
    cameraProvider.unbindAll();

    camera = cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, imageAnalysis, preview);
  }

  /**
   * Turns the detected barcodes into a result payload. In continuous mode the
   * camera stays open and only newly seen codes are streamed back; otherwise
   * the (first or all) codes are returned and the activity finishes.
   */
  private void handleBarcodes(List<Barcode> barCodes) throws JSONException {
    JSONArray payload = new JSONArray();

    for (Barcode barcode : barCodes) {
      String value = rawValueOf(barcode);
      if (value == null) {
        continue;
      }

      if (continuous && !emittedValues.add(value)) {
        // Already streamed this code in a previous frame.
        continue;
      }

      payload.put(toTriple(barcode, value));

      if (!multiple) {
        // Single-result mode: one code is enough.
        break;
      }
    }

    if (payload.length() == 0) {
      return;
    }

    if (continuous) {
      MLKitBarcodeScanner.sendContinuousResult(payload);
    } else if (confirmation) {
      enterConfirmation(payload, barCodes);
    } else {
      finishWithResult(payload);
    }
  }

  /** Returns the payload to the plugin and closes the scanner. */
  private void finishWithResult(JSONArray payload) {
    Intent data = new Intent();
    data.putExtra(BarcodePayload, payload.toString());
    setResult(CommonStatusCodes.SUCCESS, data);
    finish();
  }

  /**
   * Returns the barcode's raw value, falling back to ASCII decoding when it is
   * not UTF-8 encoded (the most common case for 1D barcodes).
   * e.g. https://www.barcodefaq.com/1d/code-128/
   */
  private String rawValueOf(Barcode barcode) {
    String value = barcode.getRawValue();
    if (value == null && barcode.getRawBytes() != null) {
      value = new String(barcode.getRawBytes(), StandardCharsets.US_ASCII);
    }
    return value;
  }

  /** Encodes a barcode as a [text, format, type] triple. */
  private JSONArray toTriple(Barcode barcode, String value) throws JSONException {
    JSONArray triple = new JSONArray();
    triple.put(value);
    triple.put(barcode.getFormat());
    triple.put(barcode.getValueType());
    return triple;
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (executor != null) {
      executor.shutdown();
    }
  }

  // --------------------------------------------------------------------------
  // | Overlay drawing
  // --------------------------------------------------------------------------

  /**
   * Redraws the overlay: the focus rectangle plus a corner-point border around
   * each supplied barcode. Pass {@code null} to draw only the focus rectangle.
   */
  private void redrawOverlay(List<Barcode> barcodes) {
    overlayBarcodes = barcodes;

    if (mCameraView == null || holder == null) {
      return;
    }

    synchronized (overlayLock) {
      Canvas c = holder.lockCanvas();
      if (c == null) {
        return;
      }
      try {
        c.drawColor(0, PorterDuff.Mode.CLEAR);

        int width = mCameraView.getWidth();
        int height = mCameraView.getHeight();

        // The focus box is DetectorSize of the screen in both dimensions,
        // centred. At full screen (DetectorSize >= 1) there is no box to draw.
        if (DetectorSize < 1) {
          float left = (float) (width * (1 - DetectorSize) / 2);
          float top = (float) (height * (1 - DetectorSize) / 2);
          float right = (float) (width * (1 + DetectorSize) / 2);
          float bottom = (float) (height * (1 + DetectorSize) / 2);

          Paint focusPaint = new Paint();
          focusPaint.setStyle(Paint.Style.STROKE);
          focusPaint.setColor(Color.WHITE);
          focusPaint.setStrokeWidth(5);
          if (DetectorSize <= 0.3) {
            c.drawRect(new RectF(left, top, right, bottom), focusPaint);
          } else {
            c.drawRoundRect(new RectF(left, top, right, bottom), 100, 100, focusPaint);
          }
        }

        if (barcodes != null && haveMapping) {
          Paint borderPaint = new Paint();
          borderPaint.setStyle(Paint.Style.STROKE);
          borderPaint.setColor(Color.parseColor("#00E676"));
          borderPaint.setStrokeWidth(6);
          borderPaint.setAntiAlias(true);
          for (Barcode barcode : barcodes) {
            Path path = barcodeBorderPath(barcode);
            if (path != null) {
              c.drawPath(path, borderPaint);
            }
          }
        }
      } finally {
        holder.unlockCanvasAndPost(c);
      }
    }
  }

  /**
   * Maps a barcode's corner points (relative to the crop handed to ML Kit) into
   * a closed Path in overlay/view space using the recorded FILL_CENTER mapping.
   */
  private Path barcodeBorderPath(Barcode barcode) {
    float[] xs = new float[4];
    float[] ys = new float[4];

    Point[] corners = barcode.getCornerPoints();
    if (corners != null && corners.length == 4) {
      for (int i = 0; i < 4; i++) {
        xs[i] = corners[i].x;
        ys[i] = corners[i].y;
      }
    } else {
      Rect box = barcode.getBoundingBox();
      if (box == null) {
        return null;
      }
      xs[0] = box.left;  ys[0] = box.top;
      xs[1] = box.right; ys[1] = box.top;
      xs[2] = box.right; ys[2] = box.bottom;
      xs[3] = box.left;  ys[3] = box.bottom;
    }

    Path path = new Path();
    for (int i = 0; i < 4; i++) {
      // Crop-relative -> full image -> view.
      float vx = (cropLeft + xs[i]) * fillScale + fillOffsetX;
      float vy = (cropTop + ys[i]) * fillScale + fillOffsetY;
      // The preview is rotated 180 degrees (scaleX/Y = -1) when rotateCamera is
      // set, so mirror the points to keep the border aligned.
      if (rotateCamera) {
        vx = viewWidth - vx;
        vy = viewHeight - vy;
      }
      if (i == 0) {
        path.moveTo(vx, vy);
      } else {
        path.lineTo(vx, vy);
      }
    }
    path.close();
    return path;
  }

  /** Clamps {@code value} into [min, max] and rounds to the nearest int. */
  private static int clampInt(float value, int min, int max) {
    return Math.max(min, Math.min(max, Math.round(value)));
  }

  // --------------------------------------------------------------------------
  // | Scan confirmation
  // --------------------------------------------------------------------------

  private void setupConfirmationUi() {
    freezeFrame = findViewById(getResources().getIdentifier("freeze_frame", "id", getPackageName()));
    confirmPanel = findViewById(getResources().getIdentifier("confirm_panel", "id", getPackageName()));
    confirmText = findViewById(getResources().getIdentifier("confirm_text", "id", getPackageName()));
    confirmButton = findViewById(getResources().getIdentifier("confirm_button", "id", getPackageName()));
    retryButton = findViewById(getResources().getIdentifier("retry_button", "id", getPackageName()));

    if (confirmButton != null) {
      confirmButton.setText(getResources().getIdentifier("scan_confirm", "string", getPackageName()));
      confirmButton.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          if (pendingPayload != null) {
            finishWithResult(pendingPayload);
          }
        }
      });
    }
    if (retryButton != null) {
      retryButton.setText(getResources().getIdentifier("scan_retry", "string", getPackageName()));
      retryButton.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          exitConfirmation();
        }
      });
    }
  }

  /** Freezes the preview on the detected frame and shows the Confirm/Retry prompt. */
  private void enterConfirmation(JSONArray payload, List<Barcode> barCodes) {
    awaitingConfirmation = true;
    pendingPayload = payload;

    final Bitmap frozen = (mCameraView != null) ? mCameraView.getBitmap() : null;
    final int count = payload.length();
    final String firstValue = (barCodes != null && !barCodes.isEmpty()) ? rawValueOf(barCodes.get(0)) : null;

    // Draw the detected border(s) and keep them on screen while confirming.
    redrawOverlay(barCodes);

    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (frozen != null && freezeFrame != null) {
          freezeFrame.setImageBitmap(frozen);
          freezeFrame.setVisibility(View.VISIBLE);
        }
        if (confirmText != null) {
          if (multiple) {
            confirmText.setText(getString(
                getResources().getIdentifier("scan_confirm_count", "string", getPackageName()), count));
          } else {
            confirmText.setText(firstValue);
          }
        }
        if (confirmPanel != null) {
          confirmPanel.setVisibility(View.VISIBLE);
        }
      }
    });
  }

  /** Dismisses the confirmation prompt and resumes scanning. */
  private void exitConfirmation() {
    if (freezeFrame != null) {
      freezeFrame.setVisibility(View.GONE);
      freezeFrame.setImageBitmap(null);
    }
    if (confirmPanel != null) {
      confirmPanel.setVisibility(View.GONE);
    }
    pendingPayload = null;
    redrawOverlay(null);
    // Resume the analyzer last, once the UI has been reset.
    awaitingConfirmation = false;
  }
}
