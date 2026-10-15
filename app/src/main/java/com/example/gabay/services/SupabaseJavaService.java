package com.example.gabay.services;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SupabaseJavaService {
    private static final String SUPABASE_URL = "https://agqhlhqyrsztzowmkdyy.supabase.co";
    private static final String SUPABASE_API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZ6cGpzbWJwZ3FsYW5xemVpcXNiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTk2NzI3MjUsImV4cCI6MjA3NTI0ODcyNX0.Iwz5iXkQxbACmYgJ6EB7bXuX76tLPYPJSZPF33N9k-s";
    private static final String REST_URL = SUPABASE_URL + "/rest/v1";

    private static final OkHttpClient client = new OkHttpClient();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static String accessToken = null;
    private static String userId = null;

    public static void setAccessToken(String token, String uid) {
        accessToken = token;
        userId = uid;
        Log.d("SupabaseService", "Access token set for user: " + uid);
    }

    public static JSONObject getUserProfile() {
        if (!isAuthenticated()) {
            Log.e("SupabaseService", "User not authenticated");
            return null;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> profileRef = new AtomicReference<>();

        try {
            String profileUrl = REST_URL + "/user_profiles?id=eq." + userId;

            Request request = new Request.Builder()
                    .url(profileUrl)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .get()
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to fetch user profile: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        try {
                            String responseBody = response.body().string();
                            JSONArray jsonArray = new JSONArray(responseBody);
                            if (jsonArray.length() > 0) {
                                profileRef.set(jsonArray.getJSONObject(0));
                            }
                        } catch (Exception e) {
                            Log.e("SupabaseService", "Error parsing profile: " + e.getMessage());
                        }
                    } else {
                        Log.e("SupabaseService", "Failed to fetch profile: " + response.body().string());
                    }
                    latch.countDown();
                }
            });

            latch.await(10, TimeUnit.SECONDS);
            return profileRef.get();

        } catch (Exception e) {
            Log.e("SupabaseService", "Error fetching user profile: " + e.getMessage());
            return null;
        }
    }

    public static String getAuthUserEmail() {
        if (!isAuthenticated()) {
            Log.e("SupabaseService", "User not authenticated");
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> userEmail = new AtomicReference<>(null);

        try {
            // Get user data from auth.users table
            String url = SUPABASE_URL + "/auth/v1/user";

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to get auth user email: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (response.isSuccessful()) {
                            String responseBody = response.body().string();
                            Log.d("SupabaseDebug", "Raw auth user response for email: " + responseBody);

                            JSONObject userData = new JSONObject(responseBody);

                            // Extract email directly from the user object
                            if (userData.has("email") && !userData.isNull("email")) {
                                String email = userData.getString("email");
                                userEmail.set(email);
                                Log.d("SupabaseService", "Auth user email found: " + email);
                            } else {
                                Log.e("SupabaseService", "No email found in auth user data");
                            }
                        } else {
                            String errorBody = response.body().string();
                            Log.e("SupabaseService", "Failed to get auth user email: " + response.code() + " - " + errorBody);
                        }
                    } catch (JSONException e) {
                        Log.e("SupabaseService", "JSON parsing error for email: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });

            latch.await(10, TimeUnit.SECONDS);
            return userEmail.get();

        } catch (Exception e) {
            Log.e("SupabaseService", "Error getting auth user email: " + e.getMessage());
            return null;
        }
    }

    public static boolean updateUsername(String newUsername) {
        if (!isAuthenticated()) {
            Log.e("SupabaseService", "User not authenticated");
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        try {
            JSONObject json = new JSONObject();
            json.put("username", newUsername);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            String updateUrl = REST_URL + "/user_profiles?id=eq." + userId;

            Request request = new Request.Builder()
                    .url(updateUrl)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .patch(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to update username: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d("SupabaseService", "Username updated successfully");
                        success.set(true);
                    } else {
                        Log.e("SupabaseService", "Failed to update username: " + response.body().string());
                    }
                    latch.countDown();
                }
            });

            latch.await(10, TimeUnit.SECONDS);
            return success.get();

        } catch (Exception e) {
            Log.e("SupabaseService", "Error updating username: " + e.getMessage());
            return false;
        }
    }

    public static String getAuthUserDisplayName() {
        if (!isAuthenticated()) {
            Log.e("SupabaseService", "User not authenticated");
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> displayName = new AtomicReference<>(null);
        try {
            // Get user data from auth.users table
            String url = SUPABASE_URL + "/auth/v1/user";

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to get auth user: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (response.isSuccessful()) {
                            String responseBody = response.body().string();
                            Log.d("SupabaseDebug", "Raw auth user response: " + responseBody);

                            JSONObject userData = new JSONObject(responseBody);

                            // Extract display name from user_metadata
                            String name = extractDisplayNameFromMetadata(userData);

                            displayName.set(name);
                            Log.d("SupabaseService", "Auth user display name found: " + name);

                        } else {
                            String errorBody = response.body().string();
                            Log.e("SupabaseService", "Failed to get auth user: " + response.code() + " - " + errorBody);
                        }
                    } catch (JSONException e) {
                        Log.e("SupabaseService", "JSON parsing error: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });

            latch.await(10, TimeUnit.SECONDS);
            return displayName.get();

        } catch (Exception e) {
            Log.e("SupabaseService", "Error getting auth user: " + e.getMessage());
            return null;
        }
    }

    private static String extractDisplayNameFromMetadata(JSONObject userData) throws JSONException {
        String name = null;

        // Check user_metadata for display_name (this is what you stored during signup)
        if (userData.has("user_metadata")) {
            JSONObject metadata = userData.getJSONObject("user_metadata");
            Log.d("SupabaseDebug", "User metadata: " + metadata.toString());

            if (metadata.has("display_name") && !metadata.isNull("display_name")) {
                name = metadata.getString("display_name");
                Log.d("SupabaseDebug", "Found display_name in metadata: " + name);
            }
        }

        // Fallback: If no display_name found, use email username
        if ((name == null || name.isEmpty()) && userData.has("email") && !userData.isNull("email")) {
            String email = userData.getString("email");
            name = email.split("@")[0]; // Use part before @ as fallback
            Log.d("SupabaseDebug", "Using email-based name: " + name);
        }

        // Final fallback
        if (name == null || name.isEmpty()) {
            name = "User";
            Log.d("SupabaseDebug", "Using default name: User");
        }

        return name;
    }

    public static void clearAuth() {
        accessToken = null;
        userId = null;
    }

    public static boolean isAuthenticated() {
        return accessToken != null && userId != null;
    }

    public static boolean updateUserProgress(int chapter, int level) {
        if (!isAuthenticated()) {
            Log.e("SupabaseService", "User not authenticated");
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        try {
            // First, check if record exists
            String checkUrl = REST_URL + "/user_progress?user_id=eq." + userId +
                    "&chapter_number=eq." + chapter +
                    "&level_number=eq." + level;

            Request checkRequest = new Request.Builder()
                    .url(checkUrl)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();

            client.newCall(checkRequest).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to check existing progress: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String responseBody = response.body().string();
                        JSONArray array = new JSONArray(responseBody);

                        if (array.length() > 0) {
                            // Record exists, update it
                            updateExistingProgress(chapter, level, latch, success);
                        } else {
                            // Record doesn't exist, insert new
                            insertNewProgress(chapter, level, latch, success);
                        }
                    } catch (JSONException e) {
                        Log.e("SupabaseService", "JSON parsing error: " + e.getMessage());
                        latch.countDown();
                    }
                }
            });

            // Wait for the operation to complete (max 10 seconds)
            latch.await(10, TimeUnit.SECONDS);
            return success.get();

        } catch (Exception e) {
            Log.e("SupabaseService", "Error updating progress: " + e.getMessage());
            return false;
        }
    }

    private static void insertNewProgress(int chapter, int level, CountDownLatch latch, AtomicBoolean success) {
        try {
            JSONObject json = new JSONObject();
            json.put("user_id", userId);
            json.put("chapter_number", chapter);
            json.put("level_number", level);
            json.put("completed", true);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder()
                    .url(REST_URL + "/user_progress")
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to insert progress: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d("SupabaseService", "Progress inserted successfully");
                        success.set(true);
                    } else {
                        Log.e("SupabaseService", "Failed to insert progress: " + response.body().string());
                    }
                    latch.countDown();
                }
            });
        } catch (JSONException e) {
            Log.e("SupabaseService", "JSON error: " + e.getMessage());
            latch.countDown();
        }
    }

    private static void updateExistingProgress(int chapter, int level, CountDownLatch latch, AtomicBoolean success) {
        try {
            JSONObject json = new JSONObject();
            json.put("completed", true);
            json.put("completed_at", "now()");

            RequestBody body = RequestBody.create(json.toString(), JSON);
            String updateUrl = REST_URL + "/user_progress?user_id=eq." + userId +
                    "&chapter_number=eq." + chapter +
                    "&level_number=eq." + level;

            Request request = new Request.Builder()
                    .url(updateUrl)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .patch(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to update progress: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d("SupabaseService", "Progress updated successfully");
                        success.set(true);
                    } else {
                        Log.e("SupabaseService", "Failed to update progress: " + response.body().string());
                    }
                    latch.countDown();
                }
            });
        } catch (JSONException e) {
            Log.e("SupabaseService", "JSON error: " + e.getMessage());
            latch.countDown();
        }
    }

    public static int getCompletedLevelsCount(int chapter) {
        if (!isAuthenticated()) {
            Log.e("SupabaseService", "User not authenticated");
            return 0;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger(0);

        try {
            String url = REST_URL + "/user_progress?user_id=eq." + userId +
                    "&chapter_number=eq." + chapter +
                    "&completed=eq.true&select=level_number";

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to get completed levels: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (response.isSuccessful()) {
                            String responseBody = response.body().string();
                            JSONArray array = new JSONArray(responseBody);

                            // Find the highest level number completed
                            int maxLevel = 0;
                            for (int i = 0; i < array.length(); i++) {
                                JSONObject obj = array.getJSONObject(i);
                                int levelNum = obj.getInt("level_number");
                                if (levelNum > maxLevel) {
                                    maxLevel = levelNum;
                                }
                            }

                            count.set(maxLevel);
                            Log.d("SupabaseService", "Chapter " + chapter + " completed levels: " + maxLevel);
                        } else {
                            Log.e("SupabaseService", "Failed to get levels: " + response.body().string());
                        }
                    } catch (JSONException e) {
                        Log.e("SupabaseService", "JSON parsing error: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });

            // Wait for the operation to complete (max 10 seconds)
            latch.await(10, TimeUnit.SECONDS);
            return count.get();

        } catch (Exception e) {
            Log.e("SupabaseService", "Error getting completed levels: " + e.getMessage());
            return 0;
        }
    }


    public static boolean updateQuizCompletion(int chapter, boolean completed) {
        if (!isAuthenticated()) {
            Log.e("SupabaseService", "User not authenticated");
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        try {
            JSONObject updates = new JSONObject();
            String fieldName = "chapter_" + chapter + "_quiz_completed";
            updates.put(fieldName, completed);
            updates.put("updated_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(new Date()));

            Log.d("SupabaseService", "Updating quiz completion - Field: " + fieldName + ", Value: " + completed);
            Log.d("SupabaseService", "Update payload: " + updates.toString());

            RequestBody body = RequestBody.create(updates.toString(), JSON);
            String updateUrl = REST_URL + "/user_profiles?id=eq." + userId;

            Request request = new Request.Builder()
                    .url(updateUrl)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .patch(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to update quiz completion: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d("SupabaseService", "✅ Quiz completion updated successfully for chapter " + chapter + 
                              " (field: " + fieldName + " = " + completed + ")");
                        success.set(true);
                    } else {
                        String errorBody = response.body().string();
                        Log.e("SupabaseService", "❌ Failed to update quiz completion: " + response.code() + " - " + errorBody);
                        Log.e("SupabaseService", "Update URL was: " + updateUrl);
                        Log.e("SupabaseService", "Update payload was: " + updates.toString());
                    }
                    latch.countDown();
                }
            });

            latch.await(10, TimeUnit.SECONDS);
            return success.get();

        } catch (Exception e) {
            Log.e("SupabaseService", "Error updating quiz completion: " + e.getMessage());
            return false;
        }
    }

    public static boolean updateTotalQuizzesPassed(int totalQuizzes) {
        if (!isAuthenticated()) {
            Log.e("SupabaseService", "User not authenticated");
            return false;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        try {
            JSONObject updates = new JSONObject();
            updates.put("total_quizzes_passed", totalQuizzes);
            updates.put("updated_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(new Date()));

            RequestBody body = RequestBody.create(updates.toString(), JSON);
            String updateUrl = REST_URL + "/user_profiles?id=eq." + userId;

            Request request = new Request.Builder()
                    .url(updateUrl)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .patch(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to update total quizzes passed: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d("SupabaseService", "Total quizzes passed updated successfully: " + totalQuizzes);
                        success.set(true);
                    } else {
                        Log.e("SupabaseService", "Failed to update total quizzes passed: " + response.code() + " - " + response.body().string());
                    }
                    latch.countDown();
                }
            });

            latch.await(10, TimeUnit.SECONDS);
            return success.get();

        } catch (Exception e) {
            Log.e("SupabaseService", "Error updating total quizzes passed: " + e.getMessage());
            return false;
        }
    }

    public static boolean isQuizCompleted(int chapter) {
        if (!isAuthenticated()) {
            Log.e("SupabaseService", "User not authenticated");
            return false;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean isCompleted = new AtomicBoolean(false);

        try {
            String profileUrl = REST_URL + "/user_profiles?id=eq." + userId;

            Request request = new Request.Builder()
                    .url(profileUrl)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .get()
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to fetch user profile for quiz completion: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        try {
                            String responseBody = response.body().string();
                            Log.d("SupabaseService", "Quiz completion check - Raw response: " + responseBody);
                            
                            JSONArray jsonArray = new JSONArray(responseBody);
                            if (jsonArray.length() > 0) {
                                JSONObject profile = jsonArray.getJSONObject(0);
                                Log.d("SupabaseService", "Quiz completion check - Profile data: " + profile.toString());
                                
                                String fieldName = "chapter_" + chapter + "_quiz_completed";
                                boolean completed = profile.optBoolean(fieldName, false);
                                
                                Log.d("SupabaseService", "Quiz completion check for chapter " + chapter + 
                                      " (field: " + fieldName + "): " + completed);
                                isCompleted.set(completed);
                            } else {
                                Log.w("SupabaseService", "No profile found for user during quiz completion check");
                            }
                        } catch (Exception e) {
                            Log.e("SupabaseService", "Error parsing profile for quiz completion: " + e.getMessage());
                        }
                    } else {
                        Log.e("SupabaseService", "Failed to fetch profile for quiz completion: " + response.code() + 
                              " - " + response.body().string());
                    }
                    latch.countDown();
                }
            });

            latch.await(10, TimeUnit.SECONDS);
            return isCompleted.get();

        } catch (Exception e) {
            Log.e("SupabaseService", "Error checking quiz completion: " + e.getMessage());
            return false;
        }
    }

    /**
     * Ensures that quiz completion fields exist in the user profile.
     * This method can be called to initialize missing fields for existing users.
     */
    public static boolean ensureQuizCompletionFields() {
        if (!isAuthenticated()) {
            Log.e("SupabaseService", "User not authenticated");
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        try {
            // First, get current profile to see what fields exist
            String profileUrl = REST_URL + "/user_profiles?id=eq." + userId;

            Request getRequest = new Request.Builder()
                    .url(profileUrl)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();

            client.newCall(getRequest).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to fetch profile for field check: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (response.isSuccessful()) {
                            String responseBody = response.body().string();
                            JSONArray jsonArray = new JSONArray(responseBody);
                            
                            if (jsonArray.length() > 0) {
                                JSONObject profile = jsonArray.getJSONObject(0);
                                JSONObject updates = new JSONObject();
                                boolean needsUpdate = false;

                                // Check each chapter's quiz completion field
                                for (int i = 1; i <= 5; i++) {
                                    String fieldName = "chapter_" + i + "_quiz_completed";
                                    if (!profile.has(fieldName)) {
                                        updates.put(fieldName, false);
                                        needsUpdate = true;
                                        Log.d("SupabaseService", "Missing field detected: " + fieldName);
                                    }
                                }

                                if (needsUpdate) {
                                    // Update the profile with missing fields
                                    updates.put("updated_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(new Date()));
                                    
                                    RequestBody body = RequestBody.create(updates.toString(), JSON);
                                    Request updateRequest = new Request.Builder()
                                            .url(profileUrl)
                                            .addHeader("apikey", SUPABASE_API_KEY)
                                            .addHeader("Authorization", "Bearer " + accessToken)
                                            .addHeader("Content-Type", "application/json")
                                            .addHeader("Prefer", "return=minimal")
                                            .patch(body)
                                            .build();

                                    client.newCall(updateRequest).enqueue(new Callback() {
                                        @Override
                                        public void onFailure(Call call, IOException e) {
                                            Log.e("SupabaseService", "Failed to update missing fields: " + e.getMessage());
                                            latch.countDown();
                                        }

                                        @Override
                                        public void onResponse(Call call, Response response) throws IOException {
                                            if (response.isSuccessful()) {
                                                Log.d("SupabaseService", "✅ Quiz completion fields initialized successfully");
                                                success.set(true);
                                            } else {
                                                Log.e("SupabaseService", "❌ Failed to initialize quiz fields: " + response.body().string());
                                            }
                                            latch.countDown();
                                        }
                                    });
                                } else {
                                    Log.d("SupabaseService", "✅ All quiz completion fields already exist");
                                    success.set(true);
                                    latch.countDown();
                                }
                            } else {
                                Log.e("SupabaseService", "No profile found for field initialization");
                                latch.countDown();
                            }
                        } else {
                            Log.e("SupabaseService", "Failed to fetch profile: " + response.body().string());
                            latch.countDown();
                        }
                    } catch (Exception e) {
                        Log.e("SupabaseService", "Error during field initialization: " + e.getMessage());
                        latch.countDown();
                    }
                }
            });

            latch.await(15, TimeUnit.SECONDS);
            return success.get();

        } catch (Exception e) {
            Log.e("SupabaseService", "Error ensuring quiz completion fields: " + e.getMessage());
            return false;
        }
    }

    public static String getAccessToken() {
        return accessToken;
    }

    public static String getUserId() {
        return userId;
    }

    public static boolean createUserProfile(String username) {
        if (!isAuthenticated()) {
            Log.e("SupabaseService", "User not authenticated");
            return false;
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        try {
            JSONObject json = new JSONObject();
            json.put("id", userId);
            json.put("username", username);
            json.put("total_chapters_completed", 0);
            json.put("total_quizzes_passed", 0);
            
            // Initialize quiz completion fields for all chapters
            for (int i = 1; i <= 5; i++) {
                json.put("chapter_" + i + "_quiz_completed", false);
            }

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder()
                    .url(REST_URL + "/user_profiles")
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("SupabaseService", "Failed to create profile: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d("SupabaseService", "User profile created successfully");
                        success.set(true);
                    } else {
                        Log.e("SupabaseService", "Failed to create profile: " + response.body().string());
                    }
                    latch.countDown();
                }
            });

            latch.await(10, TimeUnit.SECONDS);
            return success.get();

        } catch (Exception e) {
            Log.e("SupabaseService", "Error creating profile: " + e.getMessage());
            return false;
        }
    }
}