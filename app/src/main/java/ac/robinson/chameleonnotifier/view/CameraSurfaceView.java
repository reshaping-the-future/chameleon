package ac.robinson.chameleonnotifier.view;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class CameraSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

	private static final String TAG = "CameraSurfaceView";

	private static final int CAMERA_MIN_PREVIEW_PIXELS = 470 * 320; // normal screen
	private static final int CAMERA_MAX_PREVIEW_PIXELS = 1280 * 720;
	private static final float CAMERA_ASPECT_RATIO_TOLERANCE = 0.05f;

	private static final int PREVIEW_FORMAT = ImageFormat.NV21; // always supported on every Android device

	private SurfaceHolder mHolder;

	private Camera mCamera;
	private Point mScreenSize;

	private int mDisplayRotation;
	private int mCameraRotation;

	private Size mPreviewSize; // actual
	private List<Size> mSupportedPreviewSizes; // claimed supported
	private Size mDefaultPreviewSize; // device default

	private Size mPictureSize; // actual
	private List<Size> mSupportedPictureSizes; // claimed supported
	private Size mDefaultPictureSize; // device default

	private Camera.AutoFocusCallback mAutoFocusCallback;
	private Camera.PreviewCallback mPreviewCallback;

	private boolean mPreferSmallestPreviewSize;

	public CameraSurfaceView(Context context) {
		super(context); // this constructor is just to enable viewing in IDE tools
	}

	public CameraSurfaceView(Context context, Display display, Camera camera, int displayRotation, int cameraRotation,
							 Camera.AutoFocusCallback autoFocusCallback, Camera.PreviewCallback previewCallback,
							 boolean preferSmallestPreviewSize) {
		super(context);

		mScreenSize = new Point();
		display.getSize(mScreenSize);
		mCamera = camera;

		mDisplayRotation = displayRotation;
		mCameraRotation = cameraRotation;

		// set up the preview
		Camera.Parameters parameters = mCamera.getParameters();
		mSupportedPreviewSizes = parameters.getSupportedPreviewSizes();
		mDefaultPreviewSize = parameters.getPreviewSize();
		mSupportedPictureSizes = parameters.getSupportedPictureSizes();
		mDefaultPictureSize = parameters.getPictureSize();

		List<String> modes = parameters.getSupportedFlashModes();
		try {
			if (modes != null) {
				modes.remove(Camera.Parameters.FLASH_MODE_TORCH);
				int offMode = modes.indexOf(Camera.Parameters.FLASH_MODE_OFF);
				if (modes.size() > (offMode >= 0 ? 1 : 0)) {
					if (modes.contains(Camera.Parameters.FLASH_MODE_AUTO)) { // default to auto flash
						parameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
					}
				}
			}
		} catch (Exception ignored) {
		}

		mAutoFocusCallback = autoFocusCallback;
		mPreviewCallback = previewCallback;

		mPreferSmallestPreviewSize = preferSmallestPreviewSize;
		mCamera.setParameters(parameters);

		// register for create/destroy events
		mHolder = getHolder();
		mHolder.addCallback(this);
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		// purposely disregard child measurements - we want to centre the camera preview instead of stretching it
		final int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
		final int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);
		setMeasuredDimension(width, height);

		// we go fullscreen, so calculate the final size, rather than resizing the camera after initial launch
		int actualWidth, actualHeight;
		if (width == Math.max(width, height)) {
			actualWidth = Math.max(mScreenSize.x, mScreenSize.y);
			actualHeight = Math.min(mScreenSize.x, mScreenSize.y);
		} else {
			actualWidth = Math.min(mScreenSize.x, mScreenSize.y);
			actualHeight = Math.max(mScreenSize.x, mScreenSize.y);
		}

		if (mSupportedPreviewSizes != null) {
			mPreviewSize = getBestPreviewSize(mSupportedPreviewSizes, mDefaultPreviewSize, actualWidth, actualHeight,
					mPreferSmallestPreviewSize);
		}
		if (mSupportedPictureSizes != null) {
			mPictureSize = getBestPictureSize(mSupportedPictureSizes, mDefaultPictureSize, actualWidth, actualHeight);
		}
	}

	public void surfaceCreated(SurfaceHolder holder) {
		try {
			mCamera.setPreviewDisplay(holder);
		} catch (IOException e) {
			Log.d(TAG, "Error setting camera preview: " + e.getLocalizedMessage());
		}
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		// removed in activity
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		if (mHolder.getSurface() == null) {
			return; // we need the surface to exist before starting the preview
		}

		// must stop any existing preview before making changes
		try {
			mCamera.stopPreview();
		} catch (Exception ignored) {
		}

		try {
			// supported preview and picture sizes checked earlier
			Camera.Parameters parameters = mCamera.getParameters();
			parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
			parameters.setPreviewFormat(PREVIEW_FORMAT);
			parameters.setPictureSize(mPictureSize.width, mPictureSize.height);
			parameters.setRotation(mCameraRotation);
			mCamera.setDisplayOrientation(mDisplayRotation);
			mCamera.setParameters(parameters);

			// we use the buffered preview method, as unbuffered doesn't work on some newer devices
			// note: we use actual camera format, rather than PREVIEW_FORMAT - sometimes setPreviewFormat fails, and
			// it's better to fail to scan than to crash entirely due to a buffer being too small
			int previewBufferSize = mPreviewSize.width * mPreviewSize.height *
					ImageFormat.getBitsPerPixel(mCamera.getParameters().getPreviewFormat()) / 8;
			mCamera.addCallbackBuffer(new byte[previewBufferSize]);
			mCamera.addCallbackBuffer(new byte[previewBufferSize]); // need at least two for some devices
		} catch (Throwable ignored) {
		}

		try {
			if (mPreviewCallback != null) {
				mCamera.setPreviewCallback(mPreviewCallback);
			}
			mCamera.startPreview();
			mCamera.autoFocus(mAutoFocusCallback);
		} catch (Exception e) {
			Log.d(TAG, "Error starting camera preview: " + e.getMessage());
		}
	}

	private Size getBestPreviewSize(List<Size> allSizes, Size defaultSize, int screenWidth, int screenHeight,
									boolean preferSmallest) {
		if (allSizes == null) {
			return defaultSize;
		}
		List<Size> sortedSizes = sortSizes(allSizes);
		if (preferSmallest) {
			// TODO: improve this (check ratios etc). Currently just for front camera background motion detection, so
			// TODO: we want the smallest usable size (it is never displayed)
			Collections.reverse(sortedSizes);
			if (sortedSizes.size() > 0) {
				return sortedSizes.get(0);
			}
		}

		Size bestSize = null;
		float screenAspectRatio = Math.min(screenWidth, screenHeight) / (float) Math.max(screenWidth, screenHeight);
		float difference = Float.MAX_VALUE;
		for (Size s : sortedSizes) {
			int sizePixels = s.width * s.height;
			if (sizePixels < CAMERA_MIN_PREVIEW_PIXELS || sizePixels > CAMERA_MAX_PREVIEW_PIXELS) {
				continue;
			}
			boolean sizePortrait = s.width < s.height;
			boolean screenPortrait = screenWidth < screenHeight;
			int sizeWidth = (sizePortrait ^ screenPortrait) ? s.height : s.width; // xor
			int sizeHeight = (sizePortrait ^ screenPortrait) ? s.width : s.height;

			if (sizeWidth == screenWidth && sizeHeight == screenHeight) {
				return s; // perfect: exactly matches screen size
			}
			float sizeAspectRatio = Math.min(sizeWidth, sizeHeight) / (float) Math.max(sizeWidth, sizeHeight);
			float newDiff = Math.abs(sizeAspectRatio - screenAspectRatio);
			if (newDiff < difference) {
				bestSize = s;
				difference = newDiff;
			}
		}
		return bestSize == null ? defaultSize : bestSize;
	}

	private Size getBestPictureSize(List<Size> allSizes, Size defaultSize, int screenWidth, int screenHeight) {
		if (allSizes == null) {
			return defaultSize;
		}
		List<Size> sortedSizes = sortSizes(allSizes);

		Size bestSize = null;
		float difference = Float.MAX_VALUE;
		float initialWidth = 0;
		for (Size s : sortedSizes) {
			boolean sizePortrait = s.width < s.height;
			boolean screenPortrait = screenWidth < screenHeight;
			int sizeWidth = (sizePortrait ^ screenPortrait) ? s.height : s.width; // xor
			int sizeHeight = (sizePortrait ^ screenPortrait) ? s.width : s.height;

			// return the best of the initial set (TODO: consider other sets?)
			if (initialWidth <= 0) {
				initialWidth = Math.max(sizeWidth, sizeHeight);
			} else if (Math.max(sizeWidth, sizeHeight) != initialWidth && bestSize != null) {
				break;
			}

			float xAspectRatio = Math.min(screenWidth, sizeWidth) / (float) Math.max(screenWidth, sizeWidth);
			float yAspectRatio = Math.min(screenHeight, sizeHeight) / (float) Math.max(screenHeight, sizeHeight);
			float newDiff = Math.abs(xAspectRatio - yAspectRatio);
			if (newDiff < CAMERA_ASPECT_RATIO_TOLERANCE && newDiff < difference) {
				bestSize = s;
				difference = newDiff;
			}
		}
		return bestSize == null ? defaultSize : bestSize;
	}

	private List<Size> sortSizes(List<Size> allSizes) {
		// sort sizes in descending order
		Collections.sort(allSizes, new Comparator<Size>() {
			@Override
			public int compare(Size s1, Size s2) {
				int s1Resolution = s1.width * s1.height;
				int s2Resolution = s2.width * s2.height;
				if (s2Resolution < s1Resolution) {
					return -1;
				}
				return (s2Resolution > s1Resolution) ? 1 : 0;
			}
		});
		return allSizes;
	}
}
