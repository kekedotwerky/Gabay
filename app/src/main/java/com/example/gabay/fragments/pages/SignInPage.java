package com.example.gabay.fragments.pages;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.fragment.app.Fragment;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.example.gabay.R;
import com.example.gabay.activities.AuthActivity;
import com.example.gabay.activities.MainActivity;
import com.example.gabay.services.SupabaseJavaService;
import com.example.gabay.services.SupabaseHelper;

import org.json.JSONObject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.regex.Pattern;

public class SignInPage extends Fragment {

    private EditText emailInput, passwordInput;
    private Button signInButton;
    private ImageButton signInGoogle, passwordShowToggle;
    private TextView tvCreateAcc, forgotPass;
    private CheckBox checkBox_rememberMe;
    private TextView emailError, passwordError, errorMessage;

    private SharedPreferences sharedPreferences;
    private SharedPreferences securePreferences;
    private static final String PREFS_NAME = "AuthPrefs";
    private static final String SECURE_PREFS_NAME = "SecureAuthPrefs";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_REMEMBER_ME = "rememberMe";
    private static final String KEY_FAILED_ATTEMPTS = "failed_attempts";
    private static final String KEY_LAST_ATTEMPT_TIME = "last_attempt_time";

    // Rate limiting constants
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION = 15 * 60 * 1000; // 15 minutes
    private static final long ATTEMPT_RESET_TIME = 60 * 60 * 1000; // 1 hour
    private static final long SIGN_IN_COOLDOWN = 2000; // 2 seconds between attempts

    // Input validation
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$"
    );
    private static final int MAX_EMAIL_LENGTH = 254; // RFC 5321
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final int MIN_PASSWORD_LENGTH = 6;

    private long lastSignInAttempt = 0;
    private boolean isSigningIn = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_sign_in, container, false);

        initializeViews(view);
        initializeSecureStorage();
        loadSavedCredentials();
        setupListeners();

        return view;
    }

    private void initializeViews(View view) {
        emailInput = view.findViewById(R.id.signIn_email);
        passwordInput = view.findViewById(R.id.signIn_Password);
        signInButton = view.findViewById(R.id.signIn_button);
        tvCreateAcc = view.findViewById(R.id.tv_createAcc);
        signInGoogle = view.findViewById(R.id.signIn_google);
        forgotPass = view.findViewById(R.id.tv_forgotPass);
        checkBox_rememberMe = view.findViewById(R.id.checkBox_rememberMe);
        passwordShowToggle = view.findViewById(R.id.password_toggle_show);
        emailError = view.findViewById(R.id.signIn_email_error);
        passwordError = view.findViewById(R.id.signIn_password_error);
        errorMessage = view.findViewById(R.id.signIn_error_message);

        sharedPreferences = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void initializeSecureStorage() {
        try {
            MasterKey masterKey = new MasterKey.Builder(requireContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            securePreferences = EncryptedSharedPreferences.create(
                    requireContext(),
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e("SignInPage", "Failed to initialize encrypted storage: " + e.getMessage());
            // Fallback to regular SharedPreferences (not recommended for production)
            securePreferences = sharedPreferences;
        }
    }

    private void setupListeners() {
        setupPasswordToggle();
        setupRealTimeValidation();

        signInButton.setOnClickListener(v -> handleSignIn());

        tvCreateAcc.setOnClickListener(v -> {
            if (getActivity() instanceof AuthActivity) {
                ((AuthActivity) getActivity()).loadSignUpFragment();
            }
        });

        signInGoogle.setOnClickListener(v -> {
            signInGoogle.setEnabled(false);
            if (getActivity() instanceof AuthActivity) {
                ((AuthActivity) getActivity()).signInWithGoogle();
            }
            signInGoogle.postDelayed(() -> signInGoogle.setEnabled(true), 2000);
        });

        forgotPass.setOnClickListener(v -> handleForgotPassword());
    }

    private void setupPasswordToggle() {
        passwordShowToggle.setOnClickListener(v -> {
            int selection = passwordInput.getSelectionEnd();

            if (passwordInput.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod) {
                passwordInput.setTransformationMethod(android.text.method.HideReturnsTransformationMethod.getInstance());
                passwordShowToggle.setImageResource(R.drawable.ic_eye);
            } else {
                passwordInput.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
                passwordShowToggle.setImageResource(R.drawable.ic_eyeoff);
            }

            passwordInput.setSelection(selection);
        });
    }

    private void setupRealTimeValidation() {
        TextWatcher validationWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                clearErrors();
                validateFieldsRealTime();
            }
        };

        emailInput.addTextChangedListener(validationWatcher);
        passwordInput.addTextChangedListener(validationWatcher);
    }

    private void validateFieldsRealTime() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        // Email validation
        if (email.length() > 0) {
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                showFieldError(emailError, "Please enter a valid email address");
            } else if (email.length() > MAX_EMAIL_LENGTH) {
                showFieldError(emailError, "Email address is too long");
            }
        }

        // Password validation
        if (password.length() > 0 && password.length() < MIN_PASSWORD_LENGTH) {
            showFieldError(passwordError, "Password must be at least 6 characters");
        }

        // Enable button if both fields have content
        boolean hasContent = email.length() > 0 && password.length() > 0;
        signInButton.setEnabled(hasContent && !isSigningIn);
    }

    private void clearErrors() {
        hideFieldError(emailError);
        hideFieldError(passwordError);
        hideErrorMessage();
    }

    private void showFieldError(TextView errorView, String message) {
        if (errorView != null) {
            errorView.setText(message);
            errorView.setVisibility(View.VISIBLE);
        }
    }

    private void hideFieldError(TextView errorView) {
        if (errorView != null) {
            errorView.setVisibility(View.GONE);
        }
    }

    private void showErrorMessage(String message) {
        if (errorMessage != null) {
            errorMessage.setText(message);
            errorMessage.setVisibility(View.VISIBLE);
        }
    }

    private void hideErrorMessage() {
        if (errorMessage != null) {
            errorMessage.setVisibility(View.GONE);
        }
    }

    private void loadSavedCredentials() {
        try {
            boolean rememberMe = sharedPreferences.getBoolean(KEY_REMEMBER_ME, false);
            if (rememberMe) {
                String savedEmail = securePreferences.getString(KEY_EMAIL, "");
                String savedPassword = securePreferences.getString(KEY_PASSWORD, "");

                if (!savedEmail.isEmpty() && !savedPassword.isEmpty()) {
                    emailInput.setText(savedEmail);
                    passwordInput.setText(savedPassword);
                    checkBox_rememberMe.setChecked(true);
                }
            }
        } catch (Exception e) {
            Log.e("SignInPage", "Error loading saved credentials: " + e.getMessage());
            clearCredentials();
        }
    }

    private void saveCredentials(String email, String password, boolean rememberMe) {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            SharedPreferences.Editor secureEditor = securePreferences.edit();

            if (rememberMe) {
                secureEditor.putString(KEY_EMAIL, email);
                secureEditor.putString(KEY_PASSWORD, password);
                editor.putBoolean(KEY_REMEMBER_ME, true);
            } else {
                secureEditor.remove(KEY_EMAIL);
                secureEditor.remove(KEY_PASSWORD);
                editor.putBoolean(KEY_REMEMBER_ME, false);
            }

            secureEditor.apply();
            editor.apply();
        } catch (Exception e) {
            Log.e("SignInPage", "Error saving credentials: " + e.getMessage());
        }
    }

    private void clearCredentials() {
        try {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            SharedPreferences.Editor secureEditor = securePreferences.edit();

            secureEditor.remove(KEY_EMAIL);
            secureEditor.remove(KEY_PASSWORD);
            editor.remove(KEY_REMEMBER_ME);

            secureEditor.apply();
            editor.apply();
        } catch (Exception e) {
            Log.e("SignInPage", "Error clearing credentials: " + e.getMessage());
        }
    }

    private boolean checkRateLimit() {
        int failedAttempts = sharedPreferences.getInt(KEY_FAILED_ATTEMPTS, 0);
        long lastAttemptTime = sharedPreferences.getLong(KEY_LAST_ATTEMPT_TIME, 0);
        long currentTime = System.currentTimeMillis();

        // Check cooldown between attempts
        if (currentTime - lastSignInAttempt < SIGN_IN_COOLDOWN) {
            showErrorMessage("Please wait a moment before trying again");
            return false;
        }

        // Reset attempts after 1 hour
        if (currentTime - lastAttemptTime > ATTEMPT_RESET_TIME) {
            clearFailedAttempts();
            return true;
        }

        // Check if locked out
        if (failedAttempts >= MAX_ATTEMPTS) {
            long lockoutRemaining = LOCKOUT_DURATION - (currentTime - lastAttemptTime);
            if (lockoutRemaining > 0) {
                long minutesRemaining = Math.max(1, (lockoutRemaining / 1000) / 60);
                showErrorMessage("Too many failed attempts. Please try again in " + minutesRemaining + " minute(s).");
                return false;
            } else {
                clearFailedAttempts();
            }
        }

        return true;
    }

    private void recordFailedAttempt() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        int failedAttempts = sharedPreferences.getInt(KEY_FAILED_ATTEMPTS, 0);
        editor.putInt(KEY_FAILED_ATTEMPTS, failedAttempts + 1);
        editor.putLong(KEY_LAST_ATTEMPT_TIME, System.currentTimeMillis());
        editor.apply();

        // Show remaining attempts warning
        int remainingAttempts = MAX_ATTEMPTS - (failedAttempts + 1);
        if (remainingAttempts > 0 && remainingAttempts <= 2) {
            showErrorMessage("Invalid credentials. " + remainingAttempts + " attempt(s) remaining before temporary lockout.");
        }
    }

    private void clearFailedAttempts() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(KEY_FAILED_ATTEMPTS);
        editor.remove(KEY_LAST_ATTEMPT_TIME);
        editor.apply();
    }

    private String sanitizeEmail(String email) {
        if (email == null) return "";
        email = email.trim().toLowerCase();
        return email.length() > MAX_EMAIL_LENGTH ? email.substring(0, MAX_EMAIL_LENGTH) : email;
    }

    private boolean validateInput(String email, String password) {
        boolean isValid = true;

        // Email validation
        if (email.isEmpty()) {
            showFieldError(emailError, "Email is required");
            isValid = false;
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showFieldError(emailError, "Please enter a valid email address");
            isValid = false;
        } else if (!EMAIL_PATTERN.matcher(email).matches()) {
            showFieldError(emailError, "Please enter a valid email address");
            isValid = false;
        } else if (email.length() > MAX_EMAIL_LENGTH) {
            showFieldError(emailError, "Email address is too long");
            isValid = false;
        }

        // Password validation
        if (password.isEmpty()) {
            showFieldError(passwordError, "Password is required");
            isValid = false;
        } else if (password.length() < MIN_PASSWORD_LENGTH) {
            showFieldError(passwordError, "Password must be at least 6 characters");
            isValid = false;
        } else if (password.length() > MAX_PASSWORD_LENGTH) {
            showFieldError(passwordError, "Password is too long");
            isValid = false;
        }

        return isValid;
    }

    private void handleSignIn() {
        if (isSigningIn) {
            return;
        }
        String email = sanitizeEmail(emailInput.getText().toString());
        String password = passwordInput.getText().toString();

        clearErrors();

        // validate input
        if (!validateInput(email, password)) {
            return;
        }

        if (!checkRateLimit()) {
            return;
        }

        lastSignInAttempt = System.currentTimeMillis();

        setLoadingState(true);

        SupabaseHelper.signIn(email, password, new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (getActivity() == null) return;

                getActivity().runOnUiThread(() -> {
                    setLoadingState(false);
                    recordFailedAttempt();
                    showErrorMessage("Network error. Please check your connection and try again.");
                    Log.e("SignInPage", "Network failure: " + e.getMessage());
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body().string();
                int responseCode = response.code();

                if (getActivity() == null) return;

                getActivity().runOnUiThread(() -> {
                    setLoadingState(false);

                    if (response.isSuccessful()) {
                        handleSuccessfulSignIn(responseBody, email, password);
                    } else {
                        handleSignInError(responseCode, responseBody);
                    }
                });
            }
        });
    }

    private void handleSuccessfulSignIn(String responseBody, String email, String password) {
        try {
            JSONObject jsonResponse = new JSONObject(responseBody);

            // verify user email
            JSONObject user = jsonResponse.optJSONObject("user");
            if (user != null) {
                String confirmedAt = user.optString("confirmed_at", "");

                if (confirmedAt.isEmpty() || confirmedAt.equals("null")) {
                    showErrorMessage("Please verify your email address before signing in. Check your inbox for the verification link.");
                    recordFailedAttempt();
                    return;
                }
            }

            String accessToken = jsonResponse.optString("access_token");
            String refreshToken = jsonResponse.optString("refresh_token");

            if (accessToken == null || accessToken.isEmpty()) {
                Log.e("SignInPage", "No access token in response");
                showErrorMessage("Authentication failed. Please try again.");
                recordFailedAttempt();
                return;
            }

            if (user == null) {
                Log.e("SignInPage", "No user object in response");
                showErrorMessage("Authentication failed. Please try again.");
                recordFailedAttempt();
                return;
            }

            String userId = user.getString("id");

            clearFailedAttempts();

            // remember credentials
            boolean rememberMe = checkBox_rememberMe.isChecked();
            saveCredentials(email, password, rememberMe);
            saveAuthTokens(accessToken, refreshToken);

            SupabaseJavaService.setAccessToken(accessToken, userId);
            Log.d("SignInPage", "Authentication successful for user: " + userId);

            hideErrorMessage();
            navigateToMainActivity();

        } catch (Exception e) {
            Log.e("SignInPage", "Error parsing auth response: " + e.getMessage());
            recordFailedAttempt();
            showErrorMessage("Authentication error. Please try again.");
        }
    }

    private void handleSignInError(int responseCode, String responseBody) {
        recordFailedAttempt();
        String errorMsg = "Sign in failed. Please try again.";

        try {
            JSONObject errorJson = new JSONObject(responseBody);

            String errorDescription = errorJson.optString("error_description", "").toLowerCase();
            String errorMsgField = errorJson.optString("msg", "").toLowerCase();
            String errorField = errorJson.optString("error", "").toLowerCase();

            String combinedError = errorDescription + " " + errorMsgField + " " + errorField;

            // email not verified
            if (combinedError.contains("email not confirmed") ||
                    combinedError.contains("email not verified") ||
                    combinedError.contains("email_not_confirmed")) {
                errorMsg = "Please verify your email address before signing in. Check your inbox for the verification link.";
            }
            // invalid credentials
            else if (combinedError.contains("invalid login") ||
                    combinedError.contains("invalid credentials") ||
                    combinedError.contains("invalid email or password") ||
                    responseCode == 400 || responseCode == 401) {
                showFieldError(emailError, "Invalid credentials");
                errorMsg = "The email or password you entered is incorrect. Please try again.";
            }
            // user not found
            else if (combinedError.contains("user not found") ||
                    combinedError.contains("email not found") ||
                    combinedError.contains("no user found")) {
                showFieldError(emailError, "Account not found");
                errorMsg = "No account found with this email. Please check your email or create a new account.";
            }
            else if (combinedError.contains("rate limit") || responseCode == 429) {
                errorMsg = "Too many requests. Please wait a moment and try again.";
            }
            // error message
            else if (!errorDescription.isEmpty() && !errorDescription.equals("null")) {
                errorMsg = errorJson.getString("error_description");
            } else if (!errorMsgField.isEmpty() && !errorMsgField.equals("null")) {
                errorMsg = errorJson.getString("msg");
            } else if (!errorField.isEmpty() && !errorField.equals("null")) {
                errorMsg = errorJson.getString("error");
            }

            Log.e("SignInPage", "Sign-in failed - Code: " + responseCode + ", Error: " + combinedError);
        } catch (Exception e) {
            Log.e("SignInPage", "Error parsing error response: " + responseBody);

            if (responseCode == 400 || responseCode == 401) {
                showFieldError(emailError, "Invalid credentials");
                errorMsg = "The email or password you entered is incorrect.";
            } else if (responseCode == 429) {
                errorMsg = "Too many requests. Please wait a moment and try again.";
            }
        }

        showErrorMessage(errorMsg);
    }

    private void setLoadingState(boolean loading) {
        isSigningIn = loading;
        signInButton.setEnabled(!loading);
        signInButton.setText(loading ? "Signing In..." : "Continue");

        // Disable inputs during loading
        emailInput.setEnabled(!loading);
        passwordInput.setEnabled(!loading);
        checkBox_rememberMe.setEnabled(!loading);
        passwordShowToggle.setEnabled(!loading);
        forgotPass.setEnabled(!loading);
        tvCreateAcc.setEnabled(!loading);
    }

    private void saveAuthTokens(String accessToken, String refreshToken) {
        try {
            if (securePreferences != null) {
                SharedPreferences.Editor editor = securePreferences.edit();
                editor.putString("access_token", accessToken);
                editor.putString("refresh_token", refreshToken);
                editor.apply();
            }

            if (getActivity() != null) {
                SharedPreferences authPrefs = getActivity().getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE);
                SharedPreferences.Editor authEditor = authPrefs.edit();
                authEditor.putString("access_token", accessToken);
                authEditor.putString("refresh_token", refreshToken);
                authEditor.apply();
            }
        } catch (Exception e) {
            Log.e("SignInPage", "Error saving auth tokens: " + e.getMessage());
        }
    }

    private void navigateToMainActivity() {
        Intent intent = new Intent(getActivity(), MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        if (getActivity() != null) {
            getActivity().finish();
        }
    }

    private void setForgotPasswordLoadingState(boolean loading) {
        forgotPass.setEnabled(!loading);
        forgotPass.setText(loading ? "Sending..." : "Forgot Password?");
        
        // Disable other interactive elements during loading
        emailInput.setEnabled(!loading);
        signInButton.setEnabled(!loading);
        tvCreateAcc.setEnabled(!loading);
        signInGoogle.setEnabled(!loading);
    }

    private void showSuccessMessage(String message) {
        if (errorMessage != null) {
            errorMessage.setText(message);
            errorMessage.setVisibility(View.VISIBLE);
            // You might want to change the text color to green for success messages
            // errorMessage.setTextColor(ContextCompat.getColor(requireContext(), R.color.success_green));
        }
    }

    private void handleForgotPassword() {
        String email = sanitizeEmail(emailInput.getText().toString());

        clearErrors();

        if (email.isEmpty()) {
            showFieldError(emailError, "Please enter your email address");
            return;
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showFieldError(emailError, "Please enter a valid email address");
            return;
        }

        if (email.length() > MAX_EMAIL_LENGTH) {
            showFieldError(emailError, "Email address is too long");
            return;
        }

        // Show loading state with visual feedback
        setForgotPasswordLoadingState(true);
        showErrorMessage("Sending password reset email...");

        SupabaseHelper.resetPassword(email, new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    setForgotPasswordLoadingState(false);
                    showErrorMessage("Failed to send reset email. Please check your connection and try again.");
                    Log.e("SignInPage", "Password reset failure: " + e.getMessage());
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    setForgotPasswordLoadingState(false);

                    if (response.isSuccessful()) {
                        showSuccessMessage("Password reset email sent successfully! Please check your inbox and spam folder.");
                        
                        // Auto-hide success message after 5 seconds
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (getActivity() != null) {
                                hideErrorMessage();
                            }
                        }, 5000);
                    } else {
                        showErrorMessage("Failed to send reset email. Please verify your email address and try again.");
                    }
                });
            }
        });
    }

    public static void clearSavedCredentials(Context context) {
        try {
            SharedPreferences sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.clear();
            editor.apply();

            // Also clear secure preferences
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            SharedPreferences securePreferences = EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );

            SharedPreferences.Editor secureEditor = securePreferences.edit();
            secureEditor.clear();
            secureEditor.apply();
        } catch (Exception e) {
            Log.e("SignInPage", "Error clearing credentials: " + e.getMessage());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Clear password field for security
        if (passwordInput != null && !checkBox_rememberMe.isChecked()) {
            passwordInput.setText("");
        }
    }
}