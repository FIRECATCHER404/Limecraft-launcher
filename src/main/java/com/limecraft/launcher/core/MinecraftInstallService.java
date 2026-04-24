package com.limecraft.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Properties;

public final class MinecraftInstallService {
    private static final String MANIFEST_URL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String EXPERIMENTAL_MANIFEST_URL = "https://maven.fabricmc.net/net/minecraft/experimental_versions.json";
    private static final String OFFICIAL_MANIFEST_CACHE = "version_manifest_v2.json";
    private static final String EXPERIMENTAL_MANIFEST_CACHE = "experimental_versions.json";

    private final HttpClient http;
    private final Downloader downloader;
    private final Path gameDir;
    private final Path legacyDataDir;

    public MinecraftInstallService(HttpClient http, Path gameDir, Path legacyDataDir) {
        this.http = http;
        this.downloader = new Downloader(http.raw());
        this.gameDir = gameDir;
        this.legacyDataDir = legacyDataDir == null ? gameDir : legacyDataDir.toAbsolutePath().normalize();
    }

    public List<VersionEntry> listVersions() throws IOException {
        Map<String, VersionEntry> byId = new LinkedHashMap<>();
        mergeManifestInto(byId, MANIFEST_URL, false, OFFICIAL_MANIFEST_CACHE, false);
        mergeManifestInto(byId, EXPERIMENTAL_MANIFEST_URL, true, EXPERIMENTAL_MANIFEST_CACHE, false);

        List<VersionEntry> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing(VersionEntry::releaseTime).reversed());
        return out;
    }

    public List<VersionEntry> listCachedVersions() {
        Map<String, VersionEntry> byId = new LinkedHashMap<>();
        mergeCachedManifestInto(byId, OFFICIAL_MANIFEST_CACHE, false);
        mergeCachedManifestInto(byId, EXPERIMENTAL_MANIFEST_CACHE, true);

        List<VersionEntry> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing(VersionEntry::releaseTime).reversed());
        return out;
    }

    private void mergeManifestInto(Map<String, VersionEntry> target, String url, boolean experimental, String cacheFileName, boolean required) throws IOException {
        JsonObject manifest = readManifestWithCache(url, cacheFileName, required);
        if (manifest == null || !manifest.has("versions") || !manifest.get("versions").isJsonArray()) {
            return;
        }
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

    private JsonObject readManifestWithCache(String url, String cacheFileName, boolean required) throws IOException {
        try {
            JsonObject manifest = http.getJson(url);
            writeManifestCache(cacheFileName, manifest);
            return manifest;
        } catch (IOException networkError) {
            JsonObject cached = readManifestCache(cacheFileName);
            if (cached != null) {
                return cached;
            }
            if (required) {
                throw networkError;
            }
            return null;
        }
    }

    private void writeManifestCache(String cacheFileName, JsonObject manifest) throws IOException {
        if (cacheFileName == null || cacheFileName.isBlank() || manifest == null) {
            return;
        }
        Path cacheFile = manifestCachePath(cacheFileName);
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, manifest.toString(), StandardCharsets.UTF_8);
    }

    private JsonObject readManifestCache(String cacheFileName) {
        if (cacheFileName == null || cacheFileName.isBlank()) {
            return null;
        }
        Path cacheFile = manifestCachePath(cacheFileName);
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Path manifestCachePath(String cacheFileName) {
        return gameDir.resolve("cache").resolve(cacheFileName);
    }

    private void mergeCachedManifestInto(Map<String, VersionEntry> target, String cacheFileName, boolean experimental) {
        JsonObject manifest = readManifestCache(cacheFileName);
        if (manifest == null || !manifest.has("versions") || !manifest.get("versions").isJsonArray()) {
            return;
        }
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

        writeDependencyMarker(meta);
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
        writeDependencyMarker(versionMeta);
        listener.onProgress("Install complete", 1.0);
    }

    public boolean installMetadataDependenciesIfNeeded(JsonObject versionMeta, ProgressListener listener) throws IOException {
        if (versionMeta == null) {
            return false;
        }
        if (dependencyMarkerCurrent(versionMeta)) {
            return false;
        }
        if (dependenciesAlreadyAvailable(versionMeta)) {
            writeDependencyMarker(versionMeta);
            return false;
        }
        installMetadataDependencies(versionMeta, listener);
        return true;
    }

    public void invalidateDependencyCache(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(dependencyMarkerPath(versionId));
        } catch (IOException ignored) {
        }
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
                if (!Files.exists(out) && resolveExistingLibraryPath(artifact.path()) == null) {
                    downloader.downloadTo(artifact.url(), out);
                }
            }

            LibraryArtifact nativeArtifact = resolveNativeArtifact(lib, os);
            if (nativeArtifact != null) {
                Path out = gameDir.resolve("libraries").resolve(nativeArtifact.path());
                if (!Files.exists(out) && resolveExistingLibraryPath(nativeArtifact.path()) == null) {
                    downloader.downloadTo(nativeArtifact.url(), out);
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
        // Older manifests often mark native-only libraries with "natives" and no main artifact.
        // Downloading the unclassified jar in that case causes 404s (for example lwjgl-platform 2.9.0).
        if (lib.has("natives")) {
            return null;
        }
        String path = toMavenPath(lib.get("name").getAsString());
        String base = normalizeRepositoryBase(lib);
        return new LibraryArtifact(path, base + path);
    }

    private LibraryArtifact resolveNativeArtifact(JsonObject lib, String os) {
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("classifiers")) {
            JsonObject classifiers = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");
            String chosenKey = chooseNativeClassifierKey(classifiers, os);
            if (chosenKey == null) {
                return null;
            }
            JsonObject nativeArtifact = classifiers.getAsJsonObject(chosenKey);
            return new LibraryArtifact(
                    nativeArtifact.get("path").getAsString(),
                    nativeArtifact.get("url").getAsString()
            );
        }
        if (!lib.has("name") || !lib.has("natives") || !lib.get("natives").isJsonObject()) {
            return null;
        }
        JsonObject natives = lib.getAsJsonObject("natives");
        if (!natives.has(os)) {
            return null;
        }
        String classifier = natives.get(os).getAsString().replace("${arch}", detectArchitectureBits());
        String path = toMavenPath(appendClassifier(lib.get("name").getAsString(), classifier));
        String base = normalizeRepositoryBase(lib);
        return new LibraryArtifact(path, base + path);
    }

    private String chooseNativeClassifierKey(JsonObject classifiers, String os) {
        String base = switch (os) {
            case "windows" -> "natives-windows";
            case "osx" -> "natives-macos";
            default -> "natives-linux";
        };
        String fallback = "osx".equals(os) ? "natives-osx" : base;
        String arch = detectArchitectureBits();

        String[] preferred = new String[] {
                base + "-" + arch,
                base,
                fallback + "-" + arch,
                fallback
        };
        for (String key : preferred) {
            if (classifiers.has(key)) {
                return key;
            }
        }
        for (var entry : classifiers.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(base + "-") || key.startsWith(fallback + "-")) {
                return key;
            }
        }
        return null;
    }

    private String appendClassifier(String notation, String classifier) {
        String ext = "";
        String base = notation;
        int at = notation.indexOf('@');
        if (at >= 0) {
            base = notation.substring(0, at);
            ext = notation.substring(at);
        }

        String[] parts = base.split(":");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid library notation: " + notation);
        }

        StringBuilder withClassifier = new StringBuilder();
        withClassifier.append(parts[0]).append(':').append(parts[1]).append(':').append(parts[2]);
        withClassifier.append(':').append(classifier);
        withClassifier.append(ext);
        return withClassifier.toString();
    }

    private String normalizeRepositoryBase(JsonObject lib) {
        String base = lib.has("url") ? lib.get("url").getAsString() : "https://libraries.minecraft.net/";
        if (!base.endsWith("/")) {
            base += "/";
        }
        return base;
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
            if (!Files.exists(out) && resolveExistingAssetObjectPath(hash) == null) {
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

    private String detectArchitectureBits() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return arch.contains("64") ? "64" : "32";
    }

    private String detectOs() {
        String name = System.getProperty("os.name").toLowerCase();
        if (name.contains("win")) return "windows";
        if (name.contains("mac")) return "osx";
        return "linux";
    }

    private boolean dependencyMarkerCurrent(JsonObject versionMeta) {
        String versionId = readString(versionMeta, "id");
        if (versionId.isBlank()) {
            return false;
        }
        Path marker = dependencyMarkerPath(versionId);
        if (!Files.isRegularFile(marker)) {
            return false;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(marker)) {
            props.load(in);
        } catch (Exception ex) {
            return false;
        }
        String expected = dependencySignature(versionMeta);
        String actual = props.getProperty("signature", "").trim();
        return expected.equals(actual) && requiredDependencyFilesPresent(versionMeta);
    }

    private boolean dependenciesAlreadyAvailable(JsonObject versionMeta) {
        if (!requiredLibraryFilesPresent(versionMeta)) {
            return false;
        }
        return requiredAssetFilesPresent(versionMeta);
    }

    private boolean requiredDependencyFilesPresent(JsonObject versionMeta) {
        return requiredLibraryFilesPresent(versionMeta) && cheapAssetIndexPresent(versionMeta);
    }

    private boolean requiredLibraryFilesPresent(JsonObject versionMeta) {
        String os = detectOs();
        if (versionMeta.has("libraries") && versionMeta.get("libraries").isJsonArray()) {
            for (JsonElement libEl : versionMeta.getAsJsonArray("libraries")) {
                JsonObject lib = libEl.getAsJsonObject();
                if (!isLibraryAllowed(lib, os)) {
                    continue;
                }
                LibraryArtifact artifact = resolveMainArtifact(lib);
                if (artifact != null && resolveExistingLibraryPath(artifact.path()) == null) {
                    return false;
                }
                LibraryArtifact nativeArtifact = resolveNativeArtifact(lib, os);
                if (nativeArtifact != null && resolveExistingLibraryPath(nativeArtifact.path()) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean cheapAssetIndexPresent(JsonObject versionMeta) {
        if (versionMeta.has("assetIndex") && versionMeta.get("assetIndex").isJsonObject()) {
            String assetId = readString(versionMeta.getAsJsonObject("assetIndex"), "id");
            if (assetId.isBlank()) {
                return false;
            }
            if (resolveExistingAssetIndexPath(assetId) == null) {
                return false;
            }
        }
        return true;
    }

    private boolean requiredAssetFilesPresent(JsonObject versionMeta) {
        if (!versionMeta.has("assetIndex") || !versionMeta.get("assetIndex").isJsonObject()) {
            return true;
        }
        String assetId = readString(versionMeta.getAsJsonObject("assetIndex"), "id");
        if (assetId.isBlank()) {
            return false;
        }
        Path indexPath = resolveExistingAssetIndexPath(assetId);
        if (indexPath == null) {
            return false;
        }
        JsonObject indexJson;
        try {
            indexJson = JsonParser.parseString(Files.readString(indexPath, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ex) {
            return false;
        }
        if (!indexJson.has("objects") || !indexJson.get("objects").isJsonObject()) {
            return false;
        }
        for (var entry : indexJson.getAsJsonObject("objects").entrySet()) {
            JsonObject object = entry.getValue().getAsJsonObject();
            String hash = readString(object, "hash");
            if (hash.isBlank() || resolveExistingAssetObjectPath(hash) == null) {
                return false;
            }
        }
        return true;
    }

    private void writeDependencyMarker(JsonObject versionMeta) throws IOException {
        String versionId = readString(versionMeta, "id");
        if (versionId.isBlank()) {
            return;
        }
        Path marker = dependencyMarkerPath(versionId);
        Files.createDirectories(marker.getParent());
        Properties props = new Properties();
        props.setProperty("signature", dependencySignature(versionMeta));
        try (var out = Files.newOutputStream(marker)) {
            props.store(out, "Limecraft dependency cache");
        }
    }

    private Path dependencyMarkerPath(String versionId) {
        String safe = versionId.replaceAll("[\\\\/:*?\"<>|]", "_");
        return gameDir.resolve("cache").resolve("dependency-markers").resolve(safe + ".properties");
    }

    private Path resolveExistingLibraryPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path local = gameDir.resolve("libraries").resolve(relativePath);
        if (Files.exists(local)) {
            return local;
        }
        Path legacy = legacyDataDir.resolve("libraries").resolve(relativePath);
        if (Files.exists(legacy)) {
            return legacy;
        }
        return null;
    }

    private Path resolveExistingAssetIndexPath(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            return null;
        }
        Path local = gameDir.resolve("assets").resolve("indexes").resolve(assetId + ".json");
        if (Files.exists(local)) {
            return local;
        }
        Path legacy = legacyDataDir.resolve("assets").resolve("indexes").resolve(assetId + ".json");
        if (Files.exists(legacy)) {
            return legacy;
        }
        return null;
    }

    private Path resolveExistingAssetObjectPath(String hash) {
        if (hash == null || hash.isBlank() || hash.length() < 2) {
            return null;
        }
        String prefix = hash.substring(0, 2);
        Path local = gameDir.resolve("assets").resolve("objects").resolve(prefix).resolve(hash);
        if (Files.exists(local)) {
            return local;
        }
        Path legacy = legacyDataDir.resolve("assets").resolve("objects").resolve(prefix).resolve(hash);
        if (Files.exists(legacy)) {
            return legacy;
        }
        return null;
    }

    private String dependencySignature(JsonObject versionMeta) {
        StringBuilder signature = new StringBuilder();
        signature.append(readString(versionMeta, "id")).append('\n');
        if (versionMeta.has("assetIndex") && versionMeta.get("assetIndex").isJsonObject()) {
            JsonObject assetIndex = versionMeta.getAsJsonObject("assetIndex");
            signature.append(readString(assetIndex, "id")).append('\n');
            signature.append(readString(assetIndex, "url")).append('\n');
        }
        if (versionMeta.has("libraries") && versionMeta.get("libraries").isJsonArray()) {
            for (JsonElement libEl : versionMeta.getAsJsonArray("libraries")) {
                signature.append(libEl.toString()).append('\n');
            }
        }
        return Integer.toHexString(signature.toString().hashCode()).toLowerCase(Locale.ROOT);
    }

    private String readString(JsonObject object, String key) {
        if (object == null || key == null || key.isBlank() || !object.has(key)) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ex) {
            return "";
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(String message, double progress);
    }

    private record LibraryArtifact(String path, String url) {}
}
