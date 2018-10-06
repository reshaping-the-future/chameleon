package ac.robinson.chameleonnotifier;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.graphics.Palette;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnticipateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.ImageViewState;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.jwetherell.motion_detection.SensorMotionActivity;
import com.jwetherell.motion_detection.detection.IMotionDetection;
import com.jwetherell.motion_detection.detection.RgbMotionDetection;
import com.jwetherell.motion_detection.image.ImageProcessing;
import com.michael.easydialog.EasyDialog;

import org.opencv.android.OpenCVLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ac.robinson.chameleonnotifier.service.MonitorManager;
import ac.robinson.chameleonnotifier.service.NotificationMonitorService;
import ac.robinson.chameleonnotifier.view.CameraSurfaceView;
import ac.robinson.chameleonnotifier.view.CircleImageButton;

public class CameraActivity extends SensorMotionActivity {

	private enum Mode {
		CAMERA, EVENTS
	}

	private Mode mCurrentMode;

	private static final String TAG = "CameraActivity";

	private static final int AUTO_HIDE_DELAY_MILLIS = 3000; // how long to wait before hiding UI elements
	private final Handler mHideHandler = new Handler();

	private static final int CAMERA_PERMISSION_RESULT = 143; // "cam"
	private static final int AUTO_FOCUS_INTERVAL = 1000; // milliseconds

	private Camera mCamera;
	private boolean mHasRequestedCameraPermission;
	private boolean mIsPreviewing;
	private boolean mIsUsingFrontCamera;
	private int mInitialBrightnessMode;
	private int mInitialBrightnessLevel;

	private File mImageCacheFile;

	private View mContentView;
	private ImageButton mTakePhotoButton;
	private SeekBar mAdjustBrightnessBar;
	private ImageButton mEnableColourPickerButton;
	private View mImageCustomisationControls;

	private FrameLayout mPreviewFrame;
	private SubsamplingScaleImageView mZoomableImageView;
	private ImageView mHighlightImageView;

	private Bitmap mOriginalImage;
	private final Bitmap[] mNotificationImages = new Bitmap[3]; // 0 = facebook, 1 = sms, 2 = whatsapp
	private final ImageAnalyserTask[] mImageAnalyserTasks = new ImageAnalyserTask[3];
	private int mFacebookColour;
	private int mSmsColour;
	private int mWhatsAppColour;
	private int mFacebookNotificationCount = 0;
	private int mSMSNotificationCount = 0;
	private int mWhatsAppNotificationCount = 0;
	private String mFacebookNotificationTitle;
	private String mFacebookNotificationMessage;
	private PendingIntent mFacebookPendingIntent;
	private String mSMSNotificationTitle;
	private String mSMSNotificationMessage;
	private PendingIntent mSMSPendingIntent;
	private String mWhatsAppNotificationTitle;
	private String mWhatsAppNotificationMessage;
	private PendingIntent mWhatsAppPendingIntent;
	private EasyDialog mCurrentNotificationDialog;

	private static volatile AtomicBoolean sIsProcessingMotionDetection = new AtomicBoolean(false);
	private static long sMotionDetectionReferenceTime = 0;
	private static final IMotionDetection sMotionDetector = new RgbMotionDetection();

	private static final int BUTTON_ANIMATION_DURATION = 250; // animation (and removal) time for notification buttons
	private CircleImageButton mFacebookButton;
	private CircleImageButton mSmsButton;
	private CircleImageButton mWhatsAppButton;
	private AnimatorSet mButtonAnimator;
	private final Handler mButtonHideHandler = new Handler();
	private final PointF mButtonStartPosition = new PointF();

	private static final int IMAGE_ANIMATION_DURATION = 1000; // the length of an image highlight
	private static final float IMAGE_ANIMATION_SCALE = 1.1f; // applied to duration so it finishes before the next one

	static {
		if (!OpenCVLoader.initDebug()) {
			throw new RuntimeException(); // TODO: handle initialisation error
		}
	}

	private static final int EVENT_REPEAT_COUNT = 0; // how many *repeats* (e.g., 1 would show notification twice)
	private final Handler mToggleImageHighlightHandler = new Handler();
	private int mTransitionRepeatCount;
	private TransitionDrawable mTransitionDrawable;

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_camera);

		// can't do anything if we don't have a camera
		if (!CameraUtilities.getIsCameraAvailable(getPackageManager())) {
			Toast.makeText(CameraActivity.this, R.string.message_no_camera, Toast.LENGTH_SHORT).show();
			finish();
			return;
		}

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayShowTitleEnabled(true);
		}

		mImageCacheFile = new File(getCacheDir(), "photo.jpg");
		mContentView = findViewById(R.id.fullscreen_content);

		mPreviewFrame = findViewById(R.id.camera_preview);
		mZoomableImageView = findViewById(R.id.zoomable_image_view);
		mZoomableImageView.setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP);
		mZoomableImageView.setMaxScale(100);
		// TODO: if enabling quick scale, need to ensure checkAndSendZoomOrPanCompleted is called appropriately
		mZoomableImageView.setQuickScaleEnabled(false);
		mZoomableImageView.setOnTouchListener(mColourPickerTouchListener);
		mHighlightImageView = findViewById(R.id.image_view);
		setupColourButtons();

		mTakePhotoButton = findViewById(R.id.camera_take_photo);
		mAdjustBrightnessBar = findViewById(R.id.screen_brightness_control);
		mEnableColourPickerButton = findViewById(R.id.image_enable_colour_picker);
		mImageCustomisationControls = findViewById(R.id.image_customisation_controls);

		int[] modeLevel = initialiseBrightnessBar();
		mInitialBrightnessMode = modeLevel[0];
		mInitialBrightnessLevel = modeLevel[1];

		// check camera permissions (see onResume for full method)
		if (savedInstanceState != null) {
			mHasRequestedCameraPermission = savedInstanceState.getBoolean("mHasRequestedCameraPermission", false);
			mInitialBrightnessMode = savedInstanceState.getInt("mInitialBrightnessMode", Settings.System
					.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
			mInitialBrightnessLevel = savedInstanceState.getInt("mInitialBrightnessLevel", 255);
		}

		mCurrentMode = Mode.CAMERA;
	}

	@Override
	public void onPause() {
		super.onPause();
		releaseCamera();

		LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(CameraActivity.this);
		Intent stopNotificationsIntent = new Intent(MonitorManager.STOP_MANAGING_NOTIFICATIONS);
		localBroadcastManager.sendBroadcast(stopNotificationsIntent);
		localBroadcastManager.unregisterReceiver(mBroadcastReceiver); // cancel event listener

		// resume brightness - for API >= 23 we need permission, handled when brightness is first changed by the user
		if (canWriteSettings()) {
			setBrightness(mInitialBrightnessMode, mInitialBrightnessLevel);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		// listen for events
		LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(CameraActivity.this);
		localBroadcastManager.registerReceiver(mBroadcastReceiver, new IntentFilter(MonitorManager.TYPE_FACEBOOK));
		localBroadcastManager.registerReceiver(mBroadcastReceiver, new IntentFilter(MonitorManager.TYPE_SMS));
		localBroadcastManager.registerReceiver(mBroadcastReceiver, new IntentFilter(MonitorManager.TYPE_WHATSAPP));

		switch (mCurrentMode) {
			case CAMERA:
				if (checkCameraPermissions()) {
					setupCamera(false);
				}
				break;

			case EVENTS:
				if (checkCameraPermissions()) {
					setupCamera(true);
				}
				Intent startNotificationsIntent = new Intent(MonitorManager.START_MANAGING_NOTIFICATIONS);
				localBroadcastManager.sendBroadcast(startNotificationsIntent);
				if (mFacebookNotificationCount > 0 || mSMSNotificationCount > 0 || mWhatsAppNotificationCount > 0) {
					// if we've shown notifications, don't allow moving again (for simplicity of interface)
					// TODO: improve
					hideSystemUI(AUTO_HIDE_DELAY_MILLIS);
				} else {
					showAndHideSystemUI(AUTO_HIDE_DELAY_MILLIS);
				}
				break;
		}

		// resume brightness - for API >= 23 we need permission, handled when brightness is changed by the user
		if (canWriteSettings()) {
			setBrightness(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, mAdjustBrightnessBar.getProgress());
		}

		// make sure we have notification access - after hiding UI so the dialog stays in the position it appears in
		// we also link with the camera permission check, so we only show one dialog at once
		if (ContextCompat.checkSelfPermission(CameraActivity.this, Manifest.permission.CAMERA) ==
				PackageManager.PERMISSION_GRANTED) {
			checkNotificationServiceAccess();
		}
	}

	private boolean canWriteSettings() {
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(CameraActivity.this);
	}

	@SuppressLint("ClickableViewAccessibility")
	private int[] initialiseBrightnessBar() {
		int initialBrightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
		int initialBrightnessLevel = 255; // 255 is max brightness
		mAdjustBrightnessBar.setMax(initialBrightnessLevel);

		try {
			initialBrightnessMode = Settings.System.getInt(getContentResolver(), Settings.System
					.SCREEN_BRIGHTNESS_MODE);
		} catch (Settings.SettingNotFoundException ignored) {
		}
		try {
			initialBrightnessLevel = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
		} catch (Settings.SettingNotFoundException ignored) {
		}
		mAdjustBrightnessBar.setProgress(initialBrightnessLevel);

		mAdjustBrightnessBar.setOnTouchListener(mDelayHideTouchListener);
		mAdjustBrightnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@TargetApi(Build.VERSION_CODES.M) // for ACTION_MANAGE_WRITE_SETTINGS,
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (canWriteSettings()) {
					setBrightness(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, progress);
				} else {
					mAdjustBrightnessBar.setEnabled(false);
					AlertDialog.Builder builder = new AlertDialog.Builder(CameraActivity.this);
					builder.setTitle(R.string.hint_settings_access)
							.setMessage(R.string.hint_enable_settings_access)
							.setOnDismissListener(new DialogInterface.OnDismissListener() {
								@Override
								public void onDismiss(DialogInterface dialog) {
									// note: dismiss rather than cancel so we always take this action
									mAdjustBrightnessBar.setEnabled(true);
								}
							})
							.setPositiveButton(R.string.hint_edit_settings_access, new DialogInterface.OnClickListener
									() {
								@Override
								public void onClick(DialogInterface dialog, int which) {
									try {
										Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
										intent.setData(Uri.parse("package:ac.robinson.chameleonnotifier"));
										startActivity(intent);
									} catch (ActivityNotFoundException e) {
										Toast.makeText(CameraActivity.this, R.string.error_editing_settings, Toast
												.LENGTH_LONG)
												.show();
									}
									dialog.dismiss();
								}
							});
					builder.show();
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
				// nothing to do
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
				// nothing to do
			}
		});

		return new int[]{ initialBrightnessMode, initialBrightnessLevel };
	}

	private void setBrightness(int mode, int level) {
		ContentResolver contentResolver = getContentResolver();
		Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, mode);
		Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, level);
	}

	private void checkNotificationServiceAccess() {
		if (!NotificationMonitorService.isEnabled(CameraActivity.this)) {
			AlertDialog.Builder builder = new AlertDialog.Builder(CameraActivity.this);
			builder.setTitle(R.string.hint_notification_access)
					.setMessage(R.string.hint_enable_notification_service)
					.setOnCancelListener(new DialogInterface.OnCancelListener() {
						@Override
						public void onCancel(DialogInterface dialog) {
							Toast.makeText(CameraActivity.this, R.string.error_accessing_notifications, Toast
									.LENGTH_LONG)
									.show();
							finish();
						}
					})
					.setPositiveButton(R.string.hint_edit_notification_access, new DialogInterface.OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							try {
								// note: can use Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS after API 22
								startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
							} catch (ActivityNotFoundException e) {
								Toast.makeText(CameraActivity.this, R.string.error_accessing_notifications, Toast
										.LENGTH_LONG)
										.show();
								finish();
							}
							dialog.dismiss();
						}
					});
			builder.show();
		}
	}

	@Override
	public void onBackPressed() {
		switch (mCurrentMode) {
			case CAMERA:
				super.onBackPressed();
				break;

			case EVENTS:
				LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(CameraActivity.this);
				Intent stopNotificationsIntent = new Intent(MonitorManager.STOP_MANAGING_NOTIFICATIONS);
				localBroadcastManager.sendBroadcast(stopNotificationsIntent);

				resetNotificationBitmaps();

				if (mCurrentNotificationDialog != null) {
					try {
						mCurrentNotificationDialog.dismiss();
					} catch (Throwable ignored) {
					}
				}

				// reset views
				mFacebookNotificationCount = 0;
				mSMSNotificationCount = 0;
				mWhatsAppNotificationCount = 0;
				mFacebookNotificationTitle = null;
				mFacebookNotificationMessage = null;
				mFacebookPendingIntent = null;
				mSMSNotificationTitle = null;
				mSMSNotificationMessage = null;
				mSMSPendingIntent = null;
				mWhatsAppNotificationTitle = null;
				mWhatsAppNotificationMessage = null;
				mWhatsAppPendingIntent = null;
				setupColourButtons();

				mEnableColourPickerButton.setImageResource(R.drawable.ic_opacity_white_48dp);
				positionNotificationButtons(mButtonStartPosition);
				mZoomableImageView.setIsColourPickerTouching(false);
				mZoomableImageView.setZoomEnabled(true);
				mZoomableImageView.setPanEnabled(true);

				if (mCamera == null || mIsUsingFrontCamera) {
					releaseCamera(); // to switch back from front camera
					setupCamera(false);
				}

				if (mCamera != null) {
					// reset images and take another photo
					try {
						if (!mIsPreviewing) {
							mCamera.startPreview();
							mCamera.autoFocus(mAutoFocusCallback);
							mIsPreviewing = true;
						}
					} catch (RuntimeException e) {
						// if they manage to click back and take a photo at roughly the same time, choose photo option
						return;
					}

					mOriginalImage = null;
					for (int i = 0, n = mNotificationImages.length; i < n; i++) {
						mNotificationImages[i] = null;
					}
					mToggleImageHighlightHandler.removeCallbacks(mAnimateImageTransitionOnRunnable);
					mToggleImageHighlightHandler.removeCallbacks(mAnimateImageTransitionOffRunnable);
					mToggleImageHighlightHandler.removeCallbacks(mResetImageTransitionRunnable);

					// reset UI
					mImageCustomisationControls.setVisibility(View.GONE);
					mZoomableImageView.setVisibility(View.INVISIBLE);
					mHighlightImageView.setVisibility(View.INVISIBLE);
					mTakePhotoButton.setVisibility(View.VISIBLE);
					ActionBar actionBar = getSupportActionBar();
					if (actionBar != null) {
						actionBar.show();
					}

					mCurrentMode = Mode.CAMERA;
				}
				break;
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	private void setupColourButtons() {
		int defaultColour = getResources().getColor(R.color.default_object_highlight);
		mFacebookColour = defaultColour;
		mSmsColour = defaultColour;
		mWhatsAppColour = defaultColour;

		mFacebookButton = findViewById(R.id.facebook_button);
		mFacebookButton.setColour(mFacebookColour);
		mFacebookButton.setOnTouchListener(mDelayHideTouchListener);
		mFacebookButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				mFacebookColour = onColourButtonClick(mFacebookButton, 0);
			}
		});
		mSmsButton = findViewById(R.id.sms_button);
		mSmsButton.setColour(mSmsColour);
		mSmsButton.setOnTouchListener(mDelayHideTouchListener);
		mSmsButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				mSmsColour = onColourButtonClick(mSmsButton, 1);
			}
		});
		mWhatsAppButton = findViewById(R.id.whatsapp_button);
		mWhatsAppButton.setColour(mWhatsAppColour);
		mWhatsAppButton.setOnTouchListener(mDelayHideTouchListener);
		mWhatsAppButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				mWhatsAppColour = onColourButtonClick(mWhatsAppButton, 2);
			}
		});
	}

	private int onColourButtonClick(CircleImageButton button, int id) {
		int newColour = mZoomableImageView.getTouchColour();
		setupNotificationBitmap(newColour, id);
		button.setColour(newColour);
		animateNotificationButtons(-1, button);
		mZoomableImageView.setIsColourPickerTouching(false);
		showAndHideSystemUI(AUTO_HIDE_DELAY_MILLIS);
		return newColour;
	}

	private void showAndHideSystemUI(int delayMilliseconds) {
		showSystemUI();
		hideSystemUI(delayMilliseconds);
	}

	private void hideSystemUI(int delayMilliseconds) {
		// hide system UI - remove the status and navigation bar
		//if (delayMilliseconds > 0) {
		//	showSystemUI();
		//}
		mHideHandler.removeCallbacks(mHideRunnable);
		mHideHandler.postDelayed(mHideRunnable, delayMilliseconds);
	}

	private final Runnable mHideRunnable = new Runnable() {
		@Override
		public void run() {
			// delayed removal of status and navigation bar
			mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN |
					View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
					View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
				mContentView.setSystemUiVisibility(
						mContentView.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
			}
			if (mZoomableImageView.getIsColourPickerTouching()) { // also hide colour buttons
				positionNotificationButtons(mButtonStartPosition);
				mZoomableImageView.setIsColourPickerTouching(false);
			}
			if (mCurrentNotificationDialog != null) {
				try {
					mCurrentNotificationDialog.dismiss();
				} catch (Throwable ignored) {
				}
			}
			mImageCustomisationControls.setVisibility(View.GONE);
		}
	};

	private void showSystemUI() {
		mImageCustomisationControls.setVisibility(View.VISIBLE);
		mContentView.setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
						View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			mContentView.setSystemUiVisibility(
					mContentView.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putBoolean("mHasRequestedCameraPermission", mHasRequestedCameraPermission);
		outState.putInt("mInitialBrightnessMode", mInitialBrightnessMode);
		outState.putInt("mInitialBrightnessLevel", mInitialBrightnessLevel);
		super.onSaveInstanceState(outState);
	}

	private boolean checkCameraPermissions() {
		if (ContextCompat.checkSelfPermission(CameraActivity.this, Manifest.permission.CAMERA) !=
				PackageManager.PERMISSION_GRANTED) {

			// check whether they've previously denied the permission
			SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(CameraActivity.this);
			int permissionDenied = preferences.getInt(getString(R.string.pref_camera_permission_denied), 0);

			// so we can limit permission checks to a small number - note that two checks are made due to onResume
			// being called when we display the permission request box (increase this number to ask more times)
			if (permissionDenied >= 2) {
				// show the prompt to edit settings and grant the permission
				//noinspection ConstantConditions (for findViewById null warning)
				findViewById(R.id.scan_permissions_allow_hint).setVisibility(View.VISIBLE);
				mHasRequestedCameraPermission = true;
				return false;

			} else {
				if (ActivityCompat.shouldShowRequestPermissionRationale(CameraActivity.this, Manifest.permission
						.CAMERA)) {
					// permission denied without ticking "never again" button - show the explanation, and request
					// permissions when they click the button
					findViewById(R.id.scan_permissions_request_hint).setVisibility(View.VISIBLE);

				} else {
					// normal request with no explanation given
					ActivityCompat.requestPermissions(CameraActivity.this, new String[]{ Manifest.permission.CAMERA },
							CAMERA_PERMISSION_RESULT);
				}
			}

		} else {
			// permission granted - record this so we don't skip checking if the preference ever changes
			SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(CameraActivity.this);
			SharedPreferences.Editor editor = preferences.edit();
			editor.putInt(getString(R.string.pref_camera_permission_denied), 0);
			editor.apply();

			// make sure both hints are hidden
			findViewById(R.id.scan_permissions_request_hint).setVisibility(View.GONE);
			findViewById(R.id.scan_permissions_allow_hint).setVisibility(View.GONE);
		}

		return true; // we can continue with this activity - permission might be granted
	}

	public void handleClick(View view) {
		switch (view.getId()) {
			case R.id.request_camera_permission:
				mHasRequestedCameraPermission = true;
				ActivityCompat.requestPermissions(CameraActivity.this, new String[]{ Manifest.permission.CAMERA },
						CAMERA_PERMISSION_RESULT);
				break;

			case R.id.edit_app_permissions:
				Intent intent = new Intent();
				intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
				Uri uri = Uri.fromParts("package", CameraActivity.this.getPackageName(), null);
				intent.setData(uri);
				startActivity(intent);
				break;

			case R.id.camera_take_photo: // take a photo
			case R.id.camera_preview:
				if (mCamera != null && mCurrentMode == Mode.CAMERA) {
					mCurrentMode = Mode.EVENTS;
					mCamera.setOneShotPreviewCallback(mPreviewFrameCallback);
					mIsPreviewing = false;

					// mZoomableImageView.setImage(ImageSource.resource(android.R.color.white));
					ActionBar actionBar = getSupportActionBar();
					if (actionBar != null) {
						actionBar.hide();
					}
					mTakePhotoButton.setVisibility(View.GONE);
				}
				break;

			case R.id.image_enable_colour_picker:
				ImageViewState state = mZoomableImageView.getState();
				// TODO: this is simply to cause an event in mImageEventListener if they click this button before
				// TODO: loading has finished; otherwise, the image can be null and we crash
				SubsamplingScaleImageView.AnimationBuilder builder = mZoomableImageView.animateCenter
						(mZoomableImageView
						.getCenter());
				if (builder != null) {
					builder.start();
				} else {
					// TODO: can we do anything?
					Log.d(TAG, "Unable to start colour picker reset animation");
				}
				boolean zoomEnabled = mZoomableImageView.isZoomEnabled();
				if (zoomEnabled) {
					mEnableColourPickerButton.setImageResource(R.drawable.ic_opacity_yellow_a700_48dp);
				} else {
					mEnableColourPickerButton.setImageResource(R.drawable.ic_opacity_white_48dp);
					positionNotificationButtons(mButtonStartPosition); // just used to hide the buttons
					mZoomableImageView.setIsColourPickerTouching(false);
				}
				mZoomableImageView.setZoomEnabled(!zoomEnabled);
				mZoomableImageView.setPanEnabled(!zoomEnabled);
				if (state != null) {
					mZoomableImageView.setScaleAndCenter(state.getScale(), state.getCenter());
				}
				break;

			case R.id.notification_display_facebook:
				Log.d(TAG, "Launching Facebook");
				if (mFacebookPendingIntent != null) {
					try {
						mFacebookPendingIntent.send();
					} catch (PendingIntent.CanceledException ignored) {
					}
				}
				break;

			case R.id.notification_display_sms:
				Log.d(TAG, "Launching SMS");
				if (mSMSPendingIntent != null) {
					try {
						mSMSPendingIntent.send();
					} catch (PendingIntent.CanceledException ignored) {
					}
				}
				break;

			case R.id.notification_display_whatsapp:
				Log.d(TAG, "Launching WhatsApp");
				if (mWhatsAppPendingIntent != null) {
					try {
						mWhatsAppPendingIntent.send();
					} catch (PendingIntent.CanceledException ignored) {
					}
				}
				break;

			default:
				break;
		}
	}

	private final Camera.PreviewCallback mPreviewFrameCallback = new Camera.PreviewCallback() {
		@Override
		public void onPreviewFrame(byte[] imageData, Camera camera) {
			Camera.Parameters parameters = camera.getParameters();
			Camera.Size size = parameters.getPreviewSize();
			int format = parameters.getPreviewFormat();
			new SavePreviewFrameTask(mImageCacheFile, size, format).execute(imageData);
		}
	};

	private class SavePreviewFrameTask extends AsyncTask<byte[], Void, Boolean> {

		private final File mOutputFile;
		private final Camera.Size mPreviewSize;
		private final int mFormat;

		SavePreviewFrameTask(File outputFile, Camera.Size size, int format) {
			mOutputFile = outputFile;
			mPreviewSize = size;
			mFormat = format;
		}

		@Override
		protected Boolean doInBackground(byte[]... params) {
			byte[] data = params[0];
			if (data == null) {
				return Boolean.FALSE; // can't do anything
			}

			FileOutputStream out = null;
			Bitmap bitmap = null;
			try {
				out = new FileOutputStream(mOutputFile);
				switch (mFormat) {
					case ImageFormat.NV21:
					case ImageFormat.YUY2:
						saveYUVToJPEG(mPreviewSize, mFormat, out, data);
						break;
					case ImageFormat.JPEG:
						out.write(data); // directly write JPEG to storage
						break;
					default:
						// try to compress from byte array
						bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
						if (bitmap != null) {
							bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
						} else {
							return Boolean.FALSE;
						}
						break;
				}
			} catch (RuntimeException ignored) {
				return Boolean.FALSE;
			} catch (Exception ignored) {
				return Boolean.FALSE;
			} finally {
				if (out != null) {
					try {
						out.close();
					} catch (IOException ignored) {
					}
				}
				if (bitmap != null) {
					bitmap.recycle();
				}
			}

			return Boolean.TRUE;
		}

		@Override
		protected void onPostExecute(Boolean result) {
			if (result) {
				// try to correct for rotated images
				Point screenSize = new Point();
				getWindowManager().getDefaultDisplay().getSize(screenSize);
				float screenAspectRatio = (float) screenSize.x / (float) screenSize.y;
				BitmapFactory.Options imageOptions = getImageDimensions(mImageCacheFile);
				int width = imageOptions.outWidth;
				int height = imageOptions.outHeight;
				float imageAspectRatio = (float) width / (float) height;
				if (Math.abs(screenAspectRatio - imageAspectRatio) > 0.5) {
					mZoomableImageView.setOrientation(SubsamplingScaleImageView.ORIENTATION_90);
				}

				mZoomableImageView.setOnImageEventListener(mImageEventListener);
				mZoomableImageView.setImage(ImageSource.uri(Uri.fromFile(mImageCacheFile)).tilingDisabled());

				mZoomableImageView.setVisibility(View.VISIBLE);
				mImageCustomisationControls.setVisibility(View.VISIBLE);

				releaseCamera();
				setupCamera(true); // front camera for movement detection

				LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(CameraActivity.this);
				Intent startNotificationsIntent = new Intent(MonitorManager.START_MANAGING_NOTIFICATIONS);
				localBroadcastManager.sendBroadcast(startNotificationsIntent);
			} else {
				Log.d(TAG, "Failed to take photo");
			}
		}
	}

	//see: http://stackoverflow.com/a/6698382
	private void saveYUVToJPEG(Camera.Size previewSize, int previewImageFormat, FileOutputStream out, byte[] data) {
		int width = previewSize.width;
		int height = previewSize.height;

		Rect rect = new Rect();
		rect.left = 0;
		rect.top = 0;
		rect.right = width - 1;
		rect.bottom = height - 1; // -1 is required, otherwise a buffer overrun occurs
		YuvImage yuvimg = new YuvImage(data, previewImageFormat, width, height, null);
		yuvimg.compressToJpeg(rect, 90, out);
	}

	private final SubsamplingScaleImageView.OnImageEventListener mImageEventListener = new SubsamplingScaleImageView
			.OnImageEventListener() {
		@Override
		public void onReady() {
		}

		@Override
		public void onImageLoaded() {
			mZoomableImageView.animateScale(5.5f)
					.withDuration(1500)
					.withEasing(SubsamplingScaleImageView.EASE_IN_OUT_QUAD)
					.withInterruptible(true)
					.withOnAnimationEventListener(new SubsamplingScaleImageView.OnAnimationEventListener() {
						@Override
						public void onComplete() {
							updateBitmapColours();
						}

						@Override
						public void onInterruptedByUser() {
							updateBitmapColours();
						}

						@Override
						public void onInterruptedByNewAnim() {
							// TODO: do we need to handle this?
						}
					})
					.start();
		}

		@Override
		public void onPreviewLoadError(Exception e) {
		}

		@Override
		public void onImageLoadError(Exception e) {
		}

		@Override
		public void onTileLoadError(Exception e) {
		}

		@Override
		public void onZoomOrPanCompleted() {
			updateBitmapColours();
		}
	};

	private void updateBitmapColours() {
		resetNotificationBitmaps();
		mZoomableImageView.getStateBitmap(new SubsamplingScaleImageView.StateBitmapCallback() {
			@Override
			public void bitmapReady(Bitmap bitmap) {
				if (bitmap == null) {
					return;
				}

				boolean loadBitmapColours = false;
				if (mOriginalImage == null) {
					loadBitmapColours = true;
				}

				mOriginalImage = bitmap;

				// only auto-load colours on the first zoom
				if (loadBitmapColours) {
					int[] dominantColours = getDominantColours(mOriginalImage, 3);
					mFacebookColour = dominantColours[0] != 0 ? dominantColours[0] : mFacebookColour;
					mSmsColour = dominantColours[1] != 0 ? dominantColours[1] : mSmsColour;
					mWhatsAppColour = dominantColours[2] != 0 ? dominantColours[2] : mWhatsAppColour;
				}

				setupNotificationBitmap(mFacebookColour, 0);
				mFacebookButton.setColour(mFacebookColour);

				setupNotificationBitmap(mSmsColour, 1);
				mSmsButton.setColour(mSmsColour);

				setupNotificationBitmap(mWhatsAppColour, 2);
				mWhatsAppButton.setColour(mWhatsAppColour);

				showAndHideSystemUI(AUTO_HIDE_DELAY_MILLIS);
			}
		});
	}

	private void resetNotificationBitmaps() {
		for (ImageAnalyserTask mImageAnalyserTask : mImageAnalyserTasks) {
			if (mImageAnalyserTask != null) {
				mImageAnalyserTask.cancel(true);
			}
		}
		for (int i = 0; i < mNotificationImages.length; i++) {
			mNotificationImages[i] = null;
		}
	}

	private BitmapFactory.Options getImageDimensions(File image) {
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(image.getAbsolutePath(), options);
		return options;
	}

	private final Camera.PreviewCallback mPreviewCallback = new Camera.PreviewCallback() {
		@Override
		public void onPreviewFrame(byte[] data, Camera camera) {
			// only analyse motion if we actually have events
			if (!getIsInMotion() && mFacebookNotificationCount > 0 || mSMSNotificationCount > 0 ||
					mWhatsAppNotificationCount > 0) {
				Camera.Size size = camera.getParameters().getPreviewSize();
				new DetectionTask(data, size.width, size.height).execute();
			}
		}
	};

	private void setupCamera(boolean preferFront) {
		CameraUtilities.CameraConfiguration cameraConfiguration = new CameraUtilities.CameraConfiguration();
		mCamera = CameraUtilities.initialiseCamera(preferFront, cameraConfiguration);
		if (mCamera != null) {
			int screenRotation = CameraUtilities.getScreenRotationDegrees(getWindowManager());
			int displayOrientation = CameraUtilities.getPreviewOrientationDegrees(screenRotation, cameraConfiguration
					.cameraOrientationDegrees, cameraConfiguration.usingFrontCamera);
			mPreviewFrame.addView(new CameraSurfaceView(CameraActivity.this, getWindowManager().getDefaultDisplay(),
					mCamera, displayOrientation, cameraConfiguration.cameraOrientationDegrees, mAutoFocusCallback,
					cameraConfiguration.usingFrontCamera ? mPreviewCallback : null, cameraConfiguration
					.usingFrontCamera));
			mIsUsingFrontCamera = cameraConfiguration.usingFrontCamera; // TODO: could end up motion detecting rear cam
			mIsPreviewing = true;
		}
	}

	private void releaseCamera() {
		mIsPreviewing = false;
		mAutoFocusHandler.removeCallbacks(mAutoFocusRunnable);
		if (mCamera != null) {
			try {
				mCamera.cancelAutoFocus();
			} catch (RuntimeException e) { // have had app store reports of this causing a RuntimeException
			}
			mCamera.stopPreview();
			mCamera.setPreviewCallback(null);
			try {
				mCamera.setPreviewDisplay(null);
			} catch (Throwable ignored) {
			}
			mCamera.release();
			mCamera = null;
		}
		mPreviewFrame.removeAllViews();
	}

	private final Runnable mAutoFocusRunnable = new Runnable() {
		@Override
		public void run() {
			if (mIsPreviewing) {
				try {
					mCamera.autoFocus(mAutoFocusCallback);
				} catch (RuntimeException ignored) {
				}
			}
		}
	};

	// simulate continuous auto-focus
	private final Handler mAutoFocusHandler = new Handler();
	private final AutoFocusCallback mAutoFocusCallback = new AutoFocusCallback() {
		@Override
		public void onAutoFocus(boolean success, Camera camera) {
			mAutoFocusHandler.removeCallbacks(mAutoFocusRunnable);
			mAutoFocusHandler.postDelayed(mAutoFocusRunnable, AUTO_FOCUS_INTERVAL);
		}
	};

	private int[] getDominantColours(Bitmap bitmap, int numColours) {
		if (bitmap == null) {
			throw new NullPointerException();
		}
		int[] returnColours = new int[numColours];

		Palette.Builder builder = new Palette.Builder(bitmap).maximumColorCount(numColours * 2);
		Palette colours = builder.generate();
		List<Palette.Swatch> swatches = colours.getSwatches();
		int i = 0;
		for (Palette.Swatch swatch : swatches) {
			returnColours[i] = swatch.getRgb();
			i += 1;
			if (i >= returnColours.length) {
				break;
			}
		}

		return returnColours;
	}

	private final View.OnTouchListener mColourPickerTouchListener = new View.OnTouchListener() {
		@Override
		public boolean onTouch(View view, MotionEvent motionEvent) {
			if (mFacebookNotificationCount > 0 || mSMSNotificationCount > 0 || mWhatsAppNotificationCount > 0) {
				// after notifications are received, don't allow further configuration - instead, show a popup
				// TODO: improve / rationalise
				switch (motionEvent.getAction()) {
					case MotionEvent.ACTION_DOWN:
						if (mCurrentNotificationDialog != null) {
							try {
								mCurrentNotificationDialog.dismiss();
							} catch (Throwable ignored) {
							}
						}

						// we can't attach to parent as it doesn't exist yet
						@SuppressLint("InflateParams") View dialog = getLayoutInflater().inflate(R.layout
								.layout_notification, null);
						if (dialog != null) {
							if (mFacebookNotificationCount > 0) {
								dialog.findViewById(R.id.notification_display_facebook).setVisibility(View.VISIBLE);
								if (mFacebookNotificationTitle != null) {
									((TextView) dialog.findViewById(R.id.notification_title_facebook)).setText
											(mFacebookNotificationTitle);
								}
								if (mFacebookNotificationMessage != null) {
									((TextView) dialog.findViewById(R.id.notification_message_facebook)).setText
											(mFacebookNotificationMessage);
								}
							}
							if (mSMSNotificationCount > 0) {
								dialog.findViewById(R.id.notification_display_sms).setVisibility(View.VISIBLE);
								if (mSMSNotificationTitle != null) {
									((TextView) dialog.findViewById(R.id.notification_title_sms)).setText
											(mSMSNotificationTitle);
								}
								if (mSMSNotificationMessage != null) {
									((TextView) dialog.findViewById(R.id.notification_message_sms)).setText
											(mSMSNotificationMessage);
								}
							}
							if (mWhatsAppNotificationCount > 0) {
								dialog.findViewById(R.id.notification_display_whatsapp).setVisibility(View.VISIBLE);
								if (mWhatsAppNotificationTitle != null) {
									((TextView) dialog.findViewById(R.id.notification_title_whatsapp)).setText
											(mWhatsAppNotificationTitle);
								}
								if (mWhatsAppNotificationMessage != null) {
									((TextView) dialog.findViewById(R.id.notification_message_whatsapp)).setText
											(mWhatsAppNotificationMessage);
								}
							}

							mCurrentNotificationDialog = new EasyDialog(CameraActivity.this).setLayout(dialog)
									.setLocation(new int[]{
											Math.round(motionEvent.getX()),
											Math.round(motionEvent.getY() -
													getResources().getDimension(R.dimen
															.notification_popup_vertical_offset))
									})
									.setBackgroundColor(CameraActivity.this.getResources()
											.getColor(R.color.notification_background))
									.setGravity(EasyDialog.GRAVITY_TOP)
									.setTouchOutsideDismiss(true)
									.setMatchParent(false)
									.show();
						}
						break;

					default:
						break;
				}
				hideSystemUI(AUTO_HIDE_DELAY_MILLIS);
				return true;

			} else {
				if (!mZoomableImageView.isZoomEnabled()) { // only show colour picker when in the right mode
					switch (motionEvent.getAction()) {
						case MotionEvent.ACTION_DOWN:
							mZoomableImageView.setIsColourPickerTouching(true);
							positionNotificationButtons(mButtonStartPosition); // just used to hide the buttons
							mImageCustomisationControls.setVisibility(View.VISIBLE);
							break;

						case MotionEvent.ACTION_UP:
							mButtonStartPosition.set(mZoomableImageView.getCorrectedTouchPosition());
							positionNotificationButtons(mButtonStartPosition);
							animateNotificationButtons(1, null);
							break;

						default:
							break;
					}
				}
				showAndHideSystemUI(AUTO_HIDE_DELAY_MILLIS); // hide buttons again if no interaction happens
				return false;
			}
		}
	};

	private final View.OnTouchListener mDelayHideTouchListener = new View.OnTouchListener() {
		@Override
		public boolean onTouch(View view, MotionEvent motionEvent) {
			if (mFacebookNotificationCount <= 0 && mSMSNotificationCount <= 0 && mWhatsAppNotificationCount <= 0) {
				showAndHideSystemUI(AUTO_HIDE_DELAY_MILLIS); // only when no notifications have been received
			}
			return false;
		}
	};

	private void setupNotificationBitmap(int colour, final int bitmapIndex) {
		if (mOriginalImage == null) {
			Log.d(TAG, "Image analysis error: no original image");
			return;
		}

		// get a new bitmap with the specified colour highlighted
		ImageAnalyserTask parserTask = new ImageAnalyserTask(mOriginalImage, colour, new ImageAnalyserTask
				.ImageAnalyserCallback() {
			@Override
			public void analysisFailed() {
				Log.d(TAG, "Image analysis error (or cancelled)");
			}

			@Override
			public void analysisSucceeded(Bitmap result) {
				Log.d(TAG, "Image processing completed for type " + bitmapIndex);
				mNotificationImages[bitmapIndex] = result;
			}
		});

		if (mImageAnalyserTasks[bitmapIndex] != null) {
			mImageAnalyserTasks[bitmapIndex].cancel(true);
		}
		mImageAnalyserTasks[bitmapIndex] = parserTask;
		parserTask.execute();
	}

	private void positionNotificationButtons(PointF centrePoint) {
		int parentLeft = mZoomableImageView.getLeft();
		int parentTop = mZoomableImageView.getTop();
		int parentRight = mZoomableImageView.getRight();
		int parentBottom = mZoomableImageView.getBottom();

		// position the control buttons in anticipation of touch completion
		RelativeLayout.LayoutParams controlLayoutParams = getLayoutParamsForButtonPosition(centrePoint,
				mFacebookButton.getWidth(), mFacebookButton
				.getHeight(), parentLeft, parentTop, parentRight, parentBottom);
		mFacebookButton.setLayoutParams(controlLayoutParams);
		mSmsButton.setLayoutParams(controlLayoutParams);
		mWhatsAppButton.setLayoutParams(controlLayoutParams);

		mFacebookButton.setVisibility(View.INVISIBLE);
		mSmsButton.setVisibility(View.INVISIBLE);
		mWhatsAppButton.setVisibility(View.INVISIBLE);
	}

	private RelativeLayout.LayoutParams getLayoutParamsForButtonPosition(PointF buttonPosition, int buttonWidth, int
			buttonHeight, int parentLeft, int parentTop, int parentRight, int parentBottom) {
		RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(buttonWidth, buttonHeight);
		layoutParams.leftMargin = parentLeft + Math.round(buttonPosition.x - (buttonWidth / 2f));
		layoutParams.topMargin = parentTop + Math.round(buttonPosition.y - (buttonHeight / 2f));

		// need to set negative margins for when the button is close to the edges (its size would be changed otherwise)
		int rightPosition = layoutParams.leftMargin + buttonWidth;
		if (rightPosition > parentRight) {
			layoutParams.rightMargin = parentRight - rightPosition;
		}
		int bottomPosition = layoutParams.topMargin + buttonHeight;
		if (bottomPosition > parentBottom) {
			layoutParams.bottomMargin = parentBottom - bottomPosition;
		}
		return layoutParams;
	}

	private void animateNotificationButtons(int direction, View keyView) {
		mFacebookButton.setVisibility(View.VISIBLE);
		mSmsButton.setVisibility(View.VISIBLE);
		mWhatsAppButton.setVisibility(View.VISIBLE);

		// animate the control buttons out to be equally spaced around the record button
		float buttonOffset = -mFacebookButton.getWidth();
		PointF centre = new PointF(0, 0);
		PointF startingPoint = new PointF(0, buttonOffset);
		double radRot = Math.toRadians(-120);
		double cosRot = Math.cos(radRot);
		double sinRot = Math.sin(radRot);
		rotatePoint(startingPoint, centre, cosRot, sinRot);
		float leftX = startingPoint.x;
		float leftY = startingPoint.y;
		rotatePoint(startingPoint, centre, cosRot, sinRot);
		float rightX = startingPoint.x;
		float rightY = startingPoint.y;

		RelativeLayout parent;
		if (mButtonAnimator != null) {
			mButtonAnimator.cancel(); // stop previous animations
		}
		mButtonHideHandler.removeCallbacks(mButtonHideRunnable);
		mButtonAnimator = new AnimatorSet();
		switch (direction) {
			case 1: // out
				// on an outward animation, we want the three minor buttons to have priority so the record button's
				// larger click area doesn't capture their clicks
				mFacebookButton.bringToFront();
				mSmsButton.bringToFront();
				mWhatsAppButton.bringToFront();
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
					// need to manually re-layout before KitKat
					parent = (RelativeLayout) mFacebookButton.getParent();
					parent.requestLayout();
					parent.invalidate();
				}

				mButtonAnimator.playTogether(ObjectAnimator.ofFloat(mSmsButton, "translationX", 0, leftX),
						ObjectAnimator
						.ofFloat(mSmsButton, "translationY", 0, leftY), ObjectAnimator.ofFloat(mWhatsAppButton,
								"translationX", 0, rightX), ObjectAnimator
						.ofFloat(mWhatsAppButton, "translationY", 0, rightY), ObjectAnimator.ofFloat(mFacebookButton,
								"translationY", 0, buttonOffset));
				mButtonAnimator.setInterpolator(new OvershootInterpolator());
				showAndHideSystemUI(AUTO_HIDE_DELAY_MILLIS); // hide buttons again if no interaction happens
				break;

			case -1: // in
				// keyView is the view we want to be at the front after an inward animation
				if (keyView != null) {
					keyView.bringToFront();
					if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
						// need to manually re-layout before KitKat
						parent = (RelativeLayout) keyView.getParent();
						parent.requestLayout();
						parent.invalidate();
					}
				}

				mButtonAnimator.playTogether(ObjectAnimator.ofFloat(mSmsButton, "translationX", leftX, 0),
						ObjectAnimator
						.ofFloat(mSmsButton, "translationY", leftY, 0), ObjectAnimator.ofFloat(mWhatsAppButton,
								"translationX", rightX, 0), ObjectAnimator
						.ofFloat(mWhatsAppButton, "translationY", rightY, 0), ObjectAnimator.ofFloat(mFacebookButton,
								"translationY", buttonOffset, 0));
				mButtonAnimator.setInterpolator(new AnticipateInterpolator());

				// show briefly in final position, then hide
				mButtonHideHandler.postDelayed(mButtonHideRunnable, BUTTON_ANIMATION_DURATION * 3);
				break;

			default:
				return;
		}
		mButtonAnimator.setDuration(BUTTON_ANIMATION_DURATION);
		mButtonAnimator.start();
	}

	private final Runnable mButtonHideRunnable = new Runnable() {
		@Override
		public void run() {
			mFacebookButton.setVisibility(View.INVISIBLE);
			mSmsButton.setVisibility(View.INVISIBLE);
			mWhatsAppButton.setVisibility(View.INVISIBLE);
		}
	};

	private static void rotatePoint(PointF point, PointF centre, double cosT, double sinT) {
		float newX = point.x - centre.x;
		float newY = point.y - centre.y;
		point.x = (float) (cosT * newX - sinT * newY) + centre.x;
		point.y = (float) (sinT * newX + cosT * newY) + centre.y;
	}

	private void showNotification(int id) {
		if (mCurrentNotificationDialog != null) {
			try {
				if (mCurrentNotificationDialog.getDialog().isShowing()) {
					return; // don't show new notifications while we're trying to display the dialog for a current one
				}
			} catch (Throwable ignored) {
			}
		}

		hideSystemUI(0);

		if (mNotificationImages[id] != null) {
			Log.d(TAG, "Showing notification for type " + id);
			Resources resources = getResources();
			mTransitionDrawable = new TransitionDrawable(new Drawable[]{
					new BitmapDrawable(resources, mOriginalImage),
					new BitmapDrawable(resources, mNotificationImages[id])
			});
			mHighlightImageView.setVisibility(View.VISIBLE);
			mHighlightImageView.setImageDrawable(mTransitionDrawable);
			mTransitionDrawable.startTransition(IMAGE_ANIMATION_DURATION);
			mTransitionRepeatCount = 0;
			mToggleImageHighlightHandler.removeCallbacks(mAnimateImageTransitionOnRunnable);
			mToggleImageHighlightHandler.removeCallbacks(mAnimateImageTransitionOffRunnable);
			mToggleImageHighlightHandler.removeCallbacks(mResetImageTransitionRunnable);
			mToggleImageHighlightHandler.postDelayed(mAnimateImageTransitionOffRunnable, Math.round(
					IMAGE_ANIMATION_DURATION * IMAGE_ANIMATION_SCALE));
		}
	}

	private final Runnable mAnimateImageTransitionOffRunnable = new Runnable() {
		@Override
		public void run() {
			Log.d("blah", "animating off");
			mTransitionDrawable.reverseTransition(IMAGE_ANIMATION_DURATION);

			mToggleImageHighlightHandler.removeCallbacks(mAnimateImageTransitionOnRunnable);
			mToggleImageHighlightHandler.removeCallbacks(mAnimateImageTransitionOffRunnable);
			if (mTransitionRepeatCount < EVENT_REPEAT_COUNT) { // TODO: improve; extract to preferences
				mToggleImageHighlightHandler.postDelayed(mAnimateImageTransitionOnRunnable, Math.round(
						IMAGE_ANIMATION_DURATION * IMAGE_ANIMATION_SCALE));
				mToggleImageHighlightHandler.postDelayed(mAnimateImageTransitionOffRunnable, Math.round(
						2 * (IMAGE_ANIMATION_DURATION * IMAGE_ANIMATION_SCALE)));
				mTransitionRepeatCount += 1;
			} else {
				mTransitionRepeatCount = 0;
				mToggleImageHighlightHandler.postDelayed(mResetImageTransitionRunnable, Math.round(
						IMAGE_ANIMATION_DURATION * IMAGE_ANIMATION_SCALE));
			}
		}
	};

	private final Runnable mAnimateImageTransitionOnRunnable = new Runnable() {
		@Override
		public void run() {
			mTransitionDrawable.startTransition(IMAGE_ANIMATION_DURATION);
		}
	};

	private final Runnable mResetImageTransitionRunnable = new Runnable() {
		@Override
		public void run() {
			mHighlightImageView.setVisibility(View.INVISIBLE);
		}
	};

	private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action == null) {
				return;
			}

			Bundle extras = intent.getExtras();
			String notificationTitle = null;
			String notificationMessage = null;
			PendingIntent notificationIntent = null;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && extras != null) {
				Bundle notificationExtras = extras.getBundle(MonitorManager.NOTIFICATION_EXTRAS);
				if (notificationExtras != null) {
					notificationTitle = notificationExtras.getString(Notification.EXTRA_TITLE);
					CharSequence message = notificationExtras.getCharSequence(Notification.EXTRA_TEXT);
					if (message != null) {
						notificationMessage = message.toString();
					}
				}
				notificationIntent = extras.getParcelable(MonitorManager.NOTIFICATION_INTENT);
			}

			switch (action) {
				case MonitorManager.TYPE_FACEBOOK:
					mFacebookNotificationCount += 1;
					mFacebookNotificationTitle = notificationTitle;
					mFacebookNotificationMessage = notificationMessage;
					mFacebookPendingIntent = notificationIntent;
					showNotification(0);
					// Log.d(TAG, "Facebook event received");
					break;

				case MonitorManager.TYPE_SMS:
					mSMSNotificationCount += 1;
					mSMSNotificationTitle = notificationTitle;
					mSMSNotificationMessage = notificationMessage;
					mSMSPendingIntent = notificationIntent;
					showNotification(1);
					// Log.d(TAG, "SMS event received");
					break;

				case MonitorManager.TYPE_WHATSAPP:
					mWhatsAppNotificationCount += 1;
					mWhatsAppNotificationTitle = notificationTitle;
					mWhatsAppNotificationMessage = notificationMessage;
					mWhatsAppPendingIntent = notificationIntent;
					showNotification(2);
					// Log.d(TAG, "WhatsApp event received");
					break;

				default:
					break;
			}
		}
	};

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[]
			grantResults) {
		switch (requestCode) {
			case CAMERA_PERMISSION_RESULT:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					// permission was granted
					findViewById(R.id.scan_permissions_request_hint).setVisibility(View.GONE);
					findViewById(R.id.scan_permissions_allow_hint).setVisibility(View.GONE);
					mTakePhotoButton.setVisibility(View.VISIBLE);

				} else {
					// permission denied - record this so we can skip in future
					SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(CameraActivity.this);
					SharedPreferences.Editor editor = preferences.edit();
					int permissionDenied = preferences.getInt(getString(R.string.pref_camera_permission_denied), 0);
					editor.putInt(getString(R.string.pref_camera_permission_denied), permissionDenied + 1);
					editor.apply();

					// they asked to be asked again and then denied - hide previous message
					if (mHasRequestedCameraPermission) {
						findViewById(R.id.scan_permissions_request_hint).setVisibility(View.GONE);
					}
				}
				break;

			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
				break;
		}
	}

	private class DetectionTask extends AsyncTask<Void, Void, Boolean> {

		private final byte[] mData;
		private final int mWidth;
		private final int mHeight;

		DetectionTask(byte[] data, int width, int height) {
			this.mData = data;
			this.mWidth = width;
			this.mHeight = height;
		}

		@Override
		protected Boolean doInBackground(Void... params) {
			if (!sIsProcessingMotionDetection.compareAndSet(false, true)) {
				return Boolean.FALSE;
			}
			try {
				// avoid analysing frames multiple times - 2.5 second delay between motion events
				int[] img = ImageProcessing.decodeYUV420SPtoRGB(mData, mWidth, mHeight);
				if (img != null && sMotionDetector.detect(img, mWidth, mHeight)) {
					// TODO: improve (to save battery)
					long now = System.currentTimeMillis();
					if (now > (sMotionDetectionReferenceTime + 2500)) {
						sMotionDetectionReferenceTime = now;
						return Boolean.TRUE;
					}
				}
			} catch (Exception ignored) {
			} finally {
				sIsProcessingMotionDetection.set(false);
			}
			return Boolean.FALSE;
		}

		@Override
		protected void onPostExecute(Boolean motionDetected) {
			if (motionDetected) {
				// Log.d(TAG, "Camera motion detected");
				// TODO: handle concurrent notifications better - this is a hacky way to stop them overlapping
				if (mFacebookNotificationCount > 0) {
					showNotification(0);
				}
				if (mSMSNotificationCount > 0) {
					if (mFacebookNotificationCount > 0) {
						new Handler().postDelayed(new Runnable() {
							@Override
							public void run() {
								showNotification(1);
							}
						}, Math.round(IMAGE_ANIMATION_DURATION * IMAGE_ANIMATION_SCALE));
					} else {
						showNotification(1);
					}
				}
				if (mWhatsAppNotificationCount > 0) {
					if (mFacebookNotificationCount > 0 && mSMSNotificationCount > 0) {
						new Handler().postDelayed(new Runnable() {
							@Override
							public void run() {
								showNotification(2);
							}
						}, 2 * Math.round(IMAGE_ANIMATION_DURATION * IMAGE_ANIMATION_SCALE));
					} else if (mFacebookNotificationCount > 0 || mSMSNotificationCount > 0) {
						new Handler().postDelayed(new Runnable() {
							@Override
							public void run() {
								showNotification(2);
							}
						}, Math.round(IMAGE_ANIMATION_DURATION * IMAGE_ANIMATION_SCALE));
					} else {
						showNotification(2);
					}
				}
			}
		}
	}
}
