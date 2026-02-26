package com.limecraft.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;

import java.io.IOException;
import java.time.Duration;

public final class HttpClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final Gson gson;

    public HttpClient() {
        this.client = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(60))
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.gson = new Gson();
    }

    public JsonObject getJson(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            ensureOk(response, url);
            return gson.fromJson(response.body().string(), JsonObject.class);
        }
    }

    public JsonObject postForm(String url, FormBody body) throws IOException {
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = client.newCall(request).execute()) {
            ensureOk(response, url);
            return gson.fromJson(response.body().string(), JsonObject.class);
        }
    }

    public JsonObject postJson(String url, JsonObject payload) throws IOException {
        RequestBody body = RequestBody.create(gson.toJson(payload), JSON);
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = client.newCall(request).execute()) {
            ensureOk(response, url);
            return gson.fromJson(response.body().string(), JsonObject.class);
        }
    }

    public OkHttpClient raw() {
        return client;
    }

    private static void ensureOk(Response response, String url) throws IOException {
        if (!response.isSuccessful()) {
            String body = response.body() != null ? response.body().string() : "";
            throw new IOException("HTTP " + response.code() + " for " + url + " -> " + body);
        }
    }
}