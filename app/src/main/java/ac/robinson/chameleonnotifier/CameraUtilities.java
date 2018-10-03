/*
 *  Copyright (C) 2012 Simon Robinson
 *
 *  This file is part of Com-Me.
 *
 *  Com-Me is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU Lesser General Public License as
 *  published by the Free Software Foundation; either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  Com-Me is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General
 *  Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with Com-Me.
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package ac.robinson.chameleonnotifier;

import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

class CameraUtilities {

	private static final String TAG = "CameraUtilities";

	public static class CameraConfiguration {
		@SuppressWarnings("WeakerAccess")
		public boolean hasFrontCamera;
		@SuppressWarnings("WeakerAccess")
		public boolean usingFrontCamera;
		@SuppressWarnings("WeakerAccess")
		public int numberOfCameras;
		@SuppressWarnings("WeakerAccess")
		public int cameraOrientationDegrees;

		CameraConfiguration() {
			reset();
		}

		final void reset() {
			hasFrontCamera = false;
			usingFrontCamera = false;
			numberOfCameras = 1;
			cameraOrientationDegrees = 90; // 90 = most common, historically
		}

		@Override
		public String toString() {
			return this.getClass().getName() + "[" + hasFrontCamera + "," + numberOfCameras + "," + usingFrontCamera +
					"," + cameraOrientationDegrees + "]";
		}
	}

	public static boolean getIsCameraAvailable(PackageManager packageManager) {
		return packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA);
	}

	/**
	 * Open one of the device's cameras, and retrieve basic device camera configuration attributes
	 *
	 * @param preferFront         whether to prefer front cameras
	 * @param cameraConfiguration the object in which to store the device's camera configuration aspects
	 * @return the camera, or null on error
	 */
	@Nullable
	public static Camera initialiseCamera(boolean preferFront, @NonNull CameraConfiguration cameraConfiguration) {
		Camera camera = null;
		cameraConfiguration.reset();

		try {
			int preferredFacing = (preferFront ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo
					.CAMERA_FACING_BACK);
			int cameraCount = Camera.getNumberOfCameras();
			for (int i = 0; i < cameraCount; i++) {
				Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
				Camera.getCameraInfo(i, cameraInfo);

				if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
					cameraConfiguration.hasFrontCamera = true;
				}

				// allow non-preferred camera (some devices only have one)
				if (cameraInfo.facing == preferredFacing || i == cameraCount - 1) {
					if (camera == null) { // so that we continue and detect a front camera even if not using it
						try {
							camera = Camera.open(i);
							if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
								cameraConfiguration.usingFrontCamera = true;
							}
							cameraConfiguration.numberOfCameras = cameraCount;
							cameraConfiguration.cameraOrientationDegrees = cameraInfo.orientation;
						} catch (RuntimeException e) {
							Log.e(TAG, "Camera failed to open: " + e.getLocalizedMessage());
						}
					}
				}

			}

		} catch (SecurityException e) {
			Log.d(TAG, "Camera SecurityException " + e.getLocalizedMessage());
		}

		// if we failed, try using the default API to open any camera.
		if (camera == null) {
			try {
				camera = Camera.open();
				cameraConfiguration.reset();
			} catch (RuntimeException e) {
				camera = null;
				Log.e(TAG, "Default camera failed to open: " + e.getLocalizedMessage());
			}
		}

		return camera;
	}

	// see: http://developer.android.com/reference/android/hardware/Camera.Parameters.html#setRotation(int)
	public static int getPreviewOrientationDegrees(int screenOrientationDegrees, int cameraOrientationDegrees, boolean
			usingFrontCamera) {
		int previewOrientationDegrees;
		if (usingFrontCamera) { // compensate for the mirror of the front camera
			previewOrientationDegrees = (cameraOrientationDegrees + screenOrientationDegrees) % 360;
			previewOrientationDegrees = (360 - previewOrientationDegrees) % 360;
		} else { // back-facing
			previewOrientationDegrees = (cameraOrientationDegrees - screenOrientationDegrees + 360) % 360;
		}
		return previewOrientationDegrees;
	}

	/**
	 * Get the current rotation of the screen, either 0, 90, 180 or 270 degrees
	 */
	public static int getScreenRotationDegrees(@NonNull WindowManager windowManager) {
		switch (windowManager.getDefaultDisplay().getRotation()) {
			case Surface.ROTATION_0:
				return 0;
			case Surface.ROTATION_90:
				return 90;
			case Surface.ROTATION_180:
				return 180;
			case Surface.ROTATION_270:
				return 270;
		}
		return 0;
	}
}
