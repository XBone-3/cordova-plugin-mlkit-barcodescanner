package com.mobisys.cordova.plugins.mlkit.barcode.scanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.hardware.camera2.CameraManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import com.google.android.gms.common.api.CommonStatusCodes;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * This class echoes a string called from JavaScript.
 */
public class MLKitBarcodeScanner extends CordovaPlugin {

  private static final String TAG = "MLKitBarcodeScanner";
  private static final int RC_BARCODE_CAPTURE = 9001;

  // Active plugin instance, used by CaptureActivity to stream results back
  // while the camera stays open in continuous mode.
  private static MLKitBarcodeScanner _Instance;

  private CallbackContext _CallbackContext;
  private Boolean _BeepOnSuccess;
  private Boolean _VibrateOnSuccess;
  private MediaPlayer _MediaPlayer;
  private Vibrator _Vibrator;

  public void initialize(CordovaInterface cordova, CordovaWebView webView) {
    super.initialize(cordova, webView);

    _Instance = this;

    Context context = cordova.getContext();

    _Vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    _MediaPlayer = new MediaPlayer();

    try {
      AssetFileDescriptor descriptor = context.getAssets().openFd("beep.ogg");
      _MediaPlayer.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), descriptor.getLength());
      descriptor.close();
      _MediaPlayer.prepare();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    Activity activity = cordova.getActivity();
    Boolean hasCamera = activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    CameraManager cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

    _CallbackContext = callbackContext;

    int numberOfCameras = 0;

    try {
      numberOfCameras = cameraManager.getCameraIdList().length;
    } catch (Exception e) {
      e.printStackTrace();
    }

    if (!hasCamera || numberOfCameras == 0) {
      AlertDialog alertDialog = new AlertDialog.Builder(activity).create();
      alertDialog.setMessage(activity.getString(activity.getResources()
          .getIdentifier("no_cameras_found", "string", activity.getPackageName())));
      alertDialog.setButton(
          AlertDialog.BUTTON_POSITIVE, activity.getString(activity.getResources()
              .getIdentifier("ok", "string", activity.getPackageName())),
          new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
              dialog.dismiss();
            }
          });
      alertDialog.show();
      return false;
    }

    if (action.equals("startScan")) {
      class OneShotTask implements Runnable {
        private final Context context;
        private final JSONArray args;

        private OneShotTask(Context ctx, JSONArray as) {
          context = ctx;
          args = as;
        }

        public void run() {
          try {
            openNewActivity(context, args);
          } catch (JSONException e) {
            _CallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, e.toString()));
          }
        }
      }
      Thread t = new Thread(new OneShotTask(cordova.getContext(), args));
      t.start();
      return true;
    }
    return false;
  }

  private void openNewActivity(Context context, JSONArray args) throws JSONException {
    JSONObject config = args.getJSONObject(0);
    Intent intent = new Intent(context, CaptureActivity.class);
    intent.putExtra("BarcodeFormats", config.optInt("barcodeFormats", 1234));
    intent.putExtra("DetectorSize", config.optDouble("detectorSize", 0.5));
    intent.putExtra("RotateCamera", config.optBoolean("rotateCamera", false));
    intent.putExtra("Continuous", config.optBoolean("continuous", false));
    intent.putExtra("Multiple", config.optBoolean("multiple", false));

    _BeepOnSuccess = config.optBoolean("beepOnSuccess", false);
    _VibrateOnSuccess = config.optBoolean("vibrateOnSuccess", false);

    // NOTE: do not call setActivityResultCallback(this) here.
    // startActivityForResult() already registers this plugin as the result
    // callback. Calling it twice makes the second registration treat the first
    // as a still-pending activity and cancel it via a synthetic
    // onActivityResult(activityResultRequestCode, RESULT_CANCELED, null). Once
    // activityResultRequestCode has been set to RC_BARCODE_CAPTURE by an
    // earlier scan, that synthetic cancel passes our guard and aborts every
    // subsequent scan before the camera opens.
    this.cordova.startActivityForResult(this, intent, RC_BARCODE_CAPTURE);
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (requestCode == RC_BARCODE_CAPTURE) {
      // CommonStatusCodes.SUCCESS and Activity.RESULT_CANCELED are both 0, so a
      // successful scan can only be distinguished from a cancellation (back
      // press / permission denied) by the presence of result data.
      if (resultCode == CommonStatusCodes.SUCCESS && data != null) {
        String payload = data.getStringExtra(CaptureActivity.BarcodePayload);
        try {
          // The payload is an array of [text, format, type] triples (one per
          // detected barcode).
          JSONArray result = new JSONArray(payload);
          _CallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, result));
          notifyScan();
          Log.d(TAG, "Barcodes read: " + payload);
        } catch (JSONException e) {
          _CallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, e.toString()));
        }
      } else {
        // No result data means the user cancelled (back press / permission
        // denied). Report it as a cancellation rather than crashing on a null
        // intent. A null first element is mapped to USER_CANCELLED in JS.
        String err = (data != null) ? data.getStringExtra("err") : null;
        JSONArray result = new JSONArray();
        result.put(err);
        result.put("");
        result.put("");
        _CallbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, result));
      }
    }
  }

  /**
   * Streams a batch of barcodes back to JS while keeping the callback (and the
   * camera) alive. Called from CaptureActivity in continuous mode.
   */
  static void sendContinuousResult(JSONArray barcodes) {
    MLKitBarcodeScanner self = _Instance;
    if (self == null || self._CallbackContext == null) {
      return;
    }
    PluginResult result = new PluginResult(PluginResult.Status.OK, barcodes);
    result.setKeepCallback(true);
    self._CallbackContext.sendPluginResult(result);
    self.notifyScan();
  }

  /** Plays the success beep and/or vibrates, honouring the scan options. */
  private void notifyScan() {
    if (_BeepOnSuccess) {
      _MediaPlayer.start();
    }

    if (_VibrateOnSuccess) {
      int duration = 200;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        _Vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
      } else {
        // deprecated in API 26 aka Oreo
        _Vibrator.vibrate(duration);
      }
    }
  }

  @Override
  public void onRestoreStateForActivityResult(Bundle state, CallbackContext callbackContext) {
    _CallbackContext = callbackContext;
  }
}
