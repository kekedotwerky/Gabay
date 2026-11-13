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
import com.example.gabay.activities.MainActivity;
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
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class GabAIPage extends Fragment {

    private PreviewView cameraPreview;

    private OrtEnvironment transactionalOrtEnvironment;

    private OrtSession transactionalOrtSession;

    private View view;
    private ExecutorService cameraExecutor;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;
    private int currentImageSize = 224;

    private TextView predictionText, modelIndicatorText, instructionText;
    private Interpreter tfliteBasicSigns;
    private Interpreter tfliteFSLAlphabet;
    private Interpreter tfliteNumbers10;
    private Interpreter tfliteFSL105;
    private Interpreter currentModel;

    private HandLandmarker handLandmarker;

    // =====================================================================
    // ALPHABET MODEL (fsl_alphabet_model.tflite) SETTINGS
    //
    // Model facts (read directly from the .tflite file):
    //   - Input : [1, 224, 224, 3] float32, RGB, NHWC
    //   - The model ALREADY contains its own preprocessing (x / 127.5 - 1),
    //     so the app must feed RAW pixel values 0..255. Do NOT divide by 255.
    //   - Output: [1, 26] float32, already softmax probabilities
    //   - Classes: A..Z in alphabetical order (labels.txt)
    // =====================================================================
    private static final int ALPHABET_CLASS_COUNT = 26;
    private static final int ALPHABET_INPUT_SIZE = 224;

    // Portion of the shorter camera side used as the center box.
    private static final float ALPHABET_CROP_PERCENTAGE = 0.5f;

    // Show a letter only when the averaged confidence reaches this value.
    // Raise it (e.g. 0.70f) for fewer wrong letters, lower it (e.g. 0.50f)
    // if letters appear too rarely.
    private static final float ALPHABET_CONFIDENCE_THRESHOLD = 0.60f;

    // Average the last N frames to remove flicker between letters.
    private static final int ALPHABET_SMOOTHING_FRAMES = 3;

    // Mirror the image for the front camera (same as the preview).
    // If letters are consistently wrong only on the front camera, try false.
    private static final boolean ALPHABET_MIRROR_FRONT_CAMERA = true;

    private static final String ALPHABET_LABELS_ASSET = "handsign_outputs/labels.txt";
    private static final String ALPHABET_MODEL_ASSET = "handsign_outputs/handsign_landmark_mlp.tflite";

    private static final String[] DEFAULT_ALPHABET_LABELS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"
    };

    private String[] FSL_alphabet_labels = DEFAULT_ALPHABET_LABELS.clone();
    private ByteBuffer alphabetInputBuffer;
    private String alphabetLoadError = null;
    private String alphabetAssetName = ALPHABET_MODEL_ASSET;
    private final ArrayDeque<float[]> alphabetProbHistory = new ArrayDeque<>();

    /**
     * Finds the alphabet model in assets. Prefers fsl_alphabet_model.tflite,
     * but also accepts a renamed copy such as fsl_alphabet_model_1.tflite.
     */
    private String findAlphabetModelAsset() {
        if (getContext() == null) return ALPHABET_MODEL_ASSET;
        try {
            String[] names = getContext().getAssets().list("");
            if (names != null) {
                for (String name : names) {
                    if (ALPHABET_MODEL_ASSET.equals(name)) return name;
                }
                for (String name : names) {
                    if (name.startsWith("fsl_alphabet") && name.endsWith(".tflite")) {
                        Log.w("TFLite", "Using alphabet model asset: " + name);
                        return name;
                    }
                }
            }
        } catch (Throwable e) {
            Log.w("TFLite", "Could not list assets", e);
        }
        return ALPHABET_MODEL_ASSET;
    }

    /** Turns a load failure into a short message that can be shown on screen. */
    private String describeAlphabetLoadError(String assetName, Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : "";

        if (message.contains("Didn't find op") || message.contains("older version of this builtin")) {
            return "TensorFlow Lite library is too old. Use org.tensorflow:tensorflow-lite:2.17.0 or newer in build.gradle.";
        }
        if (e instanceof java.io.FileNotFoundException) {
            return assetName + " was not found in the assets folder.";
        }
        if (message.contains("not a valid TensorFlow Lite model")) {
            return assetName + " is corrupted or is not a valid .tflite file.";
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    private String[] loadLabels(String fileName) throws IOException {
        if (getContext() == null) {
            throw new IOException("Context is null");
        }
        try (InputStream input = getContext()
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

    private String[] loadTextLabels(String fileName) throws IOException {
        if (getContext() == null) {
            throw new IOException("Context is null");
        }
        java.util.List<String> labelsList = new java.util.ArrayList<>();
        try (InputStream inputStream = getContext().getAssets().open(fileName);
             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Remove a possible UTF-8 BOM, then whitespace.
                String trimmed = line.replace("\uFEFF", "").trim();
                if (!trimmed.isEmpty()) {
                    labelsList.add(trimmed);
                }
            }
        }
        return labelsList.toArray(new String[0]);
    }

    /**
     * Loads alphabet_labels.txt (26 lines, A..Z). If the file is missing or
     * does not contain exactly 26 labels, falls back to A..Z in order.
     */
    private String[] loadAlphabetLabels() {
        try {
            String[] loaded = loadTextLabels(ALPHABET_LABELS_ASSET);
            if (loaded.length == ALPHABET_CLASS_COUNT) {
                Log.d("TFLite", "Loaded " + loaded.length + " alphabet labels from " + ALPHABET_LABELS_ASSET);
                return loaded;
            }
            Log.w("TFLite", ALPHABET_LABELS_ASSET + " has " + loaded.length
                    + " labels, expected " + ALPHABET_CLASS_COUNT + ". Using default A-Z.");
        } catch (Throwable e) {
            Log.w("TFLite", "Could not read " + ALPHABET_LABELS_ASSET + ". Using default A-Z.", e);
        }
        return DEFAULT_ALPHABET_LABELS.clone();
    }

    private String[] FSL105_labels;
    private final String[] FSL_BasicSigns_labels = {"A", "C", "I", "MAHAL KITA", "N"};
    private final ArrayDeque<float[]> fsl105Frames = new ArrayDeque<>();
    private static final long FSL105_SAMPLE_INTERVAL_MS = 250;
    private long fsl105LastSampleTimeMs = 0L;
    private static final int FSL105_FRAME_COUNT = 16;
    private static final int FSL105_IMAGE_SIZE = 224;
    private final String[] FSL_numbers10_labels = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};
    private int currentModelIndex = 0; // 0=Basic, 1=alphabet, 2=numbers, 3=FSL-105
    private String[] currentLabels;
    private Button switchModelButton;
    private ImageButton switchCameraButton;
    private int currentLensFacing = CameraSelector.LENS_FACING_FRONT;
    private ProcessCameraProvider cameraProvider;

    // Frame rate limiting
    private long lastAnalysisTime = 0;
    private static final long MIN_TIME_BETWEEN_FRAMES = 100; // 10 FPS for fluid real-time detection
    private static final float CONFIDENCE_THRESHOLD = 0.45f; // Used by Basic Signs and Numbers modes

    private static final int STABILITY_WINDOW = 3;
    private static final int REQUIRED_MATCHES = 2;

    private final ArrayDeque<String> recentPredictions =
            new ArrayDeque<>();

    private int stabilityModelIndex = -1;

    // Bounding box configuration (Basic Signs and Numbers modes)
    private static final float CROP_PERCENTAGE = 0.5f; // use center 50% of the image

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Camera permission is required", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private boolean checkCameraPermission() {
        if (getContext() == null) return false;
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
    }

    private java.nio.ByteBuffer loadModelFile(String modelName) throws IOException {
        if (getContext() == null) {
            throw new IOException("Context is null");
        }
        try {
            AssetFileDescriptor fileDescriptor = getContext().getAssets().openFd(modelName);
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        } catch (Throwable e) {
            try (InputStream inputStream = getContext().getAssets().open(modelName)) {
                int size = inputStream.available();
                byte[] bytes = new byte[size];
                int read = 0;
                while (read < size) {
                    int r = inputStream.read(bytes, read, size - read);
                    if (r == -1) break;
                    read += r;
                }
                java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.allocateDirect(bytes.length);
                byteBuffer.order(java.nio.ByteOrder.nativeOrder());
                byteBuffer.put(bytes);
                byteBuffer.rewind();
                return byteBuffer;
            }
        }
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

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);

        try {
            tfliteBasicSigns = new Interpreter(loadModelFile("model_unquant.tflite"), options);
        } catch (Throwable e) {
            Log.e("TFLite", "Failed to load model_unquant.tflite: " + e.getMessage(), e);
        }

        try {
            alphabetAssetName = findAlphabetModelAsset();
            tfliteFSLAlphabet = new Interpreter(loadModelFile(alphabetAssetName), options);
        } catch (Throwable e) {
            // If the message says: "Didn't find op for builtin opcode
            // 'FULLY_CONNECTED' version '12'", the TensorFlow Lite library in
            // build.gradle is too old. This model needs 2.17.0 or newer.
            Log.e("TFLite", "Failed to load fsl_alphabet_model.tflite: " + e.getMessage(), e);
            alphabetLoadError = describeAlphabetLoadError(alphabetAssetName, e);
            if (getContext() != null) {
                Toast.makeText(getContext(), "Alphabet model error: " + alphabetLoadError, Toast.LENGTH_LONG).show();
            }
        }

        try {
            tfliteNumbers10 = new Interpreter(loadModelFile("fsl_numbers10_model.tflite"), options);
        } catch (Throwable e) {
            Log.e("TFLite", "Failed to load fsl_numbers10_model.tflite: " + e.getMessage(), e);
        }

        try {
            tfliteFSL105 = new Interpreter(loadModelFile("senyas_fsl105_candidate_52_58.tflite"), options);
        } catch (Throwable e) {
            Log.e("TFLite", "Failed to load senyas_fsl105_candidate_52_58.tflite: " + e.getMessage(), e);
        }

        try {
            FSL105_labels = loadLabels("senyas_fsl105_labels.json");
        } catch (Throwable e) {
            Log.e("TFLite", "Failed to load senyas_fsl105_labels.json: " + e.getMessage(), e);
        }

        FSL_alphabet_labels = loadAlphabetLabels();

        if (tfliteFSL105 != null) {
            debugModelInfo(tfliteFSL105, "SENYAS FSL-105");
        }
        if (tfliteBasicSigns != null) {
            debugModelInfo(tfliteBasicSigns, "Model 1 (Basic Signs)");
        }
        if (tfliteFSLAlphabet != null) {
            debugModelInfo(tfliteFSLAlphabet, "Model 2 (Alphabet)");
        }
        if (tfliteNumbers10 != null) {
            debugModelInfo(tfliteNumbers10, "Model 3 (Numbers)");
        }

        if (tfliteBasicSigns != null || tfliteFSLAlphabet != null || tfliteNumbers10 != null || tfliteFSL105 != null) {
            switchToModel(1); // Default to Alphabet Mode (fsl_alphabet_model.tflite)
            Log.d("TFLite", "AI models initialized — Alphabet mode set as default");
        } else {
            if (predictionText != null) {
                predictionText.setText("Failed to load AI models");
            }
            if (getContext() != null) {
                Toast.makeText(getContext(), "Failed to load AI models", Toast.LENGTH_LONG).show();
            }
        }

        // Switch model button listener
        switchModelButton.setOnClickListener(v -> {
            switchToNextModel();
        });

        // Switch camera button listener
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

        // The image orientation changes with the camera, so drop old averages.
        clearAlphabetHistory();

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
                        95,
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

        } catch (Throwable e) {
            Log.e("ImageConversion", "Error converting camera image", e);
            return null;
        }
    }


    // Used by Basic Signs and Numbers modes (unchanged behaviour: pixels in [0, 1]).
    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap, Interpreter model, int rotationDegrees) {
        int imageSize = currentImageSize;
        boolean useCHW = false;
        if (model != null) {
            try {
                int[] shape = model.getInputTensor(0).shape();
                if (shape.length == 4 && shape[1] == 3) {
                    useCHW = true;
                }
            } catch (Exception ignored) {}
        }

        // Calculate center crop dimensions
        int minDimension = Math.min(bitmap.getWidth(), bitmap.getHeight());
        int cropSize = (int) (minDimension * CROP_PERCENTAGE);
        int left = (bitmap.getWidth() - cropSize) / 2;
        int top = (bitmap.getHeight() - cropSize) / 2;

        Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, left, top, cropSize, cropSize);
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, imageSize, imageSize, true);
        Matrix matrix = new Matrix();

        // Dynamically rotate based on camera sensor rotation and front/back mirroring
        matrix.postRotate(rotationDegrees);
        if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
            matrix.postScale(-1, 1);
        }

        Bitmap processedBitmap = Bitmap.createBitmap(scaledBitmap, 0, 0,
                scaledBitmap.getWidth(), scaledBitmap.getHeight(), matrix, true);
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * 3);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[imageSize * imageSize];
        processedBitmap.getPixels(pixels, 0, imageSize, 0, 0, imageSize, imageSize);

        // Normalize to [0, 1] range
        if (useCHW) {
            // Write planar R, then G, then B for NCHW models.
            for (int pixelValue : pixels) {
                buffer.putFloat(((pixelValue >> 16) & 0xFF) / 255.0f);
            }
            for (int pixelValue : pixels) {
                buffer.putFloat(((pixelValue >> 8) & 0xFF) / 255.0f);
            }
            for (int pixelValue : pixels) {
                buffer.putFloat((pixelValue & 0xFF) / 255.0f);
            }
        } else {
            for (int pixelValue : pixels) {
                buffer.putFloat(((pixelValue >> 16) & 0xFF) / 255.0f);
                buffer.putFloat(((pixelValue >> 8) & 0xFF) / 255.0f);
                buffer.putFloat((pixelValue & 0xFF) / 255.0f);
            }
        }
        croppedBitmap.recycle();
        if (scaledBitmap != croppedBitmap) scaledBitmap.recycle();
        if (processedBitmap != scaledBitmap) processedBitmap.recycle();

        return buffer;
    }

    /**
     * Builds the input for fsl_alphabet_model.tflite.
     *
     * IMPORTANT: this model already does (x / 127.5 - 1) inside the graph, so
     * the values written here are the RAW RGB pixel values 0..255 (as float).
     * Dividing by 255 here would make the model see almost-black images and
     * give wrong letters.
     */
    private ByteBuffer convertLandmarksToByteBuffer(java.util.List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> landmarks) {
        float[] centered = new float[63];
        float wristX = landmarks.get(0).x();
        float wristY = landmarks.get(0).y();
        float wristZ = landmarks.get(0).z();

        for (int i = 0; i < 21; i++) {
            com.google.mediapipe.tasks.components.containers.NormalizedLandmark lm = landmarks.get(i);
            centered[i * 3] = lm.x() - wristX;
            centered[i * 3 + 1] = lm.y() - wristY;
            centered[i * 3 + 2] = lm.z() - wristZ;
        }

        float maxDist = 0f;
        for (int i = 0; i < 21; i++) {
            float x = centered[i * 3];
            float y = centered[i * 3 + 1];
            float dist = (float) Math.sqrt(x * x + y * y);
            if (dist > maxDist) {
                maxDist = dist;
            }
        }
        if (maxDist < 1e-6f) maxDist = 1.0f;

        if (alphabetInputBuffer == null) {
            alphabetInputBuffer = ByteBuffer.allocateDirect(4 * 63);
            alphabetInputBuffer.order(ByteOrder.nativeOrder());
        }
        alphabetInputBuffer.clear();
        for (int i = 0; i < 63; i++) {
            alphabetInputBuffer.putFloat(centered[i] / maxDist);
        }
        alphabetInputBuffer.rewind();
        return alphabetInputBuffer;
    }

    private void clearAlphabetHistory() {
        synchronized (alphabetProbHistory) {
            alphabetProbHistory.clear();
        }
    }

    /** Averages the last few probability vectors to remove flicker. */
    private float[] smoothAlphabetProbabilities(float[] latest) {
        float[] average = new float[latest.length];

        synchronized (alphabetProbHistory) {
            alphabetProbHistory.addLast(latest.clone());

            while (alphabetProbHistory.size() > ALPHABET_SMOOTHING_FRAMES) {
                alphabetProbHistory.removeFirst();
            }

            for (float[] probs : alphabetProbHistory) {
                for (int i = 0; i < average.length; i++) {
                    average[i] += probs[i];
                }
            }

            int count = alphabetProbHistory.size();
            for (int i = 0; i < average.length; i++) {
                average[i] /= count;
            }
        }

        return average;
    }

    private void showPrediction(final String text, final int modelIndex) {
        androidx.fragment.app.FragmentActivity act = getActivity();
        if (isAdded() && act != null) {
            act.runOnUiThread(() -> {
                if (!isAdded()
                        || predictionText == null
                        || currentModelIndex != modelIndex) {
                    return;
                }

                predictionText.setText(text);
                predictionText.setVisibility(View.VISIBLE);
            });
        }
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

        // Alphabet has its own dedicated pipeline.
        if (modelIndexForFrame == 1) {
            analyzeAlphabetFrame(imageProxy);
            return;
        }

        // ---- Basic Signs (0) and Numbers (2) ----

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

                androidx.fragment.app.FragmentActivity act = getActivity();
                if (isAdded() && act != null) {
                    act.runOnUiThread(() -> {
                        if (isAdded() && predictionText != null) {
                            predictionText.setText("Hand detector unavailable");
                            predictionText.setVisibility(View.VISIBLE);
                        }
                    });
                }
                return;
            }

            boolean handDetected = true; // Default true for direct inference

            if (handLandmarker != null) {
                Bitmap detectionBitmap = rotateBitmapForHandDetection(
                        bitmap,
                        imageProxy.getImageInfo().getRotationDegrees()
                );

                try {
                    MPImage mpImage = new BitmapImageBuilder(detectionBitmap).build();
                    HandLandmarkerResult handResult = handLandmarker.detect(mpImage);
                    handDetected = handResult != null && !handResult.landmarks().isEmpty();
                } catch (Throwable e) {
                    Log.e("SENYAS_HAND", "Hand detection failed: " + e.getMessage());
                    handDetected = true; // fallback to true
                } finally {
                    if (detectionBitmap != bitmap && !detectionBitmap.isRecycled()) {
                        detectionBitmap.recycle();
                    }
                }
            }

            if (!handDetected) {
                Log.d("SENYAS_HAND", "No hand detected, proceeding with inference...");
            }

            // Dynamically detect tensor shape (NCHW vs NHWC) for any model.
            ByteBuffer input = convertBitmapToByteBuffer(bitmap, modelForFrame, rotation);

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

                showPrediction("Model output mismatch", modelIndexForFrame);
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

            showPrediction(displayText, modelIndexForFrame);

        } catch (Exception e) {
            Log.e("TFLite", "Frame prediction failed", e);

        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }

            imageProxy.close();
        }
    }

    /**
     * Alphabet pipeline (fsl_alphabet_model.tflite).
     *  - Does not need the hand detector, so it can never be blocked by it.
     *  - Feeds raw 0..255 RGB values (the model normalizes internally).
     *  - Averages the last few frames and only shows a letter when confident.
     *  - Always shows a status (letter, "Hold steady", or an error). Never "...".
     */
    private void analyzeAlphabetFrame(ImageProxy imageProxy) {
        final int modelIndex = 1;
        Bitmap bitmap = null;

        try {
            final Interpreter model = tfliteFSLAlphabet;
            final String[] labels = FSL_alphabet_labels;

            if (model == null) {
                Log.e("SENYAS_ALPHABET", "Alphabet model is null. Load error: " + alphabetLoadError);
                showPrediction("Model error", modelIndex);
                return;
            }

            if (labels == null || labels.length != ALPHABET_CLASS_COUNT) {
                Log.e("SENYAS_ALPHABET", "Alphabet labels invalid");
                showPrediction("Alphabet labels invalid", modelIndex);
                return;
            }

            if (handLandmarker == null) {
                showPrediction("Hand detector unavailable", modelIndex);
                return;
            }

            bitmap = toBitmap(imageProxy);

            if (bitmap == null) {
                Log.e("SENYAS_ALPHABET", "Bitmap conversion failed");
                return;
            }

            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            Bitmap detectionBitmap = rotateBitmapForHandDetection(bitmap, rotation);

            MPImage mpImage = new BitmapImageBuilder(detectionBitmap).build();
            HandLandmarkerResult handResult = handLandmarker.detect(mpImage);

            if (detectionBitmap != bitmap && !detectionBitmap.isRecycled()) {
                detectionBitmap.recycle();
            }

            if (handResult == null || handResult.landmarks().isEmpty()) {
                showPrediction("Show hand in center box", modelIndex);
                return;
            }

            java.util.List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> landmarks = handResult.landmarks().get(0);
            ByteBuffer input = convertLandmarksToByteBuffer(landmarks);

            float[][] output = new float[1][ALPHABET_CLASS_COUNT];
            model.run(input, output);

            float[] smoothed = smoothAlphabetProbabilities(output[0]);
            int bestIndex = getMaxProbIndex(smoothed);
            float confidence = smoothed[bestIndex];

            String displayText;

            if (confidence >= ALPHABET_CONFIDENCE_THRESHOLD) {
                displayText = String.format(
                        java.util.Locale.US,
                        "%s (%.1f%%)",
                        labels[bestIndex],
                        confidence * 100f
                );
            } else {
                displayText = String.format(
                        java.util.Locale.US,
                        "%s (%.1f%%) - Hold steady",
                        labels[bestIndex],
                        confidence * 100f
                );
            }

            Log.d("SENYAS_ALPHABET",
                    "best=" + labels[bestIndex]
                            + ", avgConfidence=" + confidence
                            + ", rawBest=" + labels[getMaxProbIndex(output[0])]
                            + ", rawConfidence=" + output[0][getMaxProbIndex(output[0])]
                            + ", rotation=" + rotation);

            showPrediction(displayText, modelIndex);

        } catch (Throwable e) {
            Log.e("SENYAS_ALPHABET", "Alphabet prediction failed", e);
            showPrediction("Alphabet error: " + e.getClass().getSimpleName(), modelIndex);

        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }

            imageProxy.close();
        }
    }

    private void switchToModel(int modelIndex) {
        currentModelIndex = Math.abs(modelIndex) % 4;
        synchronized (fsl105Frames) {
            fsl105Frames.clear();
            fsl105LastSampleTimeMs = 0L;
        }
        clearAlphabetHistory();
        recentPredictions.clear();

        switch (currentModelIndex) {
            case 0: // basic signs
                currentModel = tfliteBasicSigns;
                currentLabels = FSL_BasicSigns_labels;
                currentImageSize = 224;
                if (modelIndicatorText != null) modelIndicatorText.setText("Basic Signs Mode");
                if (instructionText != null) instructionText.setText("Place your hand in the center box and show a basic sign");
                if (switchModelButton != null) switchModelButton.setText("Switch model to Alphabet");
                Log.d("ModelSwitch", "Switched to Basic Signs - " + FSL_BasicSigns_labels.length + " labels");
                break;

            case 1: // fsl alphabet
                currentModel = tfliteFSLAlphabet;
                currentLabels = FSL_alphabet_labels;
                currentImageSize = ALPHABET_INPUT_SIZE;
                if (modelIndicatorText != null) modelIndicatorText.setText("Alphabet Mode");
                if (instructionText != null) instructionText.setText("Place your hand in the center box and show a letter (A-Z)");
                if (switchModelButton != null) switchModelButton.setText("Switch model to Numbers");
                Log.d("ModelSwitch", "Switched to Alphabet - " + (FSL_alphabet_labels != null ? FSL_alphabet_labels.length : 0) + " labels");
                break;

            case 2: // numbers 0-9
                currentModel = tfliteNumbers10;
                currentLabels = FSL_numbers10_labels;
                currentImageSize = 224;
                if (modelIndicatorText != null) modelIndicatorText.setText("Numbers Mode");
                if (instructionText != null) instructionText.setText("Place your hand in the center box and show a number (0-9)");
                if (switchModelButton != null) switchModelButton.setText("Switch model to FSL-105 Video Signs");
                Log.d("ModelSwitch", "Switched to Numbers - " + FSL_numbers10_labels.length + " labels");
                break;

            case 3: // FSL-105 video model
                currentModel = tfliteFSL105;
                currentLabels = FSL105_labels;
                currentImageSize = 224;

                synchronized (fsl105Frames) {
                    fsl105Frames.clear();
                }

                if (modelIndicatorText != null) modelIndicatorText.setText("FSL-105 Video Signs");
                if (instructionText != null) instructionText.setText("Perform a sign and wait for 16 video frames to be analyzed");
                if (switchModelButton != null) switchModelButton.setText("Switch model to Basic Signs");

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
        if (predictionText != null) {
            if (currentModelIndex == 1) {
                // Alphabet mode: no "..." placeholder.
                if (tfliteFSLAlphabet == null) {
                    predictionText.setText("Model error");
                    if (instructionText != null) {
                        instructionText.setText("Alphabet model error: "
                                + (alphabetLoadError != null ? alphabetLoadError : "unknown"));
                    }
                } else {
                    predictionText.setText("");
                }
            } else {
                predictionText.setText("...");
            }
        }
        lastAnalysisTime = 0;
    }

    private void switchToNextModel() {
        switchToModel((currentModelIndex + 1) % 4);
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
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showBottomNav();
            ((MainActivity) getActivity()).hideActionBar();
        }
        if (checkCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }
    }

    private void startCamera() {
        if (getContext() == null || !isAdded()) return;

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(getContext());

        cameraProviderFuture.addListener(() -> {
            try {
                if (getContext() == null || !isAdded() || getView() == null) {
                    return;
                }

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

                if (cameraPreview != null && cameraPreview.getSurfaceProvider() != null) {
                    preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());
                    cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageAnalysis);
                    Log.d("CameraX", "Camera started successfully with lens facing: " + currentLensFacing);
                }

            } catch (Exception e) {
                Log.e("CameraX", "Error starting camera: " + e.getMessage(), e);
                if (getContext() != null) {
                    Toast.makeText(getContext(), "Error starting camera", Toast.LENGTH_LONG).show();
                }
            }
        }, ContextCompat.getMainExecutor(getContext()));
    }

    @Override
    public void onPause() {
        super.onPause();
        stopCameraSafely();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopCameraSafely();
        if (handLandmarker != null) {
            try {
                handLandmarker.close();
            } catch (Throwable ignored) {}
            handLandmarker = null;
        }
        if (transactionalOrtSession != null) {
            try {
                transactionalOrtSession.close();
            } catch (Throwable ignored) {}
            transactionalOrtSession = null;
        }
        if (transactionalOrtEnvironment != null) {
            try {
                transactionalOrtEnvironment.close();
            } catch (Throwable ignored) {}
            transactionalOrtEnvironment = null;
        }
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
        closeModelSafely(tfliteFSL105);

        Log.d("GabAIPage", "All models closed");
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
        if (getContext() == null) return;
        try {
            ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(getContext()).get();
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

            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> {
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
        if (getContext() == null) return;
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
                    getContext(),
                    options
            );

            Log.d("SENYAS_HAND", "Hand detector initialized successfully");

        } catch (Throwable e) {
            Log.e("SENYAS_HAND", "Failed to initialize hand detector", e);
            handLandmarker = null;
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
        if (getContext() == null) return;
        try {
            if (transactionalOrtEnvironment != null &&
                    transactionalOrtSession != null) {
                return;
            }

            transactionalOrtEnvironment = OrtEnvironment.getEnvironment();

            try (java.io.InputStream inputStream =
                         getContext().getAssets().open(
                                 "senyas_transactional_svm_v3.onnx");
                 java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {

                int nRead;
                byte[] data = new byte[16384];
                while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                byte[] modelBytes = buffer.toByteArray();

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

        } catch (Throwable e) {
            android.util.Log.e(
                    "SENYAS_TRANSACTIONAL",
                    "Failed to load ONNX model",
                    e);

            transactionalOrtSession = null;
        }
    }

}