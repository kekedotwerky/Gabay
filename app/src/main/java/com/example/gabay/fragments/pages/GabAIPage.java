package com.example.gabay.fragments.pages;


import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import java.io.InputStream;
import java.util.ArrayDeque;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.gabay.R;
import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream  ;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GabAIPage extends Fragment {

    private PreviewView cameraPreview;

    private OrtEnvironment transactionalOrtEnvironment;

    private OrtSession transactionalOrtSession;

    private View view;
    private ExecutorService cameraExecutor;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;
    private int currentImageSize = 224;
    private final int numClasses = 5;

    private TextView predictionText, modelIndicatorText, instructionText;
    private Interpreter tfliteBasicSigns;
    private Interpreter tfliteFSLAlphabet;
    private Interpreter tfliteNumbers10;
    private Interpreter tfliteFSL105;
    private Interpreter currentModel;

    private HandLandmarker handLandmarker;


    private String[] loadLabels(String fileName) throws IOException {
        try (InputStream input = requireContext()
                .getAssets().open(fileName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            byte[] data = new byte[4096];
            int count;

            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
            }

            JSONArray jsonArray = new JSONArray(
                    output.toString("UTF-8")
            );

            String[] labels = new String[jsonArray.length()];

            for (int i = 0; i < jsonArray.length(); i++) {
                labels[i] = jsonArray.getString(i).trim();
            }

            if (labels.length != 105) {
                throw new IOException(
                        "Expected 105 labels, found " + labels.length
                );
            }

            return labels;

        } catch (Exception e) {
            throw new IOException(
                    "Could not load FSL-105 labels: " + e.getMessage(), e
            );
        }
    }

    private String[] FSL105_labels;

    private final ArrayDeque<float[]> fsl105Frames = new ArrayDeque<>();
    private static final long FSL105_SAMPLE_INTERVAL_MS = 250;
    private long fsl105LastSampleTimeMs = 0L;
    private static final int FSL105_FRAME_COUNT = 16;
    private static final int FSL105_IMAGE_SIZE = 224;
    private final String[] FSL_BasicSigns_labels = {"A", "C", "I", "MAHAL KITA", "N"}; //
    private final String[] FSL_alphabet_labels = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "J", "Z"};
    private final String[] FSL_numbers10_labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10"};
    private int currentModelIndex = 0; // 0=Basic, 1=alphabet, 2=numbers
    private String[] currentLabels;
    private Button switchModelButton;
    private ImageButton switchCameraButton;
    private int currentLensFacing = CameraSelector.LENS_FACING_FRONT;
    private ProcessCameraProvider cameraProvider;

    // Frame rate limiting
    private long lastAnalysisTime = 0;
    private static final long MIN_TIME_BETWEEN_FRAMES = 200; // 5 FPS max
    private static final float CONFIDENCE_THRESHOLD = 0.8f;

    private static final int STABILITY_WINDOW = 5;
    private static final int REQUIRED_MATCHES = 3;

    private final ArrayDeque<String> recentPredictions =
            new ArrayDeque<>();

    private int stabilityModelIndex = -1;

    // Bounding box configuration
    private static final float CROP_PERCENTAGE = 0.5f; // use center 50% of the image

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
    }

    private MappedByteBuffer loadModelFile(String modelName) throws IOException {
        AssetFileDescriptor fileDescriptor = requireContext().getAssets().openFd(modelName);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_gabai_page, container, false);

        cameraPreview = view.findViewById(R.id.cameraPreview);
        predictionText = view.findViewById(R.id.predictionText);
        modelIndicatorText = view.findViewById(R.id.modelIndicatorText);
        instructionText = view.findViewById(R.id.instructionText);
        switchModelButton = view.findViewById(R.id.switchModelButton);
        switchCameraButton = view.findViewById(R.id.switchCameraButton);
        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            Interpreter.Options senyasOptions = new Interpreter.Options();

            Interpreter senyasTestModel = new Interpreter(
                    loadModelFile("senyas_fsl105_candidate_52_58.tflite"),
                    senyasOptions
            );

            int[] inputShape = senyasTestModel.getInputTensor(0).shape();
            int[] outputShape = senyasTestModel.getOutputTensor(0).shape();

            Log.d("SENYAS_TEST", "Input shape: " + Arrays.toString(inputShape));
            Log.d("SENYAS_TEST", "Output shape: " + Arrays.toString(outputShape));

            senyasTestModel.close();

        } catch (Exception e) {
            Log.e("SENYAS_TEST", "SENYAS model inspection failed", e);
        }


        try {
            Interpreter.Options options = new Interpreter.Options();
            tfliteBasicSigns = new Interpreter(loadModelFile("model_unquant.tflite"), options);
            tfliteFSLAlphabet = new Interpreter(loadModelFile("fsl_alphabet_model.tflite"), options);
            tfliteNumbers10 = new Interpreter(loadModelFile("fsl_numbers10_model.tflite"), options);
            tfliteFSL105 = new Interpreter(
                    loadModelFile("senyas_fsl105_candidate_52_58.tflite"),
                    options
            );

            FSL105_labels = loadLabels("senyas_fsl105_labels.json");

            debugModelInfo(tfliteFSL105, "SENYAS FSL-105");

            if (tfliteFSL105 != null) {
                int[] inputShape =
                        tfliteFSL105.getInputTensor(0).shape();

                int[] outputShape =
                        tfliteFSL105.getOutputTensor(0).shape();

                Log.d(
                        "SENYAS_TENSOR",
                        "Input shape: "
                                + java.util.Arrays.toString(inputShape)
                );

                Log.d(
                        "SENYAS_TENSOR",
                        "Input data type: "
                                + tfliteFSL105.getInputTensor(0).dataType()
                );

                Log.d(
                        "SENYAS_TENSOR",
                        "Output shape: "
                                + java.util.Arrays.toString(outputShape)
                );

                Log.d(
                        "SENYAS_TENSOR",
                        "Output data type: "
                                + tfliteFSL105.getOutputTensor(0).dataType()
                );
            }

            Log.d("SENYAS_FSL105",
                    "Loaded " + FSL105_labels.length + " labels");
            debugModelInfo(tfliteBasicSigns, "Model 1 (Basic Signs)");
            debugModelInfo(tfliteFSLAlphabet, "Model 2 (Alphabet)");
            debugModelInfo(tfliteNumbers10, "Model 3 (Numbers)");


            if (tfliteBasicSigns != null
                    && tfliteFSLAlphabet != null
                    && tfliteNumbers10 != null

                    && tfliteFSL105 != null) {

                switchToNextModel();

                Log.d("TFLite", "All 4 models loaded successfully");

            } else {
                throw new IOException("One or more models failed to load");
            }


        } catch (IOException e) {
            Log.e("TFLite", "Model load failed: " + e.getMessage());
            predictionText.setText("Model load failed");
            Toast.makeText(requireContext(), "Failed to load AI model", Toast.LENGTH_LONG).show();
        }

        // Update button listener for 3 models
        switchModelButton.setOnClickListener(v -> {
            if (tfliteBasicSigns == null || tfliteFSLAlphabet == null || tfliteNumbers10 == null) {
                Toast.makeText(requireContext(), "Models not loaded properly", Toast.LENGTH_SHORT).show();
                return;
            }

            switchToNextModel();
        });

// Add this for the switch camera button
        switchCameraButton.setOnClickListener(v -> {
            switchCamera();
        });

// Initialize MediaPipe hand detection
        initializeHandLandmarker();

        return view;
    }

    private void switchCamera() {
        if (cameraProvider == null) {
            Toast.makeText(requireContext(), "Camera not initialized", Toast.LENGTH_SHORT).show();
            return;
        }
        currentLensFacing = (currentLensFacing == CameraSelector.LENS_FACING_FRONT)
                ? CameraSelector.LENS_FACING_BACK
                : CameraSelector.LENS_FACING_FRONT;

        startCamera();
    }

    // image classifier

    @OptIn(markerClass = ExperimentalGetImage.class)
    private Bitmap toBitmap(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();

        if (image == null || imageProxy.getFormat() != ImageFormat.YUV_420_888) {
            Log.e("ImageConversion", "Expected a YUV_420_888 camera image");
            return null;
        }

        Image.Plane[] planes = image.getPlanes();

        if (planes.length < 3) {
            Log.e("ImageConversion", "Camera image has fewer than 3 planes");
            return null;
        }

        int width = image.getWidth();
        int height = image.getHeight();

        ByteBuffer yBuffer = planes[0].getBuffer().duplicate();
        ByteBuffer uBuffer = planes[1].getBuffer().duplicate();
        ByteBuffer vBuffer = planes[2].getBuffer().duplicate();

        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        byte[] nv21 = new byte[width * height * 3 / 2];

        try {
            // Copy the Y (luminance) plane, respecting its strides.
            int outputIndex = 0;

            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    int index = row * yRowStride + col * yPixelStride;
                    nv21[outputIndex++] = yBuffer.get(index);
                }
            }

            // NV21 stores chroma as VU pairs.
            int chromaHeight = height / 2;
            int chromaWidth = width / 2;

            for (int row = 0; row < chromaHeight; row++) {
                for (int col = 0; col < chromaWidth; col++) {
                    int vIndex = row * vRowStride + col * vPixelStride;
                    int uIndex = row * uRowStride + col * uPixelStride;

                    nv21[outputIndex++] = vBuffer.get(vIndex);
                    nv21[outputIndex++] = uBuffer.get(uIndex);
                }
            }

            YuvImage yuvImage = new YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    width,
                    height,
                    null
            );

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                boolean compressed = yuvImage.compressToJpeg(
                        new Rect(0, 0, width, height),
                        90,
                        output
                );

                if (!compressed) {
                    Log.e("ImageConversion", "YUV to JPEG conversion failed");
                    return null;
                }

                byte[] jpegBytes = output.toByteArray();

                return BitmapFactory.decodeByteArray(
                        jpegBytes,
                        0,
                        jpegBytes.length
                );
            }

        } catch (Exception e) {
            Log.e("ImageConversion", "Error converting camera image", e);
            return null;
        }
    }


    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        int imageSize = currentImageSize;

        // Calculate center crop dimensions
        int minDimension = Math.min(bitmap.getWidth(), bitmap.getHeight());
        int cropSize = (int) (minDimension * CROP_PERCENTAGE);
        int left = (bitmap.getWidth() - cropSize) / 2;
        int top = (bitmap.getHeight() - cropSize) / 2;

        Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, left, top, cropSize, cropSize);
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, imageSize, imageSize, true);
        Matrix matrix = new Matrix();

        if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
            // Front camera: rotate 270° and flip horizontally (mirror effect)
            matrix.postRotate(270);
            matrix.postScale(-1, 1);
        } else {
            // Back camera: just rotate 90° (no flip)
            matrix.postRotate(90);
        }

        Bitmap processedBitmap = Bitmap.createBitmap(scaledBitmap, 0, 0,
                scaledBitmap.getWidth(), scaledBitmap.getHeight(), matrix, true);
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * 3);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[imageSize * imageSize];
        processedBitmap.getPixels(pixels, 0, imageSize, 0, 0, imageSize, imageSize);

        // Normalize to [0, 1] range
        for (int pixelValue : pixels) {
            buffer.putFloat(((pixelValue >> 16) & 0xFF) / 255.0f);
            buffer.putFloat(((pixelValue >> 8) & 0xFF) / 255.0f);
            buffer.putFloat((pixelValue & 0xFF) / 255.0f);
        }
        croppedBitmap.recycle();
        if (scaledBitmap != croppedBitmap) scaledBitmap.recycle();
        if (processedBitmap != scaledBitmap) processedBitmap.recycle();

        return buffer;
    }

    private int getMaxProbIndex(float[] probs) {
        if (probs == null || probs.length == 0) {
            return 0;
        }
        int maxIdx = 0;
        float maxProb = probs[0];
        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > maxProb) {
                maxProb = probs[i];
                maxIdx = i;
            }
        }
        return maxIdx;
    }

    
        private void analyzeFrame(ImageProxy imageProxy) {
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
    
            Log.d("SENYAS_ROTATION",
                    "Rotation: " + rotation
                            + ", Frame: " + imageProxy.getWidth()
                            + "x" + imageProxy.getHeight()
                            + ", Lens: " + currentLensFacing);
            Log.d("SENYAS_CAMERA",
                    "Frame width=" + imageProxy.getWidth()
                            + ", height=" + imageProxy.getHeight()
                            + ", rotationDegrees="
                            + imageProxy.getImageInfo().getRotationDegrees());
            long currentTime = System.currentTimeMillis();
    
            if (currentTime - lastAnalysisTime < MIN_TIME_BETWEEN_FRAMES) {
                imageProxy.close();
                return;
            }
    
            lastAnalysisTime = currentTime;
    
            // Save the selected mode for this frame.
            final int modelIndexForFrame = currentModelIndex;
    
            // FSL-105 has its own 16-frame video pipeline.
            if (modelIndexForFrame == 3) {
                analyzeFsl105Frame(imageProxy);
                return;
            }
    
            // Capture the model and labels together for this prediction.
            final Interpreter modelForFrame = currentModel;
            final String[] labelsForFrame = currentLabels;
    
            if (modelForFrame == null || labelsForFrame == null) {
                Log.e("TFLite", "Current model or labels are null!");
                imageProxy.close();
                return;
            }
    
            Bitmap bitmap = null;
    
            try {
                bitmap = toBitmap(imageProxy);
    
                if (bitmap == null) {
                    Log.e("TFLite", "Bitmap conversion failed");
                    return;
                }
    
    // Check whether a hand is visible before running the model.
                if (handLandmarker == null) {
                    Log.e("SENYAS_HAND", "Hand detector is not initialized");
    
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> {
                            if (isAdded() && predictionText != null) {
                                predictionText.setText("Hand detector unavailable");
                                predictionText.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                    return;
                }
    
                Bitmap detectionBitmap = rotateBitmapForHandDetection(
                        bitmap,
                        imageProxy.getImageInfo().getRotationDegrees()
                );
    
                boolean handDetected;
    
                try {
                    MPImage mpImage = new BitmapImageBuilder(detectionBitmap).build();
    
                    HandLandmarkerResult handResult =
                            handLandmarker.detect(mpImage);
    
                    handDetected = handResult != null
                            && !handResult.landmarks().isEmpty();
    
                    Log.d(
                            "SENYAS_HAND",
                            "Hand detected: " + handDetected
                    );
    
                } finally {
                    if (detectionBitmap != bitmap
                            && !detectionBitmap.isRecycled()) {
                        detectionBitmap.recycle();
                    }
                }
    
    // Do not classify a frame when no hand is detected.
                if (!handDetected) {
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> {
                            if (isAdded()
                                    && predictionText != null
                                    && currentModelIndex == modelIndexForFrame) {
                                predictionText.setText("No hand detected");
                                predictionText.setVisibility(View.VISIBLE);
                            }
                        });
                    }
                    return;
                }
    
    // Keep the existing model preprocessing unchanged.
                ByteBuffer input = convertBitmapToByteBuffer(bitmap);
    
                int outputSize =
                        modelForFrame.getOutputTensor(0).shape()[1];
    
                if (outputSize != labelsForFrame.length) {
                    Log.e(
                            "SENYAS_PREDICTION",
                            "Output/label mismatch. Model index: "
                                    + modelIndexForFrame
                                    + ", output size: " + outputSize
                                    + ", label count: " + labelsForFrame.length
                    );
    
                    requireActivity().runOnUiThread(() -> {
                        if (isAdded()
                                && predictionText != null
                                && currentModelIndex == modelIndexForFrame) {
                            predictionText.setText("Model output mismatch");
                            predictionText.setVisibility(View.VISIBLE);
                        }
                    });
    
                    return;
                }
    
    
                float[][] output = new float[1][outputSize];
    
                Log.d(
                        "SENYAS_DEBUG",
                        "Running inference. Model index=" + modelIndexForFrame
                                + ", output size=" + outputSize
                );
    
                // Use the saved model, not the model that may have been
                // selected after this frame started processing.
                modelForFrame.run(input, output);
    
                int maxIdx = getMaxProbIndex(output[0]);
    
                if (maxIdx < 0 || maxIdx >= labelsForFrame.length) {
                    Log.e(
                            "SENYAS_PREDICTION",
                            "Invalid prediction index: " + maxIdx
                    );
                    return;
                }
    
                float confidence = output[0][maxIdx];
                String predictedLabel = labelsForFrame[maxIdx];
    
    
                if (stabilityModelIndex != modelIndexForFrame) {
                    recentPredictions.clear();
                    stabilityModelIndex = modelIndexForFrame;
                }
    
                boolean confident = confidence >= CONFIDENCE_THRESHOLD;
    
                if (confident) {
                    recentPredictions.addLast(predictedLabel);
    
                    while (recentPredictions.size() > STABILITY_WINDOW) {
                        recentPredictions.removeFirst();
                    }
                } else {
                    recentPredictions.clear();
                }
    
                int matchingCount = 0;
    
                for (String label : recentPredictions) {
                    if (label.equals(predictedLabel)) {
                        matchingCount++;
                    }
                }
    
                boolean stablePrediction =
                        confident && matchingCount >= REQUIRED_MATCHES;
    
                String displayText;
    
                if (!confident) {
                    displayText = "Uncertain - hold your sign steady";
                } else if (!stablePrediction) {
                    displayText = "Hold your sign steady...";
                } else {
    
                    displayText = String.format(
                            java.util.Locale.US,
                            "%s (%.1f%%)",
                            predictedLabel,
                            confidence * 100f
                    );
                }
    
                Log.d(
                        "SENYAS_PREDICTION",
                        "Model index: " + modelIndexForFrame
                                + ", label: " + predictedLabel
                                + ", score: " + confidence
                                + ", stable: " + stablePrediction
                                + ", matching frames: " + matchingCount
                );
    
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded()
                                || predictionText == null
                                || currentModelIndex != modelIndexForFrame) {
                            return;
                        }
    
                        predictionText.setText(displayText);
                        predictionText.setVisibility(View.VISIBLE);
                    });
                }
    
    
            } catch (Exception e) {
                Log.e("TFLite", "Frame prediction failed", e);
    
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
    
                imageProxy.close();
            }
        }
    
        private void switchToNextModel() {
            // Cycle through 0,1,2 and back to 0
            currentModelIndex = (currentModelIndex + 1) % 4;
            synchronized (fsl105Frames) {
                fsl105Frames.clear();
                fsl105LastSampleTimeMs = 0L;
            }
            switch (currentModelIndex) {
                case 0: // basic signs
                    currentModel = tfliteBasicSigns;
                    currentLabels = FSL_BasicSigns_labels;
                    currentImageSize = 224;
                    modelIndicatorText.setText("Basic Signs Mode");
                    instructionText.setText("Place your hand in the center box and show a basic sign");
                    switchModelButton.setText("Switch model to Alphabet");
                    Log.d("ModelSwitch", "Switched to Basic Signs - " + FSL_BasicSigns_labels.length + " labels");
                    break;
    
                case 1: // fsl alphabet
                    currentModel = tfliteFSLAlphabet;
                    currentLabels = FSL_alphabet_labels;
                    currentImageSize = 224;
                    modelIndicatorText.setText("Alphabet Mode");
                    instructionText.setText("Place your hand in the center box and show a letter");
                    switchModelButton.setText("Switch model to Numbers");
                    Log.d("ModelSwitch", "Switched to Alphabet - " + FSL_alphabet_labels.length + " labels");
                    break;
    
                case 2: // numbers 1-10
                    currentModel = tfliteNumbers10;
                    currentLabels = FSL_numbers10_labels;
                    currentImageSize = 224;
                    modelIndicatorText.setText("Numbers Mode");
                    instructionText.setText("Place your hand in the center box and show a number");
                    switchModelButton.setText("Switch model to FSL-105 Video Signs");
                    Log.d("ModelSwitch", "Switched to Numbers - " + FSL_numbers10_labels.length + " labels");
                    break;
    
                case 3: // FSL-105 video model
                    currentModel = null;
                    currentLabels = FSL105_labels;
                    currentImageSize = 224;
    
                    synchronized (fsl105Frames) {
                        fsl105Frames.clear();
                    }
    
                    modelIndicatorText.setText("FSL-105 Video Signs");
                    instructionText.setText(
                            "Perform a sign and wait for 16 video frames to be analyzed"
                    );
                    switchModelButton.setText("Switch model to Basic Signs");
    
                    Log.d("ModelSwitch", "Switched to FSL-105 Video Signs");
                    break;
            }
    
            // Verify the model input shape
            if (currentModel != null) {
                try {
                    int[] inputShape = currentModel.getInputTensor(0).shape();
                    int[] outputShape = currentModel.getOutputTensor(0).shape();
                    Log.d("ModelSwitch", "Expected input shape: " + Arrays.toString(inputShape));
                    Log.d("ModelSwitch", "Expected output shape: " + Arrays.toString(outputShape));
                } catch (Exception e) {
                    Log.e("ModelSwitch", "Error getting model shapes: " + e.getMessage());
                }
            }
    
            Log.d("ModelSwitch", "Current image size: " + currentImageSize);
            predictionText.setText("...");
            lastAnalysisTime = 0;
        }


    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        loadTransactionalModel();

        if (checkCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }


    private void debugModelInfo(Interpreter model, String modelName) {
            try {
                int inputTensorCount = model.getInputTensorCount();
                int outputTensorCount = model.getOutputTensorCount();
    
                Log.d("ModelDebug", "=== " + modelName + " ===");
                Log.d("ModelDebug", "Input tensors: " + inputTensorCount);
                Log.d("ModelDebug", "Output tensors: " + outputTensorCount);
    
                for (int i = 0; i < inputTensorCount; i++) {
                    int[] inputShape = model.getInputTensor(i).shape();
                    DataType inputDataType = model.getInputTensor(i).dataType();
                    Log.d("ModelDebug", "Input " + i + " shape: " + Arrays.toString(inputShape) +
                            ", type: " + inputDataType);
                }
    
                for (int i = 0; i < outputTensorCount; i++) {
                    int[] outputShape = model.getOutputTensor(i).shape();
                    DataType outputDataType = model.getOutputTensor(i).dataType();
                    Log.d("ModelDebug", "Output " + i + " shape: " + Arrays.toString(outputShape) +
                            ", type: " + outputDataType);
                }
            } catch (Exception e) {
                Log.e("ModelDebug", "Error debugging model: " + e.getMessage());
            }
        }
    
    
        @Override
        public void onResume() {
            super.onResume();
            if (checkCameraPermission()) {
                startCamera();
            } else {
                requestCameraPermission();
            }
        }
    
        private void startCamera() {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                    ProcessCameraProvider.getInstance(requireContext());
    
            cameraProviderFuture.addListener(() -> {
                try {
                    cameraProvider = cameraProviderFuture.get();
                    cameraProvider.unbindAll();
    
                    Preview preview = new Preview.Builder().build();
    
                    // Use currentLensFacing instead of hardcoded front camera
                    CameraSelector cameraSelector = new CameraSelector.Builder()
                            .requireLensFacing(currentLensFacing)
                            .build();
    
                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
    
                    imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
    
                    preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());
                    cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageAnalysis);
    
                    Log.d("CameraX", "Camera started successfully with lens facing: " + currentLensFacing);
    
                } catch (Exception e) {
                    Log.e("CameraX", "Error starting camera: " + e.getMessage(), e);
                    Toast.makeText(requireContext(), "Error starting camera", Toast.LENGTH_LONG).show();
                }
            }, ContextCompat.getMainExecutor(requireContext()));
        }
    
        @Override
        public void onPause() {
            super.onPause();
            stopCameraSafely();
        }
    
        @Override
        public void onDestroy() {
            super.onDestroy();
            stopCameraSafely();
    
            if (cameraExecutor != null) {
                cameraExecutor.shutdown();
            }
    
            // Close all models
            closeModelSafely(tfliteBasicSigns);
            closeModelSafely(tfliteFSLAlphabet);
            closeModelSafely(tfliteNumbers10);
            closeModelSafely(currentModel);
    
            Log.d("GabAIPage", "All 3 models closed");
        }
    
        private void closeModelSafely(Interpreter model) {
            if (model != null) {
                try {
                    model.close();
                } catch (Exception e) {
                    Log.e("TFLite", "Error closing model", e);
                }
            }
        }
    
        @Override
        public void onStop() {
            super.onStop();
            stopCameraSafely();
        }
    
        private void stopCameraSafely() {
            if (!isAdded()) return;
            try {
                ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(requireContext()).get();
                cameraProvider.unbindAll();
                Log.d("CameraX", "Camera stopped safely");
            } catch (Exception e) {
                Log.e("CameraX", "Error unbinding camera: " + e.getMessage());
            }
        }
    
        private void analyzeFsl105Frame(ImageProxy imageProxy) {
            Log.d("SENYAS_FSL105", "FSL-105 frame received");
            Bitmap bitmap = null;
            Bitmap rotatedBitmap = null;
            Bitmap resizedBitmap = null;
    
            try {
                if (tfliteFSL105 == null || FSL105_labels == null) {
                    return;
                }
    
                // Convert the camera frame to a bitmap.
                bitmap = toBitmap(imageProxy);
    
                if (bitmap == null) {
                    return;
                }
    
                // Correct the camera frame's orientation.
                int rotation =
                        imageProxy.getImageInfo().getRotationDegrees();
    
                if (rotation != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(rotation);
    
                    rotatedBitmap = Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            bitmap.getWidth(),
                            bitmap.getHeight(),
                            matrix,
                            true
                    );
                } else {
                    rotatedBitmap = bitmap;
                }
    
                // Resize directly to 224 x 224.
                resizedBitmap = Bitmap.createScaledBitmap(
                        rotatedBitmap,
                        FSL105_IMAGE_SIZE,
                        FSL105_IMAGE_SIZE,
                        true
                );
    
                int pixelCount =
                        FSL105_IMAGE_SIZE * FSL105_IMAGE_SIZE;
    
                int[] pixels = new int[pixelCount];
    
                resizedBitmap.getPixels(
                        pixels,
                        0,
                        FSL105_IMAGE_SIZE,
                        0,
                        0,
                        FSL105_IMAGE_SIZE,
                        FSL105_IMAGE_SIZE
                );
    
                // Normalize RGB pixels to [-1, 1].
                float[] frameData = new float[pixelCount * 3];
    
                int index = 0;
    
                for (int pixel : pixels) {
                    frameData[index++] =
                            (((pixel >> 16) & 0xFF) / 127.5f) - 1.0f;
    
                    frameData[index++] =
                            (((pixel >> 8) & 0xFF) / 127.5f) - 1.0f;
    
                    frameData[index++] =
                            ((pixel & 0xFF) / 127.5f) - 1.0f;
                }
    
                // Collect 16 frames before making a prediction.
                java.util.List<float[]> framesToPredict = null;
    
                synchronized (fsl105Frames) {
                    fsl105Frames.addLast(frameData);
    
                    if (fsl105Frames.size() >= FSL105_FRAME_COUNT) {
                        framesToPredict =
                                new java.util.ArrayList<>(fsl105Frames);
    
                        fsl105Frames.clear();
                    }
                }
    
                // Wait for enough frames.
                if (framesToPredict == null) {
                    Log.d(
                            "SENYAS_FSL105",
                            "Collecting frames: " + fsl105Frames.size()
                                    + "/" + FSL105_FRAME_COUNT
                    );
                    return;
                }
    
                Log.d("SENYAS_FSL105", "16 frames collected; running prediction");
    
    
                // Build the input from the 16 frames we actually collected.
                int floatsPerFrame = pixelCount * 3;
    
                ByteBuffer inputBuffer = ByteBuffer.allocateDirect(
                        FSL105_FRAME_COUNT
                                * floatsPerFrame
                                * Float.BYTES
                );
    
                inputBuffer.order(java.nio.ByteOrder.nativeOrder());
                inputBuffer.clear();
    
                float inputMin = Float.POSITIVE_INFINITY;
                float inputMax = Float.NEGATIVE_INFINITY;
                double inputSum = 0.0;
                long inputCount = 0;
    
                Log.d("SENYAS_FRAMES",
                        "Frames ready for prediction: "
                                + framesToPredict.size());
    
                for (float[] frame : framesToPredict) {
                    if (frame == null || frame.length != floatsPerFrame) {
                        throw new IllegalStateException(
                                "Invalid frame data. Expected "
                                        + floatsPerFrame
                                        + " floats, got "
                                        + (frame == null ? "null" : frame.length)
                        );
                    }
    
                    for (float value : frame) {
                        inputBuffer.putFloat(value);
    
                        if (value < inputMin) inputMin = value;
                        if (value > inputMax) inputMax = value;
    
                        inputSum += value;
                        inputCount++;
                    }
                }
    
                inputBuffer.rewind();
    
                double inputMean = inputCount > 0
                        ? inputSum / inputCount
                        : 0.0;
    
                Log.d("SENYAS_INPUT",
                        "min=" + inputMin
                                + ", max=" + inputMax
                                + ", mean=" + inputMean
                                + ", count=" + inputCount);
    
                // Run the FSL-105 video model.
                float[][] output = new float[1][FSL105_labels.length];
    
                tfliteFSL105.run(inputBuffer, output);
    
                Log.d("SENYAS_DIAG",
                        "Output classes: " + output[0].length
                                + ", label count: " + FSL105_labels.length);
    
                // Find the highest-scoring prediction.
                int bestIndex = 0;
                float bestScore = output[0][0];
                float minScore = output[0][0];
                float maxScore = output[0][0];
                double outputSum = 0.0;
    
                for (int i = 0; i < output[0].length; i++) {
                    float score = output[0][i];
    
                    outputSum += score;
                    minScore = Math.min(minScore, score);
                    maxScore = Math.max(maxScore, score);
    
                    if (score > bestScore) {
                        bestScore = score;
                        bestIndex = i;
                    }
                }
    
                String predictedLabel = FSL105_labels[bestIndex];
    
                Log.d("SENYAS_FSL105",
                        "Output diagnostics: min=" + minScore
                                + ", max=" + maxScore
                                + ", sum=" + outputSum
                                + ", classes=" + output[0].length);
    
                Log.d("SENYAS_FSL105",
                        "Top prediction: " + predictedLabel
                                + ", raw output=" + bestScore);
    
                // Log the five highest-scoring classes.
                boolean[] alreadyLogged = new boolean[output[0].length];
    
                for (int rank = 1;
                     rank <= Math.min(5, output[0].length);
                     rank++) {
    
                    int topIndex = -1;
                    float topScore = -Float.MAX_VALUE;
    
                    for (int i = 0; i < output[0].length; i++) {
                        if (!alreadyLogged[i] && output[0][i] > topScore) {
                            topScore = output[0][i];
                            topIndex = i;
                        }
                    }
    
                    if (topIndex >= 0) {
                        alreadyLogged[topIndex] = true;
    
                        Log.d("SENYAS_FSL105",
                                "Rank " + rank
                                        + ": " + FSL105_labels[topIndex]
                                        + " = " + topScore);
                    }
                }
    
    
                final String prediction = predictedLabel;
    
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (isAdded()
                                && currentModelIndex == 3
                                && predictionText != null) {
                            predictionText.setText(prediction);
                            predictionText.setVisibility(View.VISIBLE);
                        }
                    });
                }
    
    
            } catch (Exception e) {
                Log.e(
                        "SENYAS_FSL105",
                        "Video prediction failed",
                        e
                );
    
    
            } finally {
                if (resizedBitmap != null
                        && resizedBitmap != rotatedBitmap
                        && resizedBitmap != bitmap) {
                    resizedBitmap.recycle();
                }
    
                if (rotatedBitmap != null
                        && rotatedBitmap != bitmap
                        && rotatedBitmap != resizedBitmap) {
                    rotatedBitmap.recycle();
                }
    
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
    
                // Release the camera frame so CameraX can provide the next one.
                imageProxy.close();
            }
        }
    
        private void initializeHandLandmarker() {
            try {
                BaseOptions baseOptions = BaseOptions.builder()
                        .setModelAssetPath("hand_landmarker.task")
                        .build();
    
                HandLandmarker.HandLandmarkerOptions options =
                        HandLandmarker.HandLandmarkerOptions.builder()
                                .setBaseOptions(baseOptions)
                                .setRunningMode(RunningMode.IMAGE)
                                .setNumHands(1)
                                .build();
    
                handLandmarker = HandLandmarker.createFromOptions(
                        requireContext(),
                        options
                );
    
                Log.d("SENYAS_HAND", "Hand detector initialized successfully");
    
            } catch (Exception e) {
                Log.e("SENYAS_HAND", "Failed to initialize hand detector", e);
            }
        }
    
        private Bitmap rotateBitmapForHandDetection(
                Bitmap source,
                int rotationDegrees
        ) {
            if (source == null || rotationDegrees == 0) {
                return source;
            }
    
            Matrix matrix = new Matrix();
            matrix.postRotate(rotationDegrees);
    
            return Bitmap.createBitmap(
                    source,
                    0,
                    0,
                    source.getWidth(),
                    source.getHeight(),
                    matrix,
                    true
            );
        }

    private static float[] extractTransactionalFeatures(float[][] sequence) {
        final int frameCount = 75;
        final int valuesPerFrame = 225;
        final int segmentCount = 5;
        final int expectedFeatureCount = 2475;

        if (sequence == null || sequence.length != frameCount) {
            throw new IllegalArgumentException(
                    "Transactional input must contain exactly 75 frames."
            );
        }

        for (float[] frame : sequence) {
            if (frame == null || frame.length != valuesPerFrame) {
                throw new IllegalArgumentException(
                        "Each frame must contain exactly 225 values."
                );
            }
        }

        float[] features = new float[expectedFeatureCount];
        int featureIndex = 0;

        // 1. Temporal mean
        for (int j = 0; j < valuesPerFrame; j++) {
            double sum = 0.0;
            for (int i = 0; i < frameCount; i++) {
                sum += sequence[i][j];
            }
            features[featureIndex++] = (float) (sum / frameCount);
        }

        // 2. Population standard deviation (NumPy std default: ddof=0)
        for (int j = 0; j < valuesPerFrame; j++) {
            double mean = 0.0;
            for (int i = 0; i < frameCount; i++) {
                mean += sequence[i][j];
            }
            mean /= frameCount;

            double squaredSum = 0.0;
            for (int i = 0; i < frameCount; i++) {
                double delta = sequence[i][j] - mean;
                squaredSum += delta * delta;
            }
            features[featureIndex++] =
                    (float) Math.sqrt(squaredSum / frameCount);
        }

        // 3. Temporal minimum
        for (int j = 0; j < valuesPerFrame; j++) {
            float min = Float.POSITIVE_INFINITY;
            for (int i = 0; i < frameCount; i++) {
                min = Math.min(min, sequence[i][j]);
            }
            features[featureIndex++] = min;
        }

        // 4. Temporal maximum
        for (int j = 0; j < valuesPerFrame; j++) {
            float max = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < frameCount; i++) {
                max = Math.max(max, sequence[i][j]);
            }
            features[featureIndex++] = max;
        }

        // 5. Means of five consecutive temporal segments.
        // Match numpy.array_split(sequence, 5, axis=0):
        // segment sizes are 15, 15, 15, 15, 15 for 75 frames.
        for (int segment = 0; segment < segmentCount; segment++) {
            int start = segment * 15;
            int end = start + 15;

            for (int j = 0; j < valuesPerFrame; j++) {
                double sum = 0.0;
                for (int i = start; i < end; i++) {
                    sum += sequence[i][j];
                }
                features[featureIndex++] = (float) (sum / 15.0);
            }
        }

        // 6. Mean absolute difference between adjacent frames.
        for (int j = 0; j < valuesPerFrame; j++) {
            double sum = 0.0;
            for (int i = 1; i < frameCount; i++) {
                sum += Math.abs(sequence[i][j] - sequence[i - 1][j]);
            }
            features[featureIndex++] = (float) (sum / (frameCount - 1));
        }

        // 7. Population standard deviation of adjacent-frame differences.
        for (int j = 0; j < valuesPerFrame; j++) {
            double mean = 0.0;
            for (int i = 1; i < frameCount; i++) {
                mean += sequence[i][j] - sequence[i - 1][j];
            }
            mean /= (frameCount - 1);

            double squaredSum = 0.0;
            for (int i = 1; i < frameCount; i++) {
                double delta =
                        (sequence[i][j] - sequence[i - 1][j]) - mean;
                squaredSum += delta * delta;
            }

            features[featureIndex++] =
                    (float) Math.sqrt(squaredSum / (frameCount - 1));
        }

        if (featureIndex != expectedFeatureCount) {
            throw new IllegalStateException(
                    "Expected 2475 features, generated " + featureIndex
            );
        }

        return features;
    }

    private void loadTransactionalModel() {
        try {
            if (transactionalOrtEnvironment != null &&
                    transactionalOrtSession != null) {
                return;
            }

            transactionalOrtEnvironment = OrtEnvironment.getEnvironment();

            try (java.io.InputStream inputStream =
                         requireContext().getAssets().open(
                                 "senyas_transactional_svm_v3.onnx")) {

                byte[] modelBytes = inputStream.readAllBytes();

                transactionalOrtSession =
                        transactionalOrtEnvironment.createSession(
                                modelBytes,
                                new OrtSession.SessionOptions());
            }

            android.util.Log.i(
                    "SENYAS_TRANSACTIONAL",
                    "ONNX model loaded successfully. Inputs: "
                            + transactionalOrtSession.getInputNames()
                            + ", Outputs: "
                            + transactionalOrtSession.getOutputNames());

        } catch (Exception e) {
            android.util.Log.e(
                    "SENYAS_TRANSACTIONAL",
                    "Failed to load ONNX model",
                    e);

            transactionalOrtSession = null;
        }
    }

}
    
