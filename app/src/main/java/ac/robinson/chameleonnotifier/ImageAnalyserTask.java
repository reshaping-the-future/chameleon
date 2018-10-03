package ac.robinson.chameleonnotifier;

import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;

class ImageAnalyserTask extends AsyncTask<Void, Bitmap, Bitmap> {

	private static final String TAG = "ImageAnalyserTask";

	private final Bitmap mBitmap;
	private final int mColour;
	private final ImageAnalyserCallback mCallback;

	public interface ImageAnalyserCallback {
		void analysisFailed();

		void analysisSucceeded(Bitmap result);
	}

	ImageAnalyserTask(Bitmap bitmap, int colour, ImageAnalyserCallback callback) {
		mBitmap = bitmap;
		mColour = colour;
		mCallback = callback;
	}

	@Override
	protected Bitmap doInBackground(Void... unused) {
		// Log.d(TAG, "Processing image for colour: " + mColour);

		Mat bitMat = new Mat();

		// set one pixel to the desired colour (and make sure to use OpenCV's methods, so we get the right hue)
		mBitmap.setPixel(0, 0, mColour);
		Utils.bitmapToMat(mBitmap, bitMat);

		// convert to HSV for colour detection
		Mat hsvMat = new Mat();
		Imgproc.cvtColor(bitMat, hsvMat, Imgproc.COLOR_BGR2HSV); // OpenCV uses BGR

		// get the hue component of the new image
		ArrayList<Mat> hsvList = new ArrayList<>();
		Core.split(hsvMat, hsvList);
		double hue = hsvList.get(0).get(0, 0)[0];

		// select colours in this range - OpenCV uses  H: 0 - 180, S: 0 - 255, V: 0 - 255
		int hueRange = 10;
		Mat thresholdMat = new Mat(); // will be in greyscale (e.g., single channel) after thresholding
		Core.inRange(hsvMat, new Scalar(hue - hueRange, 0, 0), new Scalar(hue + hueRange, 255, 255), thresholdMat);

		// try to remove smaller elements (see: http://stackoverflow.com/a/24464280)
		int erosionSize = 15;
		Mat erodeElement = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(erosionSize, erosionSize));
		Imgproc.erode(thresholdMat, thresholdMat, erodeElement);
		Imgproc.dilate(thresholdMat, thresholdMat, erodeElement);

		// blur to soften edges - less flexible alternative: Imgproc.medianBlur(hsvMat, blurMat, blurSize);
		int blurSize = 9;
		int blurSTDev = 3;
		Imgproc.GaussianBlur(thresholdMat, thresholdMat, new Size(blurSize, blurSize), blurSTDev, blurSTDev);

		// scale down to lessen the impact of the highlight
		Core.multiply(thresholdMat, new Scalar(0.35), thresholdMat);

		// get the r/g/b (actually b/g/r) components of the original image and add the highlighted colour to each one
		ArrayList<Mat> matList = new ArrayList<>();
		Core.split(bitMat, matList);
		Mat redMat = new Mat();
		Mat greenMat = new Mat();
		Mat blueMat = new Mat();
		Core.add(matList.get(0), thresholdMat, redMat);
		Core.add(matList.get(1), thresholdMat, greenMat);
		Core.add(matList.get(2), thresholdMat, blueMat);

		// merge the colours again
		ArrayList<Mat> mergedMatList = new ArrayList<>();
		mergedMatList.add(redMat);
		mergedMatList.add(greenMat);
		mergedMatList.add(blueMat);
		Mat mergedMat = new Mat();
		Core.merge(mergedMatList, mergedMat);

		Bitmap newBitmap = Bitmap.createBitmap(mBitmap);
		Utils.matToBitmap(mergedMat, newBitmap);
		return newBitmap;
	}

	@Override
	protected void onCancelled(Bitmap result) {
		Log.d(TAG, "Cancelled image analysis for colour: " + mColour);
		mCallback.analysisFailed();
	}

	@Override
	protected void onPostExecute(Bitmap result) {
		if (result == null) {
			mCallback.analysisFailed();
		} else {
			mCallback.analysisSucceeded(result);
		}
	}
}

