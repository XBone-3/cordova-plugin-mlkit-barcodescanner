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
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.os.Bundle;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageButton;

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

  // Raw values already streamed back in continuous mode, so each barcode is
  // reported only once per session.
  private final Set<String> emittedValues = new HashSet<>();

  private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
  private ExecutorService executor = Executors.newSingleThreadExecutor();
  private PreviewView mCameraView;
  private SurfaceHolder holder;
  private SurfaceView surfaceView;
  private Canvas canvas;
  private Paint paint;

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

    if (DetectorSize <= 0 || DetectorSize >= 1) { // setting boundary detectorSize must be between 0 to 1.
      DetectorSize = 0.5;
    }

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
      DrawFocusRect(Color.parseColor("#FFFFFF"));
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
  public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
    DrawFocusRect(Color.parseColor("#FFFFFF"));
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

    Boolean rotateCamera = getIntent().getBooleanExtra("RotateCamera", false);
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

        Bitmap bmp = BitmapUtils.getBitmap(image);

        int height = bmp.getHeight();
        int width = bmp.getWidth();

        int left, right, top, bottom, diameter, boxHeight, boxWidth;

        diameter = width;
        if (height < width) {
          diameter = height;
        }

        int offset = (int) ((1 - DetectorSize) * diameter);
        diameter -= offset;

        left = width / 2 - diameter / 2;
        top = height / 2 - diameter / 2;
        right = width / 2 + diameter / 2;
        bottom = height / 2 + diameter / 2;

        boxHeight = bottom - top;
        boxWidth = right - left;

        Bitmap bitmap = Bitmap.createBitmap(bmp, left, top, boxWidth, boxHeight);
        scanner.process(InputImage.fromBitmap(bitmap, image.getImageInfo().getRotationDegrees()))
            .addOnSuccessListener(new OnSuccessListener<List<Barcode>>() {
              @Override
              public void onSuccess(List<Barcode> barCodes) {

                // # Code to test image viewfinder
                /*
                 * ImageView imageView = (ImageView)
                 * findViewById(getResources().getIdentifier("imageView", "id",
                 * getPackageName())); imageView.setImageBitmap(bitmap);
                 */

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
    } else {
      Intent data = new Intent();
      data.putExtra(BarcodePayload, payload.toString());
      setResult(CommonStatusCodes.SUCCESS, data);
      finish();
    }
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

  /**
   * For drawing the rectangular box
   */
  private void DrawFocusRect(int color) {

    if (mCameraView != null) {
      int height = mCameraView.getHeight();
      int width = mCameraView.getWidth();

      int left, right, top, bottom, diameter;

      diameter = width;
      if (height < width) {
        diameter = height;
      }

      int offset = (int) ((1 - DetectorSize) * diameter);
      diameter -= offset;

      canvas = holder.lockCanvas();
      canvas.drawColor(0, PorterDuff.Mode.CLEAR);
      // border's properties
      paint = new Paint();
      paint.setStyle(Paint.Style.STROKE);
      paint.setColor(color);
      paint.setStrokeWidth(5);

      left = width / 2 - diameter / 2;
      top = height / 2 - diameter / 2;
      right = width / 2 + diameter / 2;
      bottom = height / 2 + diameter / 2;

      // Changing the value of x in diameter/x will change the size of the box ;
      // inversely proportionate to x
      if (DetectorSize <= 0.3) {
        canvas.drawRect(new RectF(left, top, right, bottom), paint);
      } else {
        canvas.drawRoundRect(new RectF(left, top, right, bottom), 100, 100, paint);
      }

      holder.unlockCanvasAndPost(canvas);
    }

  }
}
