package com.example.gabay.services;

import android.util.Log;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Callback;
import okhttp3.MediaType;


import org.json.JSONException;
import org.json.JSONObject;


// supabase helper for authentication
public class SupabaseHelper {
    private static final String SUPABASE_URL = "https://agqhlhqyrsztzowmkdyy.supabase.co";
    private static final String SUPABASE_API_KEY = "sb_publishable_Kc8i12J1daudqpmPjaaThw_H_HKROSf";

    private static final OkHttpClient client = new OkHttpClient();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static void signUp(String displayName, String email, String password, Callback callback) {
        String url = SUPABASE_URL + "/auth/v1/signup?redirect_to=gabay%3A%2F%2Fauth%2Fconfirm";
        Log.d("SupabaseHelper", "Sign-up URL: " + url);

        JSONObject json = new JSONObject();
        try {
            json.put("email", email);
            json.put("password", password);
            JSONObject userMetadata = new JSONObject();
            userMetadata.put("display_name", displayName);
            json.put("data", userMetadata);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("apikey", SUPABASE_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        client.newCall(request).enqueue(callback);
    }
    public static void signIn(String email, String password, Callback callback) {
        String url = SUPABASE_URL + "/auth/v1/token?grant_type=password";

        JSONObject json = new JSONObject();
        try {
            json.put("email", email);
            json.put("password", password);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }
        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("apikey", SUPABASE_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        client.newCall(request).enqueue(callback);
    }
    public static void exchangeGoogleToken(String idToken, Callback callback) {
        String url = SUPABASE_URL + "/auth/v1/token?grant_type=id_token";

        JSONObject json = new JSONObject();
        try {
            json.put("provider", "google");
            json.put("id_token", idToken);

            Log.d("SupabaseHelper", "Request JSON: " + json.toString());
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }
        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", SUPABASE_API_KEY)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Client-Info", "gabay-android")
                .build();

        Log.d("SupabaseHelper", "Making request to: " + url);
        client.newCall(request).enqueue(callback);
    }

    public static void resetPassword(String email, Callback callback) {
        String url = SUPABASE_URL + "/auth/v1/recover";

        JSONObject json = new JSONObject();
        try {
            json.put("email", email);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request request = new Request.Builder()
                .url(url)
                .addHeader("apikey", SUPABASE_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        client.newCall(request).enqueue(callback);
    }

}
