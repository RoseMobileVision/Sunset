package edu.rosehulman.mobilesunset;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;
import edu.rosehulman.mobilesunset.svm.SVM;
import edu.rosehulman.mobilesunset.svm.SVMData;
import edu.rosehulman.sunrisesunset.SunriseSunsetCalculator;

public class MobileSunset extends Activity implements LocationListener {
	public static final String TAG = "MobileSunset";
	public static final String P = "Performance";
	public static long mPStart = 0, mPCur = 0, mPEnd = 0;
	public static final int MEDIA_TYPE_IMAGE = 1;
	public static final int MEDIA_TYPE_VIDEO = 2;
	private static final int R_MEAN = 0;
	private static final int R_STANDARD_DEVIATION = 1;
	private static final int G_MEAN = 2;
	private static final int G_STANDARD_DEVIATION = 3;
	private static final int B_MEAN = 4;
	private static final int B_STANDARD_DEVIATION = 5;
	private static final int DEFAULT_BLOCK_SIZE = 2; // x for x*x
	private static final int MIN_BLOCK_SIZE = 2;
	private static final int MAX_BLOCK_SIZE = 7;
	private static final int NUMBER_OF_FEATURES = 6;

	private Context mContext;
	// Camera
	private Camera mCamera;
	private CameraPreview mCameraPreview;
	private boolean mCaptureFlag = false;
	// Views
	FrameLayout mCameraViewFrameLayout;
	private ImageView mThumbnail;
	private SeekBar mBlockSizeSeekBar;
	private TextView mSVMResultTextView;
	private TextView mMAPSVMTextView;
	private TextView mMAPTimeTextView;
	private TextView mResultTextView;
	private TextView mSelectedBlockSizeTextView;
	private TextView mTimeUntilSunsetTextView;
	private ProgressBar mSVMLoadingProgressBar;
	private Bitmap mImageBitmap;
	// location
	private Location mLocation;
	private LocationManager mLocationManager;
	private String mProvider;
	private boolean mIsLocationInformationAvailable = false;
	// SVM
	private SVM mSVM;
	private GetSVMAsyncTask mGetSVMAsyncTask;
	private int mSelectedNumBlocks = DEFAULT_BLOCK_SIZE;
	private boolean mIsSVMReady = false;
	private long start = 0, end = 0;
	// Time until sunset handler
	private Handler handler = new Handler();
	private boolean mIsSunsetTimeReady = false;
	private long mSunsetTimeInMillis;

	private Runnable mSunsetTimerRunnable = new Runnable() {
		@Override
		public void run() {
			displayTimeUntilSunset();
			handler.postDelayed(this, 1000);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_mobile_sunset);
		// Create an instance of Camera
		mCamera = getCameraInstance();
		mContext = this;
		mThumbnail = (ImageView) findViewById(R.id.thumbnail);
		mSVMResultTextView = (TextView) findViewById(R.id.svmResultTextView);
		mMAPSVMTextView = (TextView) findViewById(R.id.mapSVMTextView);
		mMAPTimeTextView = (TextView) findViewById(R.id.mapTimeTextView);
		mResultTextView = (TextView) findViewById(R.id.resultTextView);
		mTimeUntilSunsetTextView = (TextView) findViewById(R.id.timeUntilSunsetTextView);
		mSVMLoadingProgressBar = (ProgressBar) findViewById(R.id.svmLoadingProgressBar);
		initializeFeaturesSeekBar();
		Button captureButton = (Button) findViewById(R.id.button_capture);
		captureButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				// get an image from the camera
				mCamera.takePicture(null, null, mPicture);
				mCaptureFlag = true;
			}
		});
		

		// Create our Preview view and set it as the content of our activity.
		mCameraPreview = new CameraPreview(this, mCamera);
		mCameraViewFrameLayout = (FrameLayout) findViewById(R.id.camera_preview);
		mCameraViewFrameLayout.addView(mCameraPreview);
		this.mGetSVMAsyncTask = new GetSVMAsyncTask();
		this.mGetSVMAsyncTask.execute();
	}
	
	
	/* Request updates at startup */
	@Override
	protected void onResume() {
		super.onResume();
		if (!mIsLocationInformationAvailable) {
			getGPSLocation();
		}
		mCameraViewFrameLayout.removeAllViews();
		if (mCamera == null) {
			mCamera = getCameraInstance();
		}
		mCameraPreview = new CameraPreview(this, mCamera);
		mCameraViewFrameLayout.addView(mCameraPreview);
	}

	@Override
	protected void onPause() {
		super.onPause();
		releaseCamera(); // release the camera immediately on pause event
		mLocationManager.removeUpdates(this);
		handler.removeCallbacks(mSunsetTimerRunnable);
	}

	protected void displayTimeUntilSunset() {
		if (!mIsLocationInformationAvailable) {
			getGPSLocation();
		} else {
			long timeDelta = getTimeUntilSunsetInSeconds();
			int hours = Math.abs((int) (timeDelta / 3600));
			int minutes = Math.abs((int) ((timeDelta / 60) % 60));
			int seconds = Math.abs((int) (timeDelta % 60));
			String timeString = String.format(Locale.US, "%02d:%02d:%02d",
					hours, minutes, seconds);
			String message;
			if (timeDelta > 0) {
				message = String.format("%s  %s", timeString, "Until Sunset");
			} else {
				message = String.format("%s  %s", timeString, "Past Sunset");
			}
			mTimeUntilSunsetTextView.setText(message);
		}
	}

	private long getTimeUntilSunsetInSeconds() {
		if (!mIsSunsetTimeReady) {
			Calendar officialSunsetCalendar = getOfficialSunsetTimeCalendarForLocation(mLocation);
			mSunsetTimeInMillis = officialSunsetCalendar.getTimeInMillis();
			mIsSunsetTimeReady = true;
		}
		long timeDelta = (mSunsetTimeInMillis - System.currentTimeMillis()) / 1000;
		return timeDelta;
	}

	private void setResult(double svmResult) {
		if (svmResult >= -.4) {
			mSVMResultTextView.setText(String.format("%4s[ %.2f]%9s", "SVM ",
					svmResult, "Sunset   "));
		} else {
			mSVMResultTextView.setText(String.format("%4s[%.2f]%9s", "SVM ",
					svmResult, "NotSunset"));
		}
		Log.d(TAG, String.format("svm[%.2f]", svmResult));
		double map_svm = MAP.map_svm(svmResult);
		if (map_svm >= 0.5) {
			mMAPSVMTextView.setText(String.format("%s[ %.2f]%9s", "MAP SVM",
					map_svm, "Sunset   "));
		} else {
			mMAPSVMTextView.setText(String.format("%s[%.2f]%9s", "MAP SVM",
					map_svm, "NotSunset"));
		}
		double map_time = MAP.map_time(getTimeUntilSunsetInSeconds() / 60.0);
		if (map_time >= 0.5) {
			mMAPTimeTextView.setText(String.format("%4s[ %.2f]%9s", "MAP Time",
					map_time, "Sunset   "));
		} else {
			mMAPTimeTextView.setText(String.format("%4s[%.2f]%9s", "MAP Time",
					map_time, "NotSunset"));
		}
		double result = MAP
				.map(svmResult, getTimeUntilSunsetInSeconds() / 60.0);
		if (result >= .5) {
			mResultTextView.setText(String.format("%4s[%.2f]%9s", "MAP",
					result, "Sunset   "));
		} else {
			mResultTextView.setText(String.format("%4s[%.2f]%9s", "MAP",
					result, "NotSunset"));
		}
		Log.d(TAG, String.format("MAP[%.2f]", result));
	}

	private void initializeFeaturesSeekBar() {
		mBlockSizeSeekBar = (SeekBar) findViewById(R.id.featuresSeekBar);
		mSelectedBlockSizeTextView = (TextView) findViewById(R.id.featuresNumTextView);
		mBlockSizeSeekBar.setMax(MAX_BLOCK_SIZE - MIN_BLOCK_SIZE);
		mBlockSizeSeekBar.setProgress(mSelectedNumBlocks - MIN_BLOCK_SIZE);
		mSelectedBlockSizeTextView.setText(String.format("%d",
				mSelectedNumBlocks));
		mBlockSizeSeekBar
				.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {

					@Override
					public void onStopTrackingTouch(SeekBar seekBar) {
					}

					@Override
					public void onStartTrackingTouch(SeekBar seekBar) {
					}

					@Override
					public void onProgressChanged(SeekBar seekBar,
							int progress, boolean fromUser) {
						mSelectedNumBlocks = progress + MIN_BLOCK_SIZE;
						mSelectedBlockSizeTextView.setText(String.format("%d",
								mSelectedNumBlocks));
						// if the slider is being changed quickly, we cancel
						// the previous svm task and start a new one
						mIsSVMReady = false;
						mSVMLoadingProgressBar.setVisibility(View.VISIBLE);
						mGetSVMAsyncTask.cancel(true);
						mGetSVMAsyncTask = new GetSVMAsyncTask();
						mGetSVMAsyncTask.execute();
					}
				});
	}

	public static Camera getCameraInstance() {
		Camera c = null;
		try {
			c = Camera.open();
		} catch (Exception e) {
			// Camera is not available (in use or does not exist)
		}
		return c;
	}

	private void releaseCamera() {
		if (mCamera != null) {
			mCamera.release();
			mCamera = null;
		}
	}

	private boolean getGPSLocation() {
		mLocationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
		Criteria criteria = new Criteria();
		mProvider = mLocationManager.getBestProvider(criteria, false);
		Log.d(TAG, mProvider);
		if (!mIsLocationInformationAvailable) {
			if (mProvider.equals("gps") && ! mLocationManager
					.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
				Intent intent = new Intent(
						Settings.ACTION_LOCATION_SOURCE_SETTINGS);
				startActivityForResult(intent, 1);
				if(!mLocationManager
						.isProviderEnabled(LocationManager.GPS_PROVIDER)){
					//user didn't enable gps
					Toast.makeText(this, "App requires gps to run. exiting", Toast.LENGTH_SHORT).show();
					this.finish();
				}
			}
			android.location.Location location = mLocationManager
					.getLastKnownLocation(mProvider);
			if (location != null) {
				mLocation = location;
				mIsLocationInformationAvailable = true;
				mLocationManager.removeUpdates(this);
				handler.post(mSunsetTimerRunnable);
			} else {
				mLocationManager.requestLocationUpdates(mProvider, 0, 0, this);
				Log.d(TAG, "no Location");
			}
			
		}
		return mIsLocationInformationAvailable;
	}

	private PictureCallback mPicture = new PictureCallback() {

		@Override
		public void onPictureTaken(byte[] data, Camera camera) {
			// Necessary so the camera will continue previewing the input after
			// taking the picture. otherwise, the screen will freeze.
			
			camera.startPreview();
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inSampleSize = 8;
			// down sample

			mImageBitmap = BitmapFactory.decodeByteArray(data, 0,
					data.length, options);
			mThumbnail.setImageBitmap(mImageBitmap);
			// Un-comment to store the picture
			if (mCaptureFlag){
				new SavePictureAsynctask().execute(data);
				mCaptureFlag = false;
			}
			int allPixels[] = new int[mImageBitmap.getWidth()
					* mImageBitmap.getHeight()];
			mImageBitmap.getPixels(allPixels, 0, mImageBitmap.getWidth(), 0, 0,
					mImageBitmap.getWidth(), mImageBitmap.getHeight());
			if (mIsSVMReady) {
				start = System.currentTimeMillis();
				new SVMForwardAsyncTask(mImageBitmap.getWidth(),
						mImageBitmap.getHeight(), mSelectedNumBlocks)
						.execute(allPixels);
			}

		}

	};

	private Calendar getOfficialSunsetTimeCalendarForLocation(
			android.location.Location location) {
		TimeZone tz = TimeZone.getDefault();
		SunriseSunsetCalculator calc = new SunriseSunsetCalculator(
				new edu.rosehulman.sunrisesunset.dto.Location(
						location.getLatitude(), location.getLongitude()),
				tz.getID());
		Calendar c = Calendar.getInstance();
		return calc.getOfficialSunsetCalendarForDate(c);
	}

	@Override
	public void onProviderDisabled(String provider) {
		Toast.makeText(this, "Disabled provider " + provider,
				Toast.LENGTH_SHORT).show();

	}

	@Override
	public void onProviderEnabled(String provider) {
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
	}

	@Override
	public void onLocationChanged(Location location) {
		mLocation = location;
		mIsLocationInformationAvailable = true;
		mLocationManager.removeUpdates(this);
		handler.post(mSunsetTimerRunnable);
	}
	
	private class LogRuntimesAsyncTask extends AsyncTask<int[], Void, Void>{

		@Override
		protected Void doInBackground(int[]... params) {
			File logsStorageDir = new File(
					Environment
							.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
					getString(R.string.app_name));

			// Create the storage directory if it does not exist
			if (!logsStorageDir.exists()) {
				if (!logsStorageDir.mkdirs()) {
					Log.d(TAG, "failed to create log directory");
				}
			}
			File logFile;
			
			logFile = new File(logsStorageDir.getPath() + File.separator
						+ "MobileSunsetLog.txt");
			
			FileOutputStream fOut;
			try {
				fOut = new FileOutputStream(logFile, true);
				
				OutputStreamWriter osw = new OutputStreamWriter(fOut);
				osw.write(String.format("%d, %.3f, %d, %d\n", params[0][0], params[0][1]/1000.0, params[0][2], params[0][3]));
				osw.flush();
				osw.close();
			} catch (FileNotFoundException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return null;
		}
		
	}
	
	private class SVMForwardAsyncTask extends AsyncTask<int[], Integer, Double> {
		private int imageWidth, imageHeight, selectedBlockSize;

		public SVMForwardAsyncTask(int imageWidth, int imageHeight,
				int selectedBlockSize) {
			this.imageWidth = imageWidth;
			this.imageHeight = imageHeight;
			this.selectedBlockSize = selectedBlockSize;
		}

		protected void onPostExecute(Double result) {
			end = System.currentTimeMillis();
			setResult(result);
			long totalTime = end - start;
			Log.d(TAG, String.format("Total time: %.3fs, for %d features",
					totalTime / 1000.0, mSelectedNumBlocks));
			new LogRuntimesAsyncTask().execute(new int [] {mSelectedNumBlocks, (int) totalTime, imageWidth, imageHeight});
			//mCamera.takePicture(null, null, mPicture);
		}

		@Override
		protected Double doInBackground(int[]... params) {
			return mSVM.forward(extractFeatures(params[0]));
		}

		private double[] extractFeatures(int[] img) {
			double[][] features = new double[selectedBlockSize * selectedBlockSize][NUMBER_OF_FEATURES];
			for (int r = 0; r < selectedBlockSize; r++) {
				for (int c = 0; c < selectedBlockSize; c++) {
					calculateBlockMeans(img, imageWidth, imageHeight,
							selectedBlockSize, r, c, features);
					calculateBlockStandardDeviation(img, imageWidth, imageHeight,
							selectedBlockSize, r, c, features);
				}
			}
			return normalizeFeatures(features);
		}

		private double[] normalizeFeatures(double[][] features) {
			double[] normalized = new double[features.length
					* features[0].length];

			for (int numFeature = 0; numFeature < NUMBER_OF_FEATURES; numFeature++) {
				double min = features[0][numFeature], max = features[0][numFeature];
				for (int i = 0; i < features.length; i++) {
					normalized[i * NUMBER_OF_FEATURES + numFeature] = features[i][numFeature];
					if (normalized[i * NUMBER_OF_FEATURES + numFeature] < min) {
						min = normalized[i * NUMBER_OF_FEATURES + numFeature];
					}
					if (normalized[i * NUMBER_OF_FEATURES + numFeature] > max) {
						max = normalized[i * NUMBER_OF_FEATURES + numFeature];
					}
				}

				for (int i = 0; i < features.length; i++) {
					normalized[i * NUMBER_OF_FEATURES + numFeature] = normalized[i
							* NUMBER_OF_FEATURES + numFeature]
							- SVMData.NORMALIZATION_MINS[numFeature];
					normalized[i * NUMBER_OF_FEATURES + numFeature] = normalized[i
							* NUMBER_OF_FEATURES + numFeature]
							/ SVMData.NORMALIZATION_DELTAS[numFeature];
				}
			}
			return normalized;
		}

		private void calculateBlockMeans(int[] img, int imageWidth,
				int imageHeight, int selectedNumBlocks, int r, int c,
				double[][] features) {
			double rSum = 0, gSum = 0, bSum = 0;
			int pixelCount = (imageHeight / selectedNumBlocks)
					* (imageWidth / selectedNumBlocks);
			int depthBlocks = imageHeight / selectedNumBlocks;
			int widthBlocks = imageWidth / selectedNumBlocks;
			int offset = (r * (imageWidth) * (imageHeight / selectedNumBlocks)) // OK, past row blocks
					+ (c * (imageWidth / selectedNumBlocks)); // OK, to given column block
			int featureOffset = r * selectedNumBlocks + c;
			for (int i = 0; i < depthBlocks; i++) {
				int rowOffset = offset
						+ i * imageWidth; // OK, skips down i single rows
				for (int j = 0; j < widthBlocks; j++) {
					int pixel = img[rowOffset + j]; // OK, skips over j pixels
					rSum += Color.red(pixel);
					gSum += Color.green(pixel);
					bSum += Color.blue(pixel);
				}
			}
			features[featureOffset][R_MEAN] = rSum / pixelCount;
			features[featureOffset][G_MEAN] = gSum / pixelCount;
			features[featureOffset][B_MEAN] = bSum / pixelCount;
		}

		private void calculateBlockStandardDeviation(int[] img, int imageWidth,
				int imageHeight, int selectedNumBlocks, int r, int c,
				double[][] features) {
			double rSum = 0, gSum = 0, bSum = 0;
			int pixelCount = (imageHeight / selectedNumBlocks)
					* (imageWidth / selectedNumBlocks);
			int depthBlocks = imageHeight / selectedNumBlocks;
			int widthBlocks = imageWidth / selectedNumBlocks;
			int offset = (r * (imageWidth) * (imageHeight / selectedNumBlocks)) // OK, past row blocks
					+ (c * (imageWidth / selectedNumBlocks)); // OK, to given column block
			int featureOffset = r * selectedNumBlocks + c;
			for (int i = 0; i < depthBlocks; i++) {
				int rowOffset = offset
						+ i * imageWidth; // OK, skips down i single rows
				for (int j = 0; j < widthBlocks; j++) {
					int pixel = img[rowOffset + j]; // OK, skips over j pixels
					rSum += ((Color.red(pixel) - features[featureOffset][R_MEAN]) * (Color.red(pixel) - features[featureOffset][R_MEAN]));
					gSum += ((Color.green(pixel) - features[featureOffset][G_MEAN]) * (Color
							.green(pixel) - features[featureOffset][G_MEAN]));
					bSum += ((Color.blue(pixel) - features[featureOffset][B_MEAN]) * (Color
							.blue(pixel) - features[featureOffset][B_MEAN]));
				}
			}

			features[featureOffset][R_STANDARD_DEVIATION] = Math
					.sqrt(rSum / (pixelCount - 1));
			features[featureOffset][G_STANDARD_DEVIATION] = Math
					.sqrt(gSum / (pixelCount - 1));
			features[featureOffset][B_STANDARD_DEVIATION] = Math
					.sqrt(bSum / (pixelCount - 1));
		}

	}

	private class GetSVMAsyncTask extends AsyncTask<Void, Void, SVM> {
		protected void onProgressUpdate(Void... params) {
			mIsSVMReady = false;
		}

		protected void onPostExecute(SVM result) {
			mIsSVMReady = true;
			mSVMLoadingProgressBar.setVisibility(View.INVISIBLE);
			mSVM = result;
			Log.d(TAG, "done Loading SVM");
		}

		@Override
		protected SVM doInBackground(Void... params) {
			// Log.d(TAG, "started async getSVMAsyncTask");
			SVM newSVM = new SVM(mContext, mSelectedNumBlocks);
			if (isCancelled())
				return null;
			mSVM = newSVM;
			mPStart = mPEnd = mPCur = System.currentTimeMillis();
			mCamera.takePicture(null, null, mPicture);
			return mSVM;
		}
	}

	private class SavePictureAsynctask extends
			AsyncTask<byte[], Integer, Boolean> {

		@Override
		protected Boolean doInBackground(byte[]... params) {
			byte[] data = params[0];
			// TODO Auto-generated method stub
			File pictureFile = getOutputMediaFile(MEDIA_TYPE_IMAGE);
			if (pictureFile == null) {
				Log.d(TAG,
						"Error creating media file, check storage permissions");
				return false;
			}

			try {
				FileOutputStream fos = new FileOutputStream(pictureFile);
				fos.write(data);
				fos.close();
				sendBroadcast(new Intent(Intent.ACTION_MEDIA_MOUNTED, Uri.parse("file://"+ pictureFile.getAbsolutePath())));
//				Log.d(TAG, pictureFile.getName() + " Saved");
			} catch (FileNotFoundException e) {
				Log.d(TAG, "File not found: " + e.getMessage());
			} catch (IOException e) {
				Log.d(TAG, "Error accessing file: " + e.getMessage());
			}
			return true;
		}

		private File getOutputMediaFile(int type) {
			// To be safe, you should check that the SDCard is mounted
			// using Environment.getExternalStorageState() before doing this.

			File mediaStorageDir = new File(
					Environment
							.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
					getString(R.string.app_name));

			// Create the storage directory if it does not exist
			if (!mediaStorageDir.exists()) {
				if (!mediaStorageDir.mkdirs()) {
					Log.d(TAG, "failed to create directory");
					return null;
				}
			}

			// Create a media file name
			String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss",
					Locale.US).format(new Date());
			File mediaFile;
			if (type == MEDIA_TYPE_IMAGE) {
				mediaFile = new File(mediaStorageDir.getPath() + File.separator
						+ "Pic_" + timeStamp + "_" + mSelectedNumBlocks
						+ ".jpg");
			} else {
				Log.d(TAG, "Not image?");
				return null;
			}

			return mediaFile;
		}

		protected void onPostExecute(Boolean result) {
			if (result) {
//				Log.d(TAG, "saved picture successfully");
				Toast.makeText(mContext, "Saved", Toast.LENGTH_SHORT).show();
			} else {
				Toast.makeText(mContext, "Save Failed", Toast.LENGTH_SHORT)
						.show();
				Log.d(TAG, "saving picture failed");
			}
		}
	}
}
