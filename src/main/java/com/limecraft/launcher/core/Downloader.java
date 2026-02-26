package com.limecraft.launcher.core;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Downloader {
    private final OkHttpClient client;

    public Downloader(OkHttpClient client) {
        this.client = client;
    }

    public void downloadTo(String url, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Failed download: " + url + " code=" + response.code());
            }
            try (InputStream in = response.body().byteStream()) {
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}