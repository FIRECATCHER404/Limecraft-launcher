package com.limecraft.launcher.core;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public final class Downloader {
    private final OkHttpClient client;

    public Downloader(OkHttpClient client) {
        this.client = client;
    }

    public void downloadTo(String url, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = parent == null
                ? Path.of(target.getFileName() + ".part-" + UUID.randomUUID())
                : parent.resolve(target.getFileName() + ".part-" + UUID.randomUUID());
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", AppVersion.userAgent())
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Failed download: " + url + " code=" + response.code());
            }
            try (InputStream in = response.body().byteStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            moveIntoPlace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
