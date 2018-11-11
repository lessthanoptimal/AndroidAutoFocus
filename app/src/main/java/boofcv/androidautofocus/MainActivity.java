/*
 * Copyright (c) 2011-2013, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.androidautofocus;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.Locale;

import boofcv.alg.feature.detect.edge.GGradientToEdgeFeatures;
import boofcv.alg.filter.derivative.DerivativeType;
import boofcv.alg.filter.derivative.GImageDerivativeOps;
import boofcv.alg.misc.ImageStatistics;
import boofcv.android.camera2.VisualizeCamera2Activity;
import boofcv.core.image.border.BorderType;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayS16;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageBase;
import boofcv.struct.image.ImageType;

/**
 * Example of how to control the camera with manual focus to select the optimal focus for viewing.
 *
 *
 * @author Peter Abeles
 */
public class MainActivity extends VisualizeCamera2Activity
		implements View.OnClickListener
{
	public static final String TAG = "FocusActivity";

	// how long it lets the camera wait before trying another focus value
	public static final long FOCUS_PERIOD = 50; // milliseconds
	// number if discrete values it will try when focusing
	public static final int FOCUS_LEVELS = 50;

	// Specifies the current state in the auto focus routine that it's in
	State state = State.INITIALIZE;
	// The current focus value being considered
	int focusIndex = 0;
	// The focus index with the best edge value
	int focusBestIndex = 0;
	// The value of the best index
	float focusBestValue = 0;
	// How long until it can attempt to focus again
	long focusTime;
	// Most recently computed edge value
	float edgeValue;

	//-------- Workspace for image processing.
	// the image gradient
	GrayS16 derivX = new GrayS16(1,1);
	GrayS16 derivY = new GrayS16(1,1);
	// Gradient intensity
	GrayF32 intensity = new GrayF32(1,1);

	// Specify paints for rending the GUI
	private Paint paintText = new Paint();
	private Paint paintGray = new Paint();

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		TextureView view = findViewById(R.id.camera_view);
		FrameLayout surface = findViewById(R.id.camera_frame);
		surface.setOnClickListener(this);

		// You need explicit permission from the user to access the camera
		requestCameraPermission();

		// Specify what format you want the image in for image processing
		// The line below specifies an 8-bit gray scale image
		setImageType(ImageType.single(GrayU8.class));

		// Tell the library what resolution you want. It searches for the resolution
		// which has the total number of pixels closest to this value. Most computer vision
		// algorithms run much faster and even work better at low resolutions.
		targetResolution = 640*480;
		// If this is too simplistic for you, feel free to override #selectResolution()

		// We want to display the raw camera feed so we will turn off rendering
		// converted images
		bitmapMode = BitmapMode.NONE;

		// Configure text for display and adjust the size intelligently
		paintText.setStrokeWidth(4*displayMetrics.density);
		paintText.setTextSize(14*displayMetrics.density);
		paintText.setTextAlign(Paint.Align.LEFT);
		paintText.setARGB(0xFF,0xFF,0xB0,0);
		paintText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
		paintGray.setARGB(0xA0,0,0,0);

		startCamera(surface,view);
	}

	@Override
	protected void processImage(ImageBase image) {
		GrayU8 gray = (GrayU8)image;
		derivX.reshape(gray.width,gray.height);
		derivY.reshape(gray.width,gray.height);
		intensity.reshape(gray.width,gray.height);
		GImageDerivativeOps.gradient(DerivativeType.SOBEL,gray,derivX,derivY, BorderType.EXTENDED);

		GGradientToEdgeFeatures.intensityE(derivX, derivY, intensity);

		edgeValue = ImageStatistics.mean(intensity);
	}

	@Override
	protected void onDrawFrame(SurfaceView view, Canvas canvas) {
		super.onDrawFrame(view, canvas);


		// Darken the image behind the text to make it easier to read
		canvas.drawRect(0,100,view.getRight(),220,paintGray);

		// Render text information on top of the camera preview
		// this shows the current edge value and what the app is doing
		canvas.drawText(String.format(Locale.getDefault(),
				"State %s", state.toString()),0,140, paintText);
		int focus = state == State.FIXED ? focusBestIndex : focusIndex;
		canvas.drawText(String.format(Locale.getDefault(),
				"Focus %4d Edge %5.1f", focus, edgeValue),
				0, 200, paintText);


		switch( state ) {
			case FOCUSING:
				// If enough time has elapsed that the camera should have changed focus and
				// the camera is ready to have its configurations changed, save the edge value
				// and tell it update the camera settings
				if( focusTime < System.currentTimeMillis() && isCameraReadyReconfiguration() ) {
					if (edgeValue > focusBestValue) {
						focusBestValue = edgeValue;
						focusBestIndex = focusIndex;
					}
					state = State.PENDING;
					// Tell the camera to change its settings. We know that it will accept
					// new settings since we checked isCameraReadyReconfiguration() otherwise
					// this coulf fail and return false
					changeCameraConfiguration();
				}
				break;
		}
	}

	@Override
	protected void configureCamera(CameraDevice device, CameraCharacteristics characteristics, CaptureRequest.Builder captureRequestBuilder) {
		// set focus control to manual
		captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);

		// get a list of acceptable values
		Float minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

		switch( state ) {
			case INITIALIZE:{
				if( minFocus == null ) {
					Toast.makeText(this,"manual focus not supported", Toast.LENGTH_SHORT).show();
					state = State.UNSUPPORTED;
				} else {
					focusBestIndex = 0;
					focusBestValue = 0;
					focusIndex = 0;
					focusTime = System.currentTimeMillis()+FOCUS_PERIOD;
					state = State.FOCUSING;
					captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
				}
			}break;


			case PENDING:{
				focusIndex++;
				if( focusIndex < FOCUS_LEVELS ) {
					focusTime = System.currentTimeMillis()+FOCUS_PERIOD;
					captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocus*focusIndex/(FOCUS_LEVELS-1));
					state = State.FOCUSING;
				} else {
					captureRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocus*focusBestIndex/(FOCUS_LEVELS-1));
					state = State.FIXED;
				}
			}break;
		}
	}

	/**
	 * Newer versions of Android require explicit permission from the user
	 */
	private void requestCameraPermission() {
		int permissionCheck = ContextCompat.checkSelfPermission(this,
				Manifest.permission.CAMERA);

		if( permissionCheck != android.content.pm.PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this,
					new String[]{Manifest.permission.CAMERA},
					0);
			// a dialog should open and this dialog will resume when a decision has been made
		}
	}

	@Override
	public void onClick(View v) {

		// If the user touches the screen and it has already finished focusing, start again
		if( state == State.FIXED ) {
			state = State.INITIALIZE;
			changeCameraConfiguration();
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
		switch (requestCode) {
			case 0: {
				// If request is cancelled, the result arrays are empty.
				if (!(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
					dialogNoCameraPermission();
				}
			}
		}
	}

	private void dialogNoCameraPermission() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage("Denied access to the camera! Exiting.")
				.setCancelable(false)
				.setPositiveButton("OK", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						System.exit(0);
					}
				});
		AlertDialog alert = builder.create();
		alert.show();
	}

	/**
	 * State in automatic focus
	 */
	enum State {
		INITIALIZE,
		UNSUPPORTED,
		FOCUSING,
		PENDING,
		FIXED
	}

}