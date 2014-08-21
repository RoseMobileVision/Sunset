package edu.rosehulman.mobilesunset.svm;

import java.io.InputStream;
import java.util.Locale;
import java.util.Scanner;

import edu.rosehulman.mobilesunset.MobileSunset;
import android.content.Context;
import android.util.Log;

/**
 * A trained support vector machine for Gaussian RBF kernel functions.
 * 
 * I hacked MATLAB to get it to produce the real-valued output.
 * 2013a/toolbox/stats/stats/ svmclassify.m /private/svmdecision.m
 * 
 * 
 * @author Matt Boutell. Created Jan 7, 2013.
 */
public class SVM {

	enum DataSource {
		USE_MATLAB_BUILTIN_FOR_TOY_PROBLEM, USE_SCHWAIGHOFER_FOR_TOY_PROBLEM, USE_MATLAB_BUILTIN_FOR_SUNSET, USE_SCHWAIGHOFER_FOR_SUNSET
	};

	private static DataSource source = DataSource.USE_SCHWAIGHOFER_FOR_SUNSET;
	private int mBlocksize;
	private Context mContext;
	private int nSupportVectors;
	private int dimension;
	private double[][] mSupportVectors;
	private double[] mAlphas;
	private double bias;
	private double sigma = 3; // TODO: read from file

	/**
	 * Create a new empty, untrained SVM.
	 */
	public SVM(Context context, int blockSize) {
		this.mContext = context;
		this.mBlocksize = blockSize;
		readParameters();
	}

	/**
	 * Computes the output for a given input assuming a gaussian RBF kernel.
	 * Scholkopf uses for rbf kernel, K =
	 * exp(-sum((X1i-X2i)^2)/(NET.kernelpar(1)*NET.nin)) =
	 * exp(-sqds/(sigma*dim))
	 * 
	 * MATLAB built-in uses: -sqds / (2*sigma^2) [see rbf_kernel.m], and so do
	 * other other sources.
	 * 
	 * @param outputFeatures
	 */
	public double forward(double[] outputFeatures) {
		// Add bias then loop over SV with corresponding alphas.
		double y1 = this.bias;
		for (int svIdx = 0; svIdx < this.nSupportVectors; svIdx++) {
			double dist2 = squaredistance(outputFeatures,
					mSupportVectors[svIdx]);
			switch (source) {
			case USE_MATLAB_BUILTIN_FOR_TOY_PROBLEM:
			case USE_MATLAB_BUILTIN_FOR_SUNSET:
				y1 += mAlphas[svIdx]
						* Math.exp(-dist2 / (2 * this.sigma * this.sigma));
				break;
			case USE_SCHWAIGHOFER_FOR_TOY_PROBLEM:
			case USE_SCHWAIGHOFER_FOR_SUNSET:
				y1 += mAlphas[svIdx]
						* Math.exp(-dist2 / (this.sigma * this.sigma));
			}
//			Log.d(MobileSunset.TAG, String.format("forwarding %d", svIdx));
		}
		return y1;
	}

	private double squaredistance(double[] x, double[] y) {
//		Log.d(MobileSunset.TAG, String.format("x=%d, y=%d dim = %d", x.length, y.length, this.dimension));
		double distance = 0.0;
		assert (x.length == y.length);
		assert (x.length == this.dimension);
		for (int i = 0; i < x.length; i++) {
			distance += (x[i] - y[i]) * (x[i] - y[i]);
		}
		return distance;
	}

	@Override
	public String toString() {
		StringBuffer buffer = new StringBuffer();
		String line = String.format(Locale.US,
				"SVM d=%d, %d support vectors, bias = %f:\n", this.dimension,
				this.nSupportVectors, this.bias);
		buffer.append(line);
		for (int svIdx = 0; svIdx < this.nSupportVectors; svIdx++) {
			buffer.append(String.format("alpha = %20.16f, SV = ",
					mAlphas[svIdx]));
			for (int dIdx = 0; dIdx < this.dimension; dIdx++) {
				buffer.append(String.format("%20.16f ",
						mSupportVectors[svIdx][dIdx]));
			}
			buffer.append('\n');
		}
		return buffer.toString();
	}

	private void readParameters() {
//		long start = System.currentTimeMillis();
		String trainedSVMFile = String.format("trained_sunset_android_%d", mBlocksize);
//		Log.d(MobileSunset.TAG, trainedSVMFile);
		int trainedSVMFileID = mContext.getResources().getIdentifier(trainedSVMFile, "raw", mContext.getPackageName());
//		Log.d(MobileSunset.TAG, trainedSVMFileID + "");
		InputStream inputStream = mContext.getResources().openRawResource(trainedSVMFileID);
		Scanner fileScanner = new Scanner(inputStream);
		this.sigma = SVMData.sigmas[mBlocksize];
//		Log.d(MobileSunset.TAG, String.format("sigma : %.8f", this.sigma));
		this.nSupportVectors = SVMData.nSupportVectors[mBlocksize];
//		Log.d(MobileSunset.TAG,	String.format("nSupo : %d", this.nSupportVectors));
		this.dimension = SVMData.dimensions[mBlocksize];
//		Log.d(MobileSunset.TAG, String.format("dim : %d", this.dimension));
		this.bias = SVMData.biases[mBlocksize];
//		Log.d(MobileSunset.TAG, String.format("bias : %.8f", this.bias));

		mSupportVectors = new double[nSupportVectors][dimension];
		mAlphas = new double[nSupportVectors];

		for (int svIdx = 0; svIdx < this.nSupportVectors; svIdx++) {
			mSupportVectors[svIdx] = new double[this.dimension];
			String [] vectors = fileScanner.nextLine().split(" ");
			for (int dIdx = 0; dIdx < this.dimension; dIdx++) {
//				Log.d(MobileSunset.TAG, String.format("didx = %d, vectors = %d", dIdx, vectors.length));
				mSupportVectors[svIdx][dIdx] = Double.parseDouble(vectors[dIdx]);
			}
//			Log.d(MobileSunset.TAG, String.format("reading %d", svIdx));
		}
		String [] alphas = fileScanner.nextLine().split(" ");
		for (int svIdx = 0; svIdx < this.nSupportVectors; svIdx++) {
			mAlphas[svIdx] = Double.parseDouble(alphas[svIdx]);
		}
		fileScanner.close();
//		long end = System.currentTimeMillis();
//		long total = end - start;
//		Log.d(MobileSunset.TAG, String.format("totaltime reading %d", total));
//		Log.d(MobileSunset.TAG, String.format("msupport=%d mdim=%d ", nSupportVectors, this.dimension));

	}
}
