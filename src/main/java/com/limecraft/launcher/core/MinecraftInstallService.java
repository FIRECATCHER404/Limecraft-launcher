package com.limecraft.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MinecraftInstallService {
    private static final String MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String EXPERIMENTAL_MANIFEST_URL = "https://maven.fabricmc.net/net/minecraft/experimental_versions.json";

    private final HttpClient http;
    private final Downloader downloader;
    private final Path gameDir;

    public MinecraftInstallService(HttpClient http, Path gameDir) {
        this.http = http;
        this.downloader = new Downloader(http.raw());
        this.gameDir = gameDir;
    }

    public List<VersionEntry> listVersions() throws IOException {
        Map<String, VersionEntry> byId = new LinkedHashMap<>();
        mergeManifestInto(byId, MANIFEST_URL, false);
        mergeManifestInto(byId, EXPERIMENTAL_MANIFEST_URL, true);

        List<VersionEntry> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing(VersionEntry::releaseTime).reversed());
        return out;
    }

    private void mergeManifestInto(Map<String, VersionEntry> target, String url, boolean experimental) throws IOException {
        JsonObject manifest = http.getJson(url);
        JsonArray versions = manifest.getAsJsonArray("versions");
        for (JsonElement el : versions) {
            JsonObject o = el.getAsJsonObject();
            String id = o.get("id").getAsString();
            String type = o.get("type").getAsString();
            if (experimental && "pending".equals(type)) {
                type = "experiment";
            }
            target.putIfAbsent(id, new VersionEntry(
                    id,
                    type,
                    o.get("url").getAsString(),
                    o.get("releaseTime").getAsString()
            ));
        }
    }

    public JsonObject installVersion(VersionEntry version, ProgressListener listener) throws IOException {
        JsonObject meta = http.getJson(version.url());
        String versionId = meta.get("id").getAsString();

        Path versionDir = gameDir.resolve("versions").resolve(versionId);
        Path versionJson = versionDir.resolve(versionId + ".json");
        java.nio.file.Files.createDirectories(versionDir);
        java.nio.file.Files.writeString(versionJson, meta.toString());

        listener.onProgress("Downloading client jar", 0.1);
        String clientUrl = meta.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
        downloader.downloadTo(clientUrl, versionDir.resolve(versionId + ".jar"));

        listener.onProgress("Downloading libraries", 0.25);
        downloadLibraries(meta, listener);

        listener.onProgress("Downloading assets", 0.7);
        downloadAssets(meta, listener);

        listener.onProgress("Install complete", 1.0);
        return meta;
    }

    public void installMetadataDependencies(JsonObject versionMeta, ProgressListener listener) throws IOException {
        if (versionMeta.has("libraries") && versionMeta.get("libraries").isJsonArray()) {
            listener.onProgress("Downloading libraries", 0.25);
            downloadLibraries(versionMeta, listener);
        }
        if (versionMeta.has("assetIndex") && versionMeta.get("assetIndex").isJsonObject()) {
            listener.onProgress("Downloading assets", 0.7);
            downloadAssets(versionMeta, listener);
        }
        listener.onProgress("Install complete", 1.0);
    }

    private void downloadLibraries(JsonObject versionMeta, ProgressListener listener) throws IOException {
        JsonArray libs = versionMeta.getAsJsonArray("libraries");
        int total = libs.size();
        int i = 0;
        String os = detectOs();

        for (JsonElement libEl : libs) {
            i++;
            JsonObject lib = libEl.getAsJsonObject();
            if (!isLibraryAllowed(lib, os)) {
                continue;
            }

            LibraryArtifact artifact = resolveMainArtifact(lib);
            if (artifact != null) {
                Path out = gameDir.resolve("libraries").resolve(artifact.path());
                if (!java.nio.file.Files.exists(out)) {
                    downloader.downloadTo(artifact.url(), out);
                }
            }

            if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("classifiers")) {
                JsonObject downloads = lib.getAsJsonObject("downloads");
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                String key = switch (os) {
                    case "windows" -> "natives-windows";
                    case "osx" -> "natives-macos";
                    default -> "natives-linux";
                };
                String fallbackKey = "osx".equals(os) ? "natives-osx" : key;
                String chosenKey = classifiers.has(key) ? key : fallbackKey;
                if (classifiers.has(chosenKey)) {
                    JsonObject nativeArtifact = classifiers.getAsJsonObject(chosenKey);
                    String path = nativeArtifact.get("path").getAsString();
                    String url = nativeArtifact.get("url").getAsString();
                    Path out = gameDir.resolve("libraries").resolve(path);
                    if (!java.nio.file.Files.exists(out)) {
                        downloader.downloadTo(url, out);
                    }
                }
            }
            listener.onProgress("Libraries: " + i + "/" + total, 0.25 + (0.4 * ((double) i / total)));
        }
    }

    private LibraryArtifact resolveMainArtifact(JsonObject lib) {
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
            JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
            return new LibraryArtifact(artifact.get("path").getAsString(), artifact.get("url").getAsString());
        }
        if (!lib.has("name")) {
            return null;
        }
        String path = toMavenPath(lib.get("name").getAsString());
        String base = lib.has("url") ? lib.get("url").getAsString() : "https://libraries.minecraft.net/";
        if (!base.endsWith("/")) {
            base += "/";
        }
        return new LibraryArtifact(path, base + path);
    }

    private String toMavenPath(String notation) {
        String ext = "jar";
        String baseNotation = notation;
        int at = notation.indexOf('@');
        if (at >= 0) {
            baseNotation = notation.substring(0, at);
            ext = notation.substring(at + 1);
        }

        String[] parts = baseNotation.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid library notation: " + notation);
        }
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length >= 4 ? parts[3] : null;

        StringBuilder file = new StringBuilder();
        file.append(artifact).append('-').append(version);
        if (classifier != null && !classifier.isBlank()) {
            file.append('-').append(classifier);
        }
        file.append('.').append(ext);
        return group + "/" + artifact + "/" + version + "/" + file;
    }

    private void downloadAssets(JsonObject versionMeta, ProgressListener listener) throws IOException {
        JsonObject assetIndex = versionMeta.getAsJsonObject("assetIndex");
        String url = assetIndex.get("url").getAsString();
        JsonObject indexJson = http.getJson(url);

        String assetId = assetIndex.get("id").getAsString();
        Path indexes = gameDir.resolve("assets").resolve("indexes");
        java.nio.file.Files.createDirectories(indexes);
        java.nio.file.Files.writeString(indexes.resolve(assetId + ".json"), indexJson.toString());

        JsonObject objects = indexJson.getAsJsonObject("objects");
        int total = objects.entrySet().size();
        int i = 0;
        for (var e : objects.entrySet()) {
            i++;
            JsonObject o = e.getValue().getAsJsonObject();
            String hash = o.get("hash").getAsString();
            String prefix = hash.substring(0, 2);
            String objectUrl = "https://resources.download.minecraft.net/" + prefix + "/" + hash;
            Path out = gameDir.resolve("assets").resolve("objects").resolve(prefix).resolve(hash);
            if (!java.nio.file.Files.exists(out)) {
                downloader.downloadTo(objectUrl, out);
            }
            if (i % 150 == 0 || i == total) {
                listener.onProgress("Assets: " + i + "/" + total, 0.7 + (0.28 * ((double) i / total)));
            }
        }
    }

    private boolean isLibraryAllowed(JsonObject lib, String os) {
        if (!lib.has("rules")) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement ruleEl : lib.getAsJsonArray("rules")) {
            JsonObject rule = ruleEl.getAsJsonObject();
            String action = rule.get("action").getAsString();
            boolean matches = true;
            if (rule.has("os")) {
                JsonObject osObj = rule.getAsJsonObject("os");
                matches = osObj.has("name") && os.equals(osObj.get("name").getAsString());
            }
            if (matches && rule.has("features")) {
                matches = false;
            }
            if (matches) {
                allowed = "allow".equals(action);
            }
        }
        return allowed;
    }

    private String detectOs() {
        String name = System.getProperty("os.name").toLowerCase();
        if (name.contains("win")) return "windows";
        if (name.contains("mac")) return "osx";
        return "linux";
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message, double progress);
    }

    private record LibraryArtifact(String path, String url) {}
}
