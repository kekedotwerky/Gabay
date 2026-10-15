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
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Service for handling user analytics including app rating, session tracking, and progress analytics
 * Works alongside existing SupabaseJavaService without affecting current functionality
 */
public class UserAnalyticsService {
    private static final String TAG = "UserAnalyticsService";
    private static final String SUPABASE_URL = "https://agqhlhqyrsztzowmkdyy.supabase.co";
    private static final String SUPABASE_API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZ6cGpzbWJwZ3FsYW5xemVpcXNiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTk2NzI3MjUsImV4cCI6MjA3NTI0ODcyNX0.Iwz5iXkQxbACmYgJ6EB7bXuX76tLPYPJSZPF33N9k-s";
    private static final String REST_URL = SUPABASE_URL + "/rest/v1";

    private static final OkHttpClient client = new OkHttpClient();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * Submit app rating to Supabase
     */
    public static boolean submitAppRating(int rating, String comment) {
        if (!SupabaseJavaService.isAuthenticated()) {
            Log.e(TAG, "User not authenticated");
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        try {
            // First check if analytics record exists
            String userId = getCurrentUserId();
            if (userId == null) {
                Log.e(TAG, "Could not get user ID");
                return false;
            }

            String checkUrl = REST_URL + "/user_analytics?user_id=eq." + userId;

            Request checkRequest = new Request.Builder()
                    .url(checkUrl)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + getAccessToken())
                    .get()
                    .build();

            client.newCall(checkRequest).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to check existing analytics: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String responseBody = response.body().string();
                        JSONArray array = new JSONArray(responseBody);

                        if (array.length() > 0) {
                            // Update existing record
                            updateAppRating(userId, rating, comment, latch, success);
                        } else {
                            // Create new record
                            createAnalyticsWithRating(userId, rating, comment, latch, success);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON parsing error: " + e.getMessage());
                        latch.countDown();
                    }
                }
            });

            latch.await(15, TimeUnit.SECONDS);
            return success.get();

        } catch (Exception e) {
            Log.e(TAG, "Error submitting app rating: " + e.getMessage());
            return false;
        }
    }

    /**
     * Track chapter completion
     */
    public static boolean trackChapterCompletion(int chapterNumber) {
        if (!SupabaseJavaService.isAuthenticated()) {
            Log.e(TAG, "User not authenticated");
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        try {
            String userId = getCurrentUserId();
            if (userId == null) return false;

            // Get current analytics or create new
            ensureAnalyticsRecord(userId, () -> {
                // Update chapters completed count
                updateChaptersCompleted(userId, chapterNumber, latch, success);
            });

            latch.await(15, TimeUnit.SECONDS);
            return success.get();

        } catch (Exception e) {
            Log.e(TAG, "Error tracking chapter completion: " + e.getMessage());
            return false;
        }
    }

    /**
     * Update user session data
     */
    public static boolean updateSessionData(int sessionTimeMinutes) {
        if (!SupabaseJavaService.isAuthenticated()) {
            Log.e(TAG, "User not authenticated");
            return false;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);

        try {
            String userId = getCurrentUserId();
            if (userId == null) return false;

            ensureAnalyticsRecord(userId, () -> {
                updateSessionStats(userId, sessionTimeMinutes, latch, success);
            });

            latch.await(15, TimeUnit.SECONDS);
            return success.get();

        } catch (Exception e) {
            Log.e(TAG, "Error updating session data: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get user analytics data
     */
    public static JSONObject getUserAnalytics() {
        if (!SupabaseJavaService.isAuthenticated()) {
            Log.e(TAG, "User not authenticated");
            return null;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> analyticsRef = new AtomicReference<>();

        try {
            String userId = getCurrentUserId();
            if (userId == null) return null;

            String url = REST_URL + "/user_analytics?user_id=eq." + userId;

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + getAccessToken())
                    .get()
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to fetch analytics: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        try {
                            String responseBody = response.body().string();
                            JSONArray jsonArray = new JSONArray(responseBody);
                            if (jsonArray.length() > 0) {
                                analyticsRef.set(jsonArray.getJSONObject(0));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing analytics: " + e.getMessage());
                        }
                    } else {
                        Log.e(TAG, "Failed to fetch analytics: " + response.body().string());
                    }
                    latch.countDown();
                }
            });

            latch.await(10, TimeUnit.SECONDS);
            return analyticsRef.get();

        } catch (Exception e) {
            Log.e(TAG, "Error fetching analytics: " + e.getMessage());
            return null;
        }
    }

    // Helper methods
    private static void updateAppRating(String userId, int rating, String comment, CountDownLatch latch, AtomicBoolean success) {
        try {
            JSONObject json = new JSONObject();
            json.put("app_rating", rating);
            json.put("rating_comment", comment != null ? comment : "");
            json.put("rated_at", getCurrentTimestamp());
            json.put("updated_at", getCurrentTimestamp());

            RequestBody body = RequestBody.create(json.toString(), JSON);
            String updateUrl = REST_URL + "/user_analytics?user_id=eq." + userId;

            Request request = new Request.Builder()
                    .url(updateUrl)
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + getAccessToken())
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .patch(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to update rating: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "App rating updated successfully");
                        success.set(true);
                    } else {
                        Log.e(TAG, "Failed to update rating: " + response.body().string());
                    }
                    latch.countDown();
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "JSON error: " + e.getMessage());
            latch.countDown();
        }
    }

    private static void createAnalyticsWithRating(String userId, int rating, String comment, CountDownLatch latch, AtomicBoolean success) {
        try {
            JSONObject json = new JSONObject();
            json.put("user_id", userId);
            json.put("app_rating", rating);
            json.put("rating_comment", comment != null ? comment : "");
            json.put("rated_at", getCurrentTimestamp());
            json.put("total_sessions", 1);
            json.put("chapters_completed", 0);
            json.put("current_streak_days", 0);
            json.put("longest_streak_days", 0);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder()
                    .url(REST_URL + "/user_analytics")
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + getAccessToken())
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to create analytics with rating: " + e.getMessage());
                    latch.countDown();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "Analytics created with rating successfully");
                        success.set(true);
                    } else {
                        Log.e(TAG, "Failed to create analytics: " + response.body().string());
                    }
                    latch.countDown();
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "JSON error: " + e.getMessage());
            latch.countDown();
        }
    }

    private static void updateChaptersCompleted(String userId, int chapterNumber, CountDownLatch latch, AtomicBoolean success) {
        // First get current count, then increment
        String url = REST_URL + "/user_analytics?user_id=eq." + userId;

        Request getRequest = new Request.Builder()
                .url(url)
                .addHeader("apikey", SUPABASE_API_KEY)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .get()
                .build();

        client.newCall(getRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to get current chapters completed: " + e.getMessage());
                latch.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    JSONArray array = new JSONArray(responseBody);
                    
                    int currentCompleted = 0;
                    if (array.length() > 0) {
                        JSONObject analytics = array.getJSONObject(0);
                        currentCompleted = analytics.optInt("chapters_completed", 0);
                    }

                    // Update with incremented count
                    JSONObject updateJson = new JSONObject();
                    updateJson.put("chapters_completed", currentCompleted + 1);
                    updateJson.put("favorite_chapter", chapterNumber);
                    updateJson.put("updated_at", getCurrentTimestamp());

                    RequestBody updateBody = RequestBody.create(updateJson.toString(), JSON);
                    String updateUrl = REST_URL + "/user_analytics?user_id=eq." + userId;

                    Request updateRequest = new Request.Builder()
                            .url(updateUrl)
                            .addHeader("apikey", SUPABASE_API_KEY)
                            .addHeader("Authorization", "Bearer " + getAccessToken())
                            .addHeader("Content-Type", "application/json")
                            .addHeader("Prefer", "return=minimal")
                            .patch(updateBody)
                            .build();

                    client.newCall(updateRequest).enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, IOException e) {
                            Log.e(TAG, "Failed to update chapters completed: " + e.getMessage());
                            latch.countDown();
                        }

                        @Override
                        public void onResponse(Call call, Response response) throws IOException {
                            if (response.isSuccessful()) {
                                Log.d(TAG, "Chapters completed updated successfully");
                                success.set(true);
                            } else {
                                Log.e(TAG, "Failed to update chapters completed: " + response.body().string());
                            }
                            latch.countDown();
                        }
                    });

                } catch (JSONException e) {
                    Log.e(TAG, "JSON parsing error: " + e.getMessage());
                    latch.countDown();
                }
            }
        });
    }

    private static void updateSessionStats(String userId, int sessionTimeMinutes, CountDownLatch latch, AtomicBoolean success) {
        // Get current session data and update
        String url = REST_URL + "/user_analytics?user_id=eq." + userId;

        Request getRequest = new Request.Builder()
                .url(url)
                .addHeader("apikey", SUPABASE_API_KEY)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .get()
                .build();

        client.newCall(getRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to get current session data: " + e.getMessage());
                latch.countDown();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    JSONArray array = new JSONArray(responseBody);
                    
                    int currentSessions = 0;
                    int currentTimeSpent = 0;
                    
                    if (array.length() > 0) {
                        JSONObject analytics = array.getJSONObject(0);
                        currentSessions = analytics.optInt("total_sessions", 0);
                        currentTimeSpent = analytics.optInt("total_time_spent_minutes", 0);
                    }

                    // Update session data
                    JSONObject updateJson = new JSONObject();
                    updateJson.put("total_sessions", currentSessions + 1);
                    updateJson.put("total_time_spent_minutes", currentTimeSpent + sessionTimeMinutes);
                    updateJson.put("last_session_at", getCurrentTimestamp());
                    updateJson.put("updated_at", getCurrentTimestamp());

                    RequestBody updateBody = RequestBody.create(updateJson.toString(), JSON);
                    String updateUrl = REST_URL + "/user_analytics?user_id=eq." + userId;

                    Request updateRequest = new Request.Builder()
                            .url(updateUrl)
                            .addHeader("apikey", SUPABASE_API_KEY)
                            .addHeader("Authorization", "Bearer " + getAccessToken())
                            .addHeader("Content-Type", "application/json")
                            .addHeader("Prefer", "return=minimal")
                            .patch(updateBody)
                            .build();

                    client.newCall(updateRequest).enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, IOException e) {
                            Log.e(TAG, "Failed to update session stats: " + e.getMessage());
                            latch.countDown();
                        }

                        @Override
                        public void onResponse(Call call, Response response) throws IOException {
                            if (response.isSuccessful()) {
                                Log.d(TAG, "Session stats updated successfully");
                                success.set(true);
                            } else {
                                Log.e(TAG, "Failed to update session stats: " + response.body().string());
                            }
                            latch.countDown();
                        }
                    });

                } catch (JSONException e) {
                    Log.e(TAG, "JSON parsing error: " + e.getMessage());
                    latch.countDown();
                }
            }
        });
    }

    private static void ensureAnalyticsRecord(String userId, Runnable onComplete) {
        String checkUrl = REST_URL + "/user_analytics?user_id=eq." + userId;

        Request checkRequest = new Request.Builder()
                .url(checkUrl)
                .addHeader("apikey", SUPABASE_API_KEY)
                .addHeader("Authorization", "Bearer " + getAccessToken())
                .get()
                .build();

        client.newCall(checkRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Failed to check analytics record: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    JSONArray array = new JSONArray(responseBody);

                    if (array.length() == 0) {
                        // Create new analytics record
                        createDefaultAnalyticsRecord(userId, onComplete);
                    } else {
                        // Record exists, proceed with update
                        onComplete.run();
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "JSON parsing error: " + e.getMessage());
                }
            }
        });
    }

    private static void createDefaultAnalyticsRecord(String userId, Runnable onComplete) {
        try {
            JSONObject json = new JSONObject();
            json.put("user_id", userId);
            json.put("total_sessions", 0);
            json.put("total_time_spent_minutes", 0);
            json.put("chapters_completed", 0);
            json.put("current_streak_days", 0);
            json.put("longest_streak_days", 0);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder()
                    .url(REST_URL + "/user_analytics")
                    .addHeader("apikey", SUPABASE_API_KEY)
                    .addHeader("Authorization", "Bearer " + getAccessToken())
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to create default analytics: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "Default analytics record created");
                        onComplete.run();
                    } else {
                        Log.e(TAG, "Failed to create default analytics: " + response.body().string());
                    }
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "JSON error creating default analytics: " + e.getMessage());
        }
    }

    // Utility methods
    private static String getCurrentUserId() {
        // Access userId from SupabaseJavaService using reflection or make it public
        // For now, we'll use the same approach as SupabaseJavaService
        try {
            java.lang.reflect.Field userIdField = SupabaseJavaService.class.getDeclaredField("userId");
            userIdField.setAccessible(true);
            return (String) userIdField.get(null);
        } catch (Exception e) {
            Log.e(TAG, "Could not access userId: " + e.getMessage());
            return null;
        }
    }

    private static String getAccessToken() {
        try {
            java.lang.reflect.Field tokenField = SupabaseJavaService.class.getDeclaredField("accessToken");
            tokenField.setAccessible(true);
            return (String) tokenField.get(null);
        } catch (Exception e) {
            Log.e(TAG, "Could not access accessToken: " + e.getMessage());
            return null;
        }
    }

    private static String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").format(new Date());
    }
}
