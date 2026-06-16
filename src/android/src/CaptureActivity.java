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
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.net.Uri;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

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
import com.google.android.gms.tasks.Tasks;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
  private boolean autoZoom = true;
  private boolean showZoomSlider = true;
  private boolean galleryButton = true;

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
  private static final int RC_PICK_IMAGE = 3;
  private ImageButton _TorchButton;
  private ImageButton _GalleryButton;
  private Button doneButton;
  private SeekBar zoomSlider;
  private TextView instructionText;
  private View controlBar;
  private Camera camera;

  // The ML Kit client, reused for live frames and for gallery images.
  private BarcodeScanner barcodeScanner;

  // Zoom state shared between the slider, the auto-zoom heuristic and the
  // camera control. linearZoom is in [0, 1] (0 = no zoom).
  private volatile float currentLinearZoom = 0f;
  // Once the user touches the slider, auto-zoom stops fighting them.
  private volatile boolean userZoomOverride = false;
  // Guards the slider callback while we move it programmatically.
  private boolean updatingSlider = false;
  // Timestamps (ms) driving the auto-zoom ramp.
  private volatile long lastDecodeMs = 0;
  private volatile long lastZoomStepMs = 0;

  // While reviewing a picked gallery image: the detected codes (with corner
  // points in full-image pixel space) and the image dimensions, so the overlay
  // can draw brackets over the displayed (fitCenter) image.
  private volatile List<GalleryHit> galleryHits;
  private volatile int galleryBmpW = 0;
  private volatile int galleryBmpH = 0;
  private volatile boolean galleryReview = false;

  /** A barcode found in a still image plus its corners in full-image pixels. */
  private static final class GalleryHit {
    final Barcode barcode;
    final float[] xs;
    final float[] ys;

    GalleryHit(Barcode barcode, int offX, int offY) {
      this.barcode = barcode;
      float[] cxs = null;
      float[] cys = null;
      Point[] corners = barcode.getCornerPoints();
      if (corners != null && corners.length == 4) {
        cxs = new float[4];
        cys = new float[4];
        for (int i = 0; i < 4; i++) {
          cxs[i] = corners[i].x + offX;
          cys[i] = corners[i].y + offY;
        }
      } else {
        Rect box = barcode.getBoundingBox();
        if (box != null) {
          cxs = new float[] { box.left + offX, box.right + offX, box.right + offX, box.left + offX };
          cys = new float[] { box.top + offY, box.top + offY, box.bottom + offY, box.bottom + offY };
        }
      }
      this.xs = cxs;
      this.ys = cys;
    }
  }

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
    drawDetectionBorder = getIntent().getBooleanExtra("DrawDetectionBorder", true);
    rotateCamera = getIntent().getBooleanExtra("RotateCamera", false);
    autoZoom = getIntent().getBooleanExtra("AutoZoom", true);
    showZoomSlider = getIntent().getBooleanExtra("ShowZoomSlider", true);
    galleryButton = getIntent().getBooleanExtra("GalleryButton", true);
    // Confirmation is meaningless while continuously streaming results.
    confirmation = getIntent().getBooleanExtra("Confirmation", false) && !continuous;

    // DetectorSize is the fraction of the screen that is scanned (0..1]; 1
    // means the whole screen. Out-of-range values fall back to the default.
    if (DetectorSize <= 0 || DetectorSize > 1) {
      DetectorSize = 0.6;
    }

    barcodeScanner = buildScanner();

    setupConfirmationUi();
    setupControls();

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
        // Pinch is a manual zoom; stop auto-zoom from fighting the user.
        userZoomOverride = true;
        float scale = camera.getCameraInfo().getZoomState().getValue().getZoomRatio() * detector.getScaleFactor();
        camera.getCameraControl().setZoomRatio(scale);
        Float linear = camera.getCameraInfo().getZoomState().getValue().getLinearZoom();
        if (linear != null) {
          currentLinearZoom = linear;
          syncZoomSlider(currentLinearZoom);
        }
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
    if (galleryReview) {
      redrawGalleryOverlay();
    } else {
      redrawOverlay(overlayBarcodes);
    }
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

    Preview preview = new Preview.Builder().build();

    CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .build();

    preview.setSurfaceProvider(mCameraView.createSurfaceProvider());

    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetAspectRatio(AspectRatio.RATIO_16_9)
        .build();

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
        barcodeScanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener(new OnSuccessListener<List<Barcode>>() {
              @Override
              public void onSuccess(List<Barcode> barCodes) {
                // Another frame may have entered confirmation while this one
                // was in flight; drop it.
                if (awaitingConfirmation) {
                  return;
                }

                // Ramp the zoom in if nothing has decoded for a moment, to
                // pull in small or distant codes.
                maybeAutoZoom(barCodes != null && !barCodes.isEmpty());

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

    // Start from no zoom and give auto-zoom a fresh grace period before it
    // starts ramping in.
    currentLinearZoom = 0f;
    userZoomOverride = false;
    lastDecodeMs = System.currentTimeMillis();
    lastZoomStepMs = lastDecodeMs;
    camera.getCameraControl().setLinearZoom(0f);
    setupZoomSlider();
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
   * Closes the scanner after the user taps "Done" in continuous mode. Results
   * were already streamed live, so this just signals a graceful completion.
   */
  private void finishCompleted() {
    Intent data = new Intent();
    data.putExtra("completed", true);
    setResult(CommonStatusCodes.SUCCESS, data);
    finish();
  }

  /** Builds the ML Kit client for the requested formats (camera + gallery). */
  private BarcodeScanner buildScanner() {
    int barcodeFormat;
    if (BarcodeFormats == 0 || BarcodeFormats == 1234) {
      barcodeFormat = (Barcode.FORMAT_CODE_39 | Barcode.FORMAT_DATA_MATRIX);
    } else {
      barcodeFormat = BarcodeFormats;
    }
    return BarcodeScanning
        .getClient(new BarcodeScannerOptions.Builder().setBarcodeFormats(barcodeFormat).build());
  }

  // --------------------------------------------------------------------------
  // | Camera UI controls (instruction banner, Done, gallery)
  // --------------------------------------------------------------------------

  /** Wires up the instruction banner, the continuous Done button and gallery. */
  private void setupControls() {
    controlBar = findViewById(getResources().getIdentifier("control_bar", "id", getPackageName()));
    instructionText = findViewById(getResources().getIdentifier("instruction_text", "id", getPackageName()));
    if (instructionText != null) {
      instructionText.setText(getResources().getIdentifier("scan_instructions", "string", getPackageName()));
    }

    doneButton = findViewById(getResources().getIdentifier("done_button", "id", getPackageName()));
    if (doneButton != null) {
      // The Done button only makes sense while the camera stays open streaming
      // results; a single scan closes on its own.
      doneButton.setText(getResources().getIdentifier("scan_done", "string", getPackageName()));
      doneButton.setVisibility(continuous ? View.VISIBLE : View.GONE);
      doneButton.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          finishCompleted();
        }
      });
    }

    _GalleryButton = findViewById(getResources().getIdentifier("gallery_button", "id", getPackageName()));
    if (_GalleryButton != null) {
      _GalleryButton.setVisibility(galleryButton ? View.VISIBLE : View.GONE);
      _GalleryButton.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View v) {
          openGallery();
        }
      });
    }
  }

  /** Launches the system image picker so the user can scan a saved image. */
  private void openGallery() {
    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
    intent.setType("image/*");
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    try {
      startActivityForResult(Intent.createChooser(intent,
          getString(getResources().getIdentifier("scan_gallery", "string", getPackageName()))), RC_PICK_IMAGE);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != RC_PICK_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) {
      return;
    }

    final Uri uri = data.getData();
    // Switch to an image-review UI: pause the live analyzer and show the picked
    // image while we scan it, instead of leaving the camera preview up.
    awaitingConfirmation = true;
    executor.execute(new Runnable() {
      @Override
      public void run() {
        Bitmap bitmap = null;
        try {
          bitmap = BitmapUtils.getBitmapFromContentUri(getContentResolver(), uri);
        } catch (IOException e) {
          e.printStackTrace();
        }
        if (bitmap == null) {
          showNoBarcodeToast();
          resumeAfterGallery();
          return;
        }
        showGalleryImage(bitmap);
        List<GalleryHit> hits = scanImageForHits(bitmap);
        handleGalleryHits(bitmap, hits);
      }
    });
  }

  /** Shows the picked image full-screen and hides the live camera chrome. */
  private void showGalleryImage(final Bitmap bitmap) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (freezeFrame != null) {
          freezeFrame.setImageBitmap(bitmap);
          freezeFrame.setVisibility(View.VISIBLE);
        }
        // Keep the overlay surface visible so we can draw detection brackets on
        // top of the image; just clear any camera focus box for now.
        clearOverlay();
        setCameraChromeVisible(false);
      }
    });
  }

  /** Restores the live camera UI after reviewing a gallery image. */
  private void resumeAfterGallery() {
    galleryReview = false;
    galleryHits = null;
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (freezeFrame != null) {
          freezeFrame.setVisibility(View.GONE);
          freezeFrame.setImageBitmap(null);
        }
        if (confirmPanel != null) {
          confirmPanel.setVisibility(View.GONE);
        }
        setCameraChromeVisible(true);
        redrawOverlay(null);
        awaitingConfirmation = false;
      }
    });
  }

  /**
   * Handles the result of scanning a picked gallery image: draws detection
   * brackets over the image and shows the Confirm/Retry prompt, returning the
   * result only on Confirm. Warns and resumes the camera when nothing is found.
   */
  private void handleGalleryHits(Bitmap bitmap, List<GalleryHit> hits) {
    JSONArray payload = new JSONArray();
    try {
      if (hits != null) {
        for (GalleryHit hit : hits) {
          String value = rawValueOf(hit.barcode);
          if (value == null) {
            continue;
          }
          payload.put(toTriple(hit.barcode, value));
        }
      }
    } catch (JSONException e) {
      e.printStackTrace();
    }

    if (payload.length() == 0) {
      showNoBarcodeToast();
      resumeAfterGallery();
      return;
    }

    galleryBmpW = bitmap.getWidth();
    galleryBmpH = bitmap.getHeight();
    galleryHits = hits;
    galleryReview = true;
    pendingPayload = payload;

    final String firstValue = payload.optJSONArray(0) != null ? payload.optJSONArray(0).optString(0) : null;
    final int count = payload.length();
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        redrawGalleryOverlay();
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

  /**
   * Scans a still image for barcodes. Tries the whole image first; if that finds
   * nothing it falls back to scanning overlapping tiles at full resolution,
   * which effectively zooms in and can pick up small or distant codes (e.g. a
   * QR shot from far away). The tiled pass only runs on a miss, so the extra
   * work is bounded. Corner points are recorded in full-image pixel space.
   */
  private List<GalleryHit> scanImageForHits(Bitmap bmp) {
    List<GalleryHit> hits = new ArrayList<>();
    Set<String> seen = new HashSet<>();

    collectHits(detect(bmp), 0, 0, hits, seen);
    if (!hits.isEmpty()) {
      return hits;
    }

    final int tiles = 3;
    int w = bmp.getWidth();
    int h = bmp.getHeight();
    int tileW = (int) Math.ceil(w / (double) tiles);
    int tileH = (int) Math.ceil(h / (double) tiles);
    // Step by half a tile so a code straddling a seam still lands fully inside
    // one tile (50% overlap).
    int stepX = Math.max(1, tileW / 2);
    int stepY = Math.max(1, tileH / 2);

    for (int top = 0; top < h; top += stepY) {
      for (int left = 0; left < w; left += stepX) {
        int cw = Math.min(tileW, w - left);
        int ch = Math.min(tileH, h - top);
        if (cw < 16 || ch < 16) {
          continue;
        }
        Bitmap tile = Bitmap.createBitmap(bmp, left, top, cw, ch);
        collectHits(detect(tile), left, top, hits, seen);
        tile.recycle();
        if (!hits.isEmpty() && !multiple) {
          return hits;
        }
      }
    }
    return hits;
  }

  /** Appends de-duplicated barcodes to {@code hits}, offsetting tile corners. */
  private void collectHits(List<Barcode> r, int offX, int offY, List<GalleryHit> hits, Set<String> seen) {
    if (r == null) {
      return;
    }
    for (Barcode b : r) {
      String v = rawValueOf(b);
      if (v != null && seen.add(v)) {
        hits.add(new GalleryHit(b, offX, offY));
        if (!multiple) {
          return;
        }
      }
    }
  }

  /** Runs ML Kit on a bitmap synchronously (called off the UI thread). */
  private List<Barcode> detect(Bitmap bmp) {
    try {
      return Tasks.await(barcodeScanner.process(InputImage.fromBitmap(bmp, 0)));
    } catch (ExecutionException | InterruptedException e) {
      return null;
    }
  }

  private void showNoBarcodeToast() {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Toast.makeText(CaptureActivity.this,
            getResources().getIdentifier("scan_no_barcode_found", "string", getPackageName()),
            Toast.LENGTH_SHORT).show();
      }
    });
  }

  // --------------------------------------------------------------------------
  // | Zoom (manual slider + auto-zoom heuristic)
  // --------------------------------------------------------------------------

  /** Shows the zoom slider (when enabled and supported) and wires it up. */
  private void setupZoomSlider() {
    zoomSlider = findViewById(getResources().getIdentifier("zoom_slider", "id", getPackageName()));
    if (zoomSlider == null) {
      return;
    }

    boolean canZoom = camera != null && camera.getCameraInfo().getZoomState().getValue() != null
        && camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio() > 1f;
    if (!showZoomSlider || !canZoom) {
      zoomSlider.setVisibility(View.GONE);
      return;
    }

    zoomSlider.setVisibility(View.VISIBLE);
    zoomSlider.setProgress(0);
    zoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        // Ignore programmatic updates from auto-zoom; only react to the user.
        if (updatingSlider || !fromUser) {
          return;
        }
        userZoomOverride = true;
        currentLinearZoom = progress / 100f;
        if (camera != null) {
          camera.getCameraControl().setLinearZoom(currentLinearZoom);
        }
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {
        userZoomOverride = true;
      }

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
      }
    });
  }

  /** Moves the slider to match {@code linear} without firing the user path. */
  private void syncZoomSlider(final float linear) {
    if (zoomSlider == null) {
      return;
    }
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        updatingSlider = true;
        zoomSlider.setProgress(Math.round(linear * 100));
        updatingSlider = false;
      }
    });
  }

  /**
   * Auto-zoom heuristic. ML Kit only reports barcodes it could fully decode, so
   * there is no "detected but unreadable" signal to react to. Instead, when
   * nothing has decoded for a short while we step the zoom in (up to a cap),
   * which pulls small or distant codes closer until one reads. Resets once a
   * code is decoded or the user takes over the zoom.
   *
   * @param decoded whether the current frame produced at least one barcode
   */
  private void maybeAutoZoom(boolean decoded) {
    long now = System.currentTimeMillis();
    if (decoded) {
      lastDecodeMs = now;
      return;
    }
    if (!autoZoom || userZoomOverride || camera == null) {
      return;
    }
    // Wait ~1s of "nothing decoded", and step at most ~twice a second, up to a
    // 0.65 linear-zoom cap so we never zoom past usable focus.
    if (now - lastDecodeMs < 1000 || now - lastZoomStepMs < 500 || currentLinearZoom >= 0.65f) {
      return;
    }
    currentLinearZoom = Math.min(0.65f, currentLinearZoom + 0.08f);
    lastZoomStepMs = now;
    camera.getCameraControl().setLinearZoom(currentLinearZoom);
    syncZoomSlider(currentLinearZoom);
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

          // Draw the focus box as four corner brackets rather than a full
          // rectangle, for a cleaner viewfinder look.
          Paint focusPaint = new Paint();
          focusPaint.setStyle(Paint.Style.STROKE);
          focusPaint.setColor(Color.WHITE);
          focusPaint.setStrokeWidth(8);
          focusPaint.setStrokeCap(Paint.Cap.ROUND);
          focusPaint.setAntiAlias(true);
          drawCornerBrackets(c, new float[] { left, right, right, left },
              new float[] { top, top, bottom, bottom }, focusPaint);
        }

        if (barcodes != null && haveMapping) {
          // Detection markers are corner brackets that trace each barcode's
          // corner points (so they follow rotated and 2D codes).
          Paint borderPaint = new Paint();
          borderPaint.setStyle(Paint.Style.STROKE);
          borderPaint.setColor(Color.parseColor("#00E676"));
          borderPaint.setStrokeWidth(10);
          borderPaint.setStrokeCap(Paint.Cap.ROUND);
          borderPaint.setAntiAlias(true);
          float[] xs = new float[4];
          float[] ys = new float[4];
          for (Barcode barcode : barcodes) {
            if (barcodeCorners(barcode, xs, ys)) {
              drawCornerBrackets(c, xs, ys, borderPaint);
            }
          }
        }
      } finally {
        holder.unlockCanvasAndPost(c);
      }
    }
  }

  /** Clears the overlay surface (no focus box, no brackets). */
  private void clearOverlay() {
    if (holder == null) {
      return;
    }
    synchronized (overlayLock) {
      Canvas c = holder.lockCanvas();
      if (c == null) {
        return;
      }
      try {
        c.drawColor(0, PorterDuff.Mode.CLEAR);
      } finally {
        holder.unlockCanvasAndPost(c);
      }
    }
  }

  /**
   * Draws detection brackets over a picked gallery image. The image is shown
   * with fitCenter scaling, so map each code's full-image pixel corners into
   * view space with the matching scale/offset.
   */
  private void redrawGalleryOverlay() {
    if (holder == null) {
      return;
    }
    List<GalleryHit> hits = galleryHits;
    int bw = galleryBmpW;
    int bh = galleryBmpH;
    int vw = viewWidth > 0 ? viewWidth : (mCameraView != null ? mCameraView.getWidth() : 0);
    int vh = viewHeight > 0 ? viewHeight : (mCameraView != null ? mCameraView.getHeight() : 0);

    synchronized (overlayLock) {
      Canvas c = holder.lockCanvas();
      if (c == null) {
        return;
      }
      try {
        c.drawColor(0, PorterDuff.Mode.CLEAR);
        if (hits == null || bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) {
          return;
        }
        // fitCenter: scale the image down to fit inside the view, centred.
        float scale = Math.min((float) vw / bw, (float) vh / bh);
        float offX = (vw - bw * scale) / 2f;
        float offY = (vh - bh * scale) / 2f;

        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.parseColor("#00E676"));
        paint.setStrokeWidth(10);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setAntiAlias(true);

        float[] xs = new float[4];
        float[] ys = new float[4];
        for (GalleryHit hit : hits) {
          if (hit.xs == null) {
            continue;
          }
          for (int i = 0; i < 4; i++) {
            xs[i] = hit.xs[i] * scale + offX;
            ys[i] = hit.ys[i] * scale + offY;
          }
          drawCornerBrackets(c, xs, ys, paint);
        }
      } finally {
        holder.unlockCanvasAndPost(c);
      }
    }
  }

  /**
   * Fills {@code xs}/{@code ys} with a barcode's four corner points mapped from
   * the crop handed to ML Kit into overlay/view space (FILL_CENTER mapping),
   * ordered clockwise. Returns false if the barcode has no usable geometry.
   */
  private boolean barcodeCorners(Barcode barcode, float[] xs, float[] ys) {
    Point[] corners = barcode.getCornerPoints();
    if (corners != null && corners.length == 4) {
      for (int i = 0; i < 4; i++) {
        xs[i] = corners[i].x;
        ys[i] = corners[i].y;
      }
    } else {
      Rect box = barcode.getBoundingBox();
      if (box == null) {
        return false;
      }
      xs[0] = box.left;  ys[0] = box.top;
      xs[1] = box.right; ys[1] = box.top;
      xs[2] = box.right; ys[2] = box.bottom;
      xs[3] = box.left;  ys[3] = box.bottom;
    }

    for (int i = 0; i < 4; i++) {
      // Crop-relative -> full image -> view.
      float vx = (cropLeft + xs[i]) * fillScale + fillOffsetX;
      float vy = (cropTop + ys[i]) * fillScale + fillOffsetY;
      // The preview is rotated 180 degrees (scaleX/Y = -1) when rotateCamera is
      // set, so mirror the points to keep the markers aligned.
      if (rotateCamera) {
        vx = viewWidth - vx;
        vy = viewHeight - vy;
      }
      xs[i] = vx;
      ys[i] = vy;
    }
    return true;
  }

  /**
   * Draws short corner brackets ("L" marks) at each of the four supplied
   * corners instead of a continuous outline. {@code xs}/{@code ys} are the
   * corner points ordered around the quad.
   */
  private void drawCornerBrackets(Canvas c, float[] xs, float[] ys, Paint paint) {
    for (int i = 0; i < 4; i++) {
      int next = (i + 1) % 4;
      int prev = (i + 3) % 4;
      drawBracketLeg(c, xs[i], ys[i], xs[next], ys[next], paint);
      drawBracketLeg(c, xs[i], ys[i], xs[prev], ys[prev], paint);
    }
  }

  /** Draws one leg of a corner bracket, from a corner partway to a neighbour. */
  private void drawBracketLeg(Canvas c, float fromX, float fromY, float toX, float toY, Paint paint) {
    float dx = toX - fromX;
    float dy = toY - fromY;
    float len = (float) Math.hypot(dx, dy);
    if (len <= 0) {
      return;
    }
    // A leg is at most ~36px but never longer than 30% of the edge, so small
    // boxes still read as brackets rather than full outlines.
    float t = Math.min(len * 0.3f, 36f) / len;
    c.drawLine(fromX, fromY, fromX + dx * t, fromY + dy * t, paint);
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
          // Retry from a gallery review returns to the live camera; retry from a
          // live confirmation just resumes the analyzer.
          if (galleryReview) {
            resumeAfterGallery();
          } else {
            exitConfirmation();
          }
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
        // The live controls don't belong over a frozen frame.
        setCameraChromeVisible(false);
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
    setCameraChromeVisible(true);
    pendingPayload = null;
    redrawOverlay(null);
    // Resume the analyzer last, once the UI has been reset.
    awaitingConfirmation = false;
  }

  /**
   * Shows or hides the live camera chrome (control bar, zoom slider, instruction
   * banner). Hidden while a frozen frame or a picked gallery image is on screen.
   */
  private void setCameraChromeVisible(final boolean visible) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        int v = visible ? View.VISIBLE : View.GONE;
        if (controlBar != null) {
          controlBar.setVisibility(v);
        }
        if (instructionText != null) {
          instructionText.setVisibility(v);
        }
        if (zoomSlider != null && showZoomSlider) {
          // Only restore the slider if zoom is actually available.
          boolean canZoom = camera != null && camera.getCameraInfo().getZoomState().getValue() != null
              && camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio() > 1f;
          zoomSlider.setVisibility(visible && canZoom ? View.VISIBLE : View.GONE);
        }
      }
    });
  }
}
