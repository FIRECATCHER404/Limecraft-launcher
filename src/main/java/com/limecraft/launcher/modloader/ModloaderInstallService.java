package com.limecraft.launcher.modloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.limecraft.launcher.core.Downloader;
import com.limecraft.launcher.core.HttpClient;
import com.limecraft.launcher.core.MinecraftInstallService;
import com.limecraft.launcher.core.VersionEntry;
import okhttp3.Request;
import okhttp3.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModloaderInstallService {
    public static final String META_SIDE = "limecraftSide";
    public static final String META_LOADER = "limecraftLoader";
    public static final String META_LOADER_VERSION = "limecraftLoaderVersion";

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    private static final String QUILT_META = "https://meta.quiltmc.org/v3";
    private static final Pattern XML_VERSION_PATTERN = Pattern.compile("<version>([^<]+)</version>");

    private final HttpClient http;
    private final MinecraftInstallService installService;
    private final Path gameDir;

    public ModloaderInstallService(HttpClient http, MinecraftInstallService installService, Path gameDir) {
        this.http = http;
        this.installService = installService;
        this.gameDir = gameDir;
    }

    public ModloaderInstallResult install(ModloaderInstallRequest request, MinecraftInstallService.ProgressListener listener) throws Exception {
        if (request == null || request.baseVersion() == null) {
            throw new IllegalArgumentException("A base Minecraft version is required.");
        }
        if (request.baseVersion().url() == null || request.baseVersion().url().isBlank()) {
            throw new IllegalArgumentException("The selected base version must have official metadata.");
        }
        if (!request.family().supports(request.side())) {
            throw new IllegalStateException(request.family().displayName() + " " + request.side().toString().toLowerCase(Locale.ROOT) + " install is not implemented yet.");
        }

        return switch (request.family()) {
            case FABRIC -> request.side() == ProfileSide.CLIENT
                    ? installProfileJson(request, listener, "fabric", resolveLatestFabricLoaderVersion(request.baseVersion().id(), request.loaderVersion()), false)
                    : installFabricOrQuiltServer(request, listener, "fabric", resolveLatestFabricLoaderVersion(request.baseVersion().id(), request.loaderVersion()));
            case QUILT -> request.side() == ProfileSide.CLIENT
                    ? installProfileJson(request, listener, "quilt", resolveLatestQuiltLoaderVersion(request.baseVersion().id(), request.loaderVersion()), true)
                    : installFabricOrQuiltServer(request, listener, "quilt", resolveLatestQuiltLoaderVersion(request.baseVersion().id(), request.loaderVersion()));
            case FORGE -> request.side() == ProfileSide.CLIENT
                    ? installForgeLikeClient(request, listener, "forge", resolveLatestForgeVersion(request.baseVersion().id(), request.loaderVersion()))
                    : installForgeLikeServer(request, listener, "forge", resolveLatestForgeVersion(request.baseVersion().id(), request.loaderVersion()));
            case NEOFORGE -> request.side() == ProfileSide.CLIENT
                    ? installForgeLikeClient(request, listener, "neoforge", resolveLatestNeoForgeVersion(request.baseVersion().id(), request.loaderVersion()))
                    : installForgeLikeServer(request, listener, "neoforge", resolveLatestNeoForgeVersion(request.baseVersion().id(), request.loaderVersion()));
        };
    }

    public ModloaderInstallResult reinstallServerFromMetadata(String versionId, JsonObject meta, String javaExecutable, MinecraftInstallService.ProgressListener listener) throws Exception {
        if (meta == null) {
            throw new IllegalStateException("Server metadata is missing.");
        }
        String baseVersionId = readString(meta, "inheritsFrom");
        if (baseVersionId.isBlank()) {
            throw new IllegalStateException("Server metadata is missing inheritsFrom.");
        }
        String loaderId = detectLoaderFromMetadata(meta);
        if (loaderId.isBlank() || "custom".equals(loaderId) || "vanilla".equals(loaderId)) {
            throw new IllegalStateException("Server metadata is missing a supported loader id.");
        }
        String loaderVersion = readString(meta, META_LOADER_VERSION);
        LoaderFamily family = LoaderFamily.fromId(loaderId);
        VersionEntry base = new VersionEntry(baseVersionId, "release", meta.has("url") ? readString(meta, "url") : "", Instant.now().toString());
        return install(new ModloaderInstallRequest(
                family,
                ProfileSide.SERVER,
                base,
                versionId,
                loaderVersion,
                javaExecutable
        ), listener);
    }

    public List<String> listAvailableLoaderVersions(LoaderFamily family, String minecraftVersion) throws Exception {
        if (family == null || minecraftVersion == null || minecraftVersion.isBlank()) {
            return List.of();
        }
        return switch (family) {
            case FABRIC -> listFabricLoaderVersions(minecraftVersion.trim());
            case QUILT -> listQuiltLoaderVersions(minecraftVersion.trim());
            case FORGE -> listForgeVersions(minecraftVersion.trim());
            case NEOFORGE -> listNeoForgeVersions(minecraftVersion.trim());
        };
    }

    private ModloaderInstallResult installProfileJson(ModloaderInstallRequest request, MinecraftInstallService.ProgressListener listener, String loaderId, String loaderVersion, boolean quilt) throws Exception {
        ensureBaseInstalled(request.baseVersion(), listener);

        String customId = requestedOrDefaultName(request, loaderId + "-" + request.baseVersion().id());
        String mcSegment = encode(request.baseVersion().id());
        String loaderSegment = encode(loaderVersion);
        String profileUrl;
        if (quilt) {
            String installerVersion = resolveLatestQuiltInstallerVersion();
            profileUrl = QUILT_META + "/versions/loader/" + mcSegment + "/" + loaderSegment + "/" + encode(installerVersion) + "/profile/json";
        } else {
            profileUrl = FABRIC_META + "/versions/loader/" + mcSegment + "/" + loaderSegment + "/profile/json";
        }

        listener.onProgress("Downloading " + loaderId + " profile...", 0.35);
        JsonObject profile = http.getJson(profileUrl);
        profile.addProperty("id", customId);
        profile.addProperty("type", loaderId);
        profile.addProperty("releaseTime", Instant.now().toString());
        profile.addProperty(META_SIDE, ProfileSide.CLIENT.id());
        profile.addProperty(META_LOADER, loaderId);
        profile.addProperty(META_LOADER_VERSION, loaderVersion);

        Path targetDir = gameDir.resolve("versions").resolve(customId);
        Files.createDirectories(targetDir);
        Path targetManifest = targetDir.resolve(customId + ".json");
        Files.writeString(targetManifest, profile.toString(), StandardCharsets.UTF_8);

        listener.onProgress("Downloading " + loaderId + " dependencies...", 0.5);
        installService.installMetadataDependencies(profile, listener);
        ensureInheritedDependenciesInstalled(profile, listener, new HashSet<>());

        return new ModloaderInstallResult(
                new VersionEntry(customId, loaderId, "", Instant.now().toString(), "client"),
                loaderVersion,
                null
        );
    }

    private ModloaderInstallResult installFabricOrQuiltServer(ModloaderInstallRequest request, MinecraftInstallService.ProgressListener listener, String loaderId, String loaderVersion) throws Exception {
        Path serverDir = gameDir.resolve("servers").resolve(requestedOrDefaultName(request, loaderId + "-server-" + request.baseVersion().id()));
        Files.createDirectories(serverDir);

        String installerVersion = "fabric".equals(loaderId) ? resolveLatestFabricInstallerVersion() : resolveLatestQuiltInstallerVersion();
        String serverJarUrl = ("fabric".equals(loaderId) ? FABRIC_META : QUILT_META)
                + "/versions/loader/" + encode(request.baseVersion().id())
                + "/" + encode(loaderVersion)
                + "/" + encode(installerVersion)
                + "/server/jar";
        Path serverJar = serverDir.resolve("server.jar");
        listener.onProgress("Downloading " + loaderId + " server launcher...", 0.4);
        downloadFile(serverJarUrl, serverJar);
        writeServerMetadata(request, loaderId, loaderVersion, serverDir.getFileName().toString());
        return new ModloaderInstallResult(
                new VersionEntry(serverDir.getFileName().toString(), loaderId, "", Instant.now().toString(), "server"),
                loaderVersion,
                serverDir
        );
    }

    private ModloaderInstallResult installForgeLikeClient(ModloaderInstallRequest request, MinecraftInstallService.ProgressListener listener, String loaderId, String loaderVersion) throws Exception {
        ensureBaseInstalledInCurrentGameDir(request.baseVersion(), listener);

        Path installersDir = gameDir.resolve("cache").resolve("installers");
        Files.createDirectories(installersDir);
        Path installerJar = installersDir.resolve(loaderId + "-" + loaderVersion + "-installer.jar");
        String installerUrl = forgeInstallerUrl(loaderId, loaderVersion);
        listener.onProgress("Downloading " + loaderId + " installer...", 0.3);
        if (!Files.exists(installerJar)) {
            downloadFile(installerUrl, installerJar);
        }

        Set<String> beforeIds = listVersionIds();
        listener.onProgress("Running " + loaderId + " client installer...", 0.55);
        runClientInstaller(request.javaExecutable(), installerJar);

        String installedId = detectInstalledClientVersion(beforeIds, request.baseVersion().id(), loaderId, loaderVersion);
        if (installedId.isBlank()) {
            throw new IllegalStateException("The " + loaderId + " installer completed, but Limecraft could not locate the installed client profile.");
        }

        String targetId = requestedOrDefaultName(request, installedId);
        Path installedJson = versionJsonPath(installedId);
        JsonObject installedMeta = JsonParser.parseString(Files.readString(installedJson, StandardCharsets.UTF_8)).getAsJsonObject();

        if (targetId.equalsIgnoreCase(installedId)) {
            tagClientMetadata(installedMeta, installedId, loaderId, loaderVersion);
            Files.writeString(installedJson, installedMeta.toString(), StandardCharsets.UTF_8);
        } else {
            writeClientAliasMetadata(targetId, installedId, loaderId, loaderVersion);
        }

        JsonObject targetMeta = JsonParser.parseString(Files.readString(versionJsonPath(targetId), StandardCharsets.UTF_8)).getAsJsonObject();
        installService.installMetadataDependencies(targetMeta, listener);
        ensureInheritedDependenciesInstalled(targetMeta, listener, new HashSet<>());

        return new ModloaderInstallResult(
                new VersionEntry(targetId, loaderId, "", Instant.now().toString(), "client"),
                loaderVersion,
                null
        );
    }

    private ModloaderInstallResult installForgeLikeServer(ModloaderInstallRequest request, MinecraftInstallService.ProgressListener listener, String loaderId, String loaderVersion) throws Exception {
        Path serverDir = gameDir.resolve("servers").resolve(requestedOrDefaultName(request, loaderId + "-server-" + request.baseVersion().id()));
        Files.createDirectories(serverDir);

        Path installersDir = gameDir.resolve("cache").resolve("installers");
        Files.createDirectories(installersDir);
        Path installerJar = installersDir.resolve(loaderId + "-" + loaderVersion + "-installer.jar");
        String installerUrl = forgeInstallerUrl(loaderId, loaderVersion);
        listener.onProgress("Downloading " + loaderId + " installer...", 0.3);
        if (!Files.exists(installerJar)) {
            downloadFile(installerUrl, installerJar);
        }

        listener.onProgress("Running " + loaderId + " server installer...", 0.55);
        runServerInstaller(request.javaExecutable(), installerJar, serverDir);
        writeServerMetadata(request, loaderId, loaderVersion, serverDir.getFileName().toString());
        return new ModloaderInstallResult(
                new VersionEntry(serverDir.getFileName().toString(), loaderId, "", Instant.now().toString(), "server"),
                loaderVersion,
                serverDir
        );
    }

    private void writeServerMetadata(ModloaderInstallRequest request, String loaderId, String loaderVersion, String profileId) throws IOException {
        Path versionDir = gameDir.resolve("versions").resolve(profileId);
        Files.createDirectories(versionDir);
        JsonObject meta = new JsonObject();
        meta.addProperty("id", profileId);
        meta.addProperty("type", loaderId);
        meta.addProperty("inheritsFrom", request.baseVersion().id());
        meta.addProperty("releaseTime", Instant.now().toString());
        meta.addProperty(META_SIDE, ProfileSide.SERVER.id());
        meta.addProperty(META_LOADER, loaderId);
        meta.addProperty(META_LOADER_VERSION, loaderVersion);
        meta.addProperty("url", request.baseVersion().url());
        Files.writeString(versionDir.resolve(profileId + ".json"), meta.toString(), StandardCharsets.UTF_8);
    }

    private void ensureBaseInstalled(VersionEntry base, MinecraftInstallService.ProgressListener listener) throws Exception {
        if (installService.versionInstalled(base.id())) {
            return;
        }
        listener.onProgress("Installing base version " + base.id() + "...", 0.1);
        installService.installVersion(base, listener);
    }

    private void ensureBaseInstalledInCurrentGameDir(VersionEntry base, MinecraftInstallService.ProgressListener listener) throws Exception {
        Path baseDir = gameDir.resolve("versions").resolve(base.id());
        Path json = baseDir.resolve(base.id() + ".json");
        Path jar = baseDir.resolve(base.id() + ".jar");
        if (Files.exists(json) && Files.exists(jar)) {
            return;
        }
        listener.onProgress("Installing base version " + base.id() + " for installer compatibility...", 0.1);
        installService.installVersion(base, listener);
    }

    private void ensureInheritedDependenciesInstalled(JsonObject meta, MinecraftInstallService.ProgressListener listener, Set<String> visiting) throws Exception {
        if (meta == null || !meta.has("inheritsFrom")) {
            return;
        }
        String parentId = readString(meta, "inheritsFrom");
        if (parentId.isBlank() || !visiting.add(parentId)) {
            return;
        }

        Path parentJson = installService.findVersionJson(parentId);
        if (!Files.exists(parentJson)) {
            throw new IllegalStateException("Missing inherited metadata for " + parentId);
        }
        JsonObject parentMeta = JsonParser.parseString(Files.readString(parentJson, StandardCharsets.UTF_8)).getAsJsonObject();
        installService.installMetadataDependenciesIfNeeded(parentMeta, listener);
        ensureInheritedDependenciesInstalled(parentMeta, listener, visiting);
    }

    private String resolveLatestFabricLoaderVersion(String minecraftVersion, String preferred) throws Exception {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        List<String> versions = listFabricLoaderVersions(minecraftVersion);
        if (versions.isEmpty()) {
            throw new IllegalStateException("No Fabric loader available for " + minecraftVersion);
        }
        return versions.get(0);
    }

    private String resolveLatestFabricInstallerVersion() throws Exception {
        JsonArray installers = getJsonArray(FABRIC_META + "/versions/installer");
        if (installers.isEmpty()) {
            throw new IllegalStateException("No Fabric installer version is available.");
        }
        JsonObject installer = installers.get(0).getAsJsonObject();
        return installer.get("version").getAsString();
    }

    private String resolveLatestQuiltLoaderVersion(String minecraftVersion, String preferred) throws Exception {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        List<String> versions = listQuiltLoaderVersions(minecraftVersion);
        if (versions.isEmpty()) {
            throw new IllegalStateException("No Quilt loader available for " + minecraftVersion);
        }
        return versions.get(0);
    }

    private String resolveLatestQuiltInstallerVersion() throws Exception {
        JsonArray installers = getJsonArray(QUILT_META + "/versions/installer");
        if (installers.isEmpty()) {
            throw new IllegalStateException("No Quilt installer version is available.");
        }
        JsonObject installer = installers.get(0).getAsJsonObject();
        return readString(installer, "version");
    }

    private String resolveLatestForgeVersion(String minecraftVersion, String preferred) throws Exception {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        List<String> versions = listForgeVersions(minecraftVersion);
        if (versions.isEmpty()) {
            throw new IllegalStateException("No Forge version was found for Minecraft " + minecraftVersion + ". Enter a loader version manually.");
        }
        return versions.get(0);
    }

    private String resolveLatestNeoForgeVersion(String minecraftVersion, String preferred) throws Exception {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        List<String> versions = listNeoForgeVersions(minecraftVersion);
        if (versions.isEmpty()) {
            throw new IllegalStateException("No NeoForge version was found. Enter a loader version manually.");
        }
        return versions.get(0);
    }

    private List<String> listFabricLoaderVersions(String minecraftVersion) throws Exception {
        JsonArray loaders = getJsonArray(FABRIC_META + "/versions/loader/" + encode(minecraftVersion));
        LinkedHashSet<String> stable = new LinkedHashSet<>();
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (int i = 0; i < loaders.size(); i++) {
            JsonObject loader = loaders.get(i).getAsJsonObject();
            if (!loader.has("loader") || !loader.get("loader").isJsonObject()) {
                continue;
            }
            JsonObject loaderInfo = loader.getAsJsonObject("loader");
            String version = readString(loaderInfo, "version");
            if (version.isBlank()) {
                continue;
            }
            all.add(version);
            if (loaderInfo.has("stable") && loaderInfo.get("stable").getAsBoolean()) {
                stable.add(version);
            }
        }
        return mergePreferredVersions(stable, all);
    }

    private List<String> listQuiltLoaderVersions(String minecraftVersion) throws Exception {
        JsonArray loaders = getJsonArray(QUILT_META + "/versions/loader/" + encode(minecraftVersion));
        LinkedHashSet<String> stable = new LinkedHashSet<>();
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (int i = 0; i < loaders.size(); i++) {
            JsonObject loader = loaders.get(i).getAsJsonObject();
            JsonObject loaderInfo = loader.has("loader") && loader.get("loader").isJsonObject()
                    ? loader.getAsJsonObject("loader")
                    : loader;
            String version = readString(loaderInfo, "version");
            if (version.isBlank()) {
                continue;
            }
            all.add(version);
            if (loaderInfo.has("stable") && loaderInfo.get("stable").getAsBoolean()) {
                stable.add(version);
            }
        }
        return mergePreferredVersions(stable, all);
    }

    private List<String> listForgeVersions(String minecraftVersion) throws Exception {
        List<String> versions = readMavenVersions("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml");
        List<String> matches = new ArrayList<>();
        String prefix = minecraftVersion + "-";
        for (String version : versions) {
            if (version.startsWith(prefix)) {
                matches.add(version);
            }
        }
        Collections.reverse(matches);
        return dedupe(matches);
    }

    private List<String> listNeoForgeVersions(String minecraftVersion) throws Exception {
        List<String> versions = readMavenVersions("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
        List<String> matches = new ArrayList<>();
        String mcFamily = minecraftVersion.startsWith("1.") ? minecraftVersion.substring(2) : minecraftVersion;
        for (String version : versions) {
            if (version.startsWith(mcFamily + ".") || version.startsWith(mcFamily + "-")) {
                matches.add(version);
            }
        }
        Collections.reverse(matches);
        if (!matches.isEmpty()) {
            return dedupe(matches);
        }
        List<String> fallback = new ArrayList<>(versions);
        Collections.reverse(fallback);
        return dedupe(fallback);
    }

    private List<String> mergePreferredVersions(Set<String> preferred, Set<String> all) {
        List<String> merged = new ArrayList<>(preferred);
        for (String version : all) {
            if (!preferred.contains(version)) {
                merged.add(version);
            }
        }
        return merged;
    }

    private List<String> dedupe(List<String> versions) {
        return new ArrayList<>(new LinkedHashSet<>(versions));
    }

    private List<String> readMavenVersions(String metadataUrl) throws Exception {
        Request request = new Request.Builder().url(metadataUrl).get().build();
        try (Response response = http.raw().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code() + " for " + metadataUrl);
            }
            String body = response.body().string();
            Matcher matcher = XML_VERSION_PATTERN.matcher(body);
            List<String> versions = new ArrayList<>();
            while (matcher.find()) {
                versions.add(matcher.group(1).trim());
            }
            return versions;
        }
    }

    private String forgeInstallerUrl(String loaderId, String loaderVersion) {
        return switch (loaderId) {
            case "forge" -> "https://maven.minecraftforge.net/net/minecraftforge/forge/" + loaderVersion + "/forge-" + loaderVersion + "-installer.jar";
            case "neoforge" -> "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar";
            default -> throw new IllegalArgumentException("Unsupported installer family: " + loaderId);
        };
    }

    private void runClientInstaller(String javaExecutable, Path installerJar) throws Exception {
        Exception firstFailure = null;
        List<String> argsWithTarget = new ArrayList<>();
        argsWithTarget.add(normalizeJava(javaExecutable));
        argsWithTarget.add("-jar");
        argsWithTarget.add(installerJar.toAbsolutePath().toString());
        argsWithTarget.add("--installClient");
        argsWithTarget.add(gameDir.toAbsolutePath().toString());
        try {
            runInstaller(argsWithTarget, gameDir);
            return;
        } catch (Exception ex) {
            firstFailure = ex;
        }

        List<String> args = List.of(
                normalizeJava(javaExecutable),
                "-jar",
                installerJar.toAbsolutePath().toString(),
                "--installClient"
        );
        try {
            runInstaller(args, gameDir);
        } catch (Exception secondFailure) {
            secondFailure.addSuppressed(firstFailure);
            throw secondFailure;
        }
    }

    private void runServerInstaller(String javaExecutable, Path installerJar, Path serverDir) throws Exception {
        List<String> args = List.of(
                normalizeJava(javaExecutable),
                "-jar",
                installerJar.toAbsolutePath().toString(),
                "--installServer"
        );
        runInstaller(args, serverDir);
    }

    private void runInstaller(List<String> args, Path workingDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            in.transferTo(output);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(output.toString(StandardCharsets.UTF_8).trim());
        }
    }

    private void downloadFile(String url, Path target) throws IOException {
        new Downloader(http.raw()).downloadTo(url, target);
    }

    private JsonArray getJsonArray(String url) throws Exception {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.raw().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("HTTP " + response.code() + " for " + url);
            }
            return JsonParser.parseString(response.body().string()).getAsJsonArray();
        }
    }

    private String requestedOrDefaultName(ModloaderInstallRequest request, String fallback) {
        String requested = request.customName() == null ? "" : request.customName().trim();
        return requested.isBlank() ? fallback : requested;
    }

    private String normalizeJava(String javaExecutable) {
        return javaExecutable == null || javaExecutable.isBlank() ? "java" : javaExecutable.trim();
    }

    private Set<String> listVersionIds() throws IOException {
        Set<String> ids = new HashSet<>();
        Path versionsDir = gameDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return ids;
        }
        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory)
                    .forEach(dir -> ids.add(dir.getFileName().toString()));
        }
        return ids;
    }

    private String detectInstalledClientVersion(Set<String> beforeIds, String baseVersionId, String loaderId, String loaderVersion) throws IOException {
        Path versionsDir = gameDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return "";
        }

        String bestId = "";
        int bestScore = Integer.MIN_VALUE;
        long bestModified = Long.MIN_VALUE;
        try (var stream = Files.list(versionsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String versionId = dir.getFileName().toString();
                Path json = dir.resolve(versionId + ".json");
                if (!Files.exists(json)) {
                    continue;
                }
                JsonObject meta;
                try {
                    meta = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (Exception ignored) {
                    continue;
                }
                int score = scoreInstalledClientVersion(beforeIds, baseVersionId, loaderId, loaderVersion, versionId, meta);
                if (score <= Integer.MIN_VALUE / 2) {
                    continue;
                }
                long modified = Files.getLastModifiedTime(json).toMillis();
                if (score > bestScore || (score == bestScore && modified > bestModified)) {
                    bestId = versionId;
                    bestScore = score;
                    bestModified = modified;
                }
            }
        }

        if (!bestId.isBlank()) {
            return bestId;
        }

        String fallback = fallbackInstalledClientVersionId(baseVersionId, loaderId, loaderVersion);
        return Files.exists(versionJsonPath(fallback)) ? fallback : "";
    }

    private int scoreInstalledClientVersion(Set<String> beforeIds, String baseVersionId, String loaderId, String loaderVersion, String versionId, JsonObject meta) {
        String detectedLoader = detectLoaderFromMetadata(meta);
        if (!loaderId.equalsIgnoreCase(detectedLoader)) {
            return Integer.MIN_VALUE;
        }

        int score = 0;
        if (beforeIds != null && !beforeIds.contains(versionId)) {
            score += 50;
        }

        String normalizedVersionId = normalizeToken(versionId + " " + readString(meta, "id") + " " + readString(meta, "inheritsFrom"));
        String normalizedBase = normalizeToken(baseVersionId);
        if (!normalizedBase.isBlank() && normalizedVersionId.contains(normalizedBase)) {
            score += 10;
        }

        String normalizedLoaderVersion = normalizeToken(loaderVersion);
        if (!normalizedLoaderVersion.isBlank() && normalizedVersionId.contains(normalizedLoaderVersion)) {
            score += 10;
        }

        String inheritsFrom = readString(meta, "inheritsFrom");
        if (!inheritsFrom.isBlank() && inheritsFrom.equalsIgnoreCase(baseVersionId)) {
            score += 10;
        }
        if (meta.has("mainClass") || meta.has("minecraftArguments") || meta.has("arguments")) {
            score += 2;
        }
        return score;
    }

    private String fallbackInstalledClientVersionId(String baseVersionId, String loaderId, String loaderVersion) {
        String suffix = loaderVersion;
        String prefix = baseVersionId + "-";
        if (loaderVersion != null && loaderVersion.startsWith(prefix)) {
            suffix = loaderVersion.substring(prefix.length());
        }
        return baseVersionId + "-" + loaderId + "-" + suffix;
    }

    private void tagClientMetadata(JsonObject meta, String versionId, String loaderId, String loaderVersion) {
        if (meta == null) {
            return;
        }
        meta.addProperty("id", versionId);
        meta.addProperty("type", loaderId);
        if (!meta.has("releaseTime")) {
            meta.addProperty("releaseTime", Instant.now().toString());
        }
        meta.addProperty(META_SIDE, ProfileSide.CLIENT.id());
        meta.addProperty(META_LOADER, loaderId);
        meta.addProperty(META_LOADER_VERSION, loaderVersion);
    }

    private void writeClientAliasMetadata(String targetId, String parentId, String loaderId, String loaderVersion) throws IOException {
        Path targetDir = gameDir.resolve("versions").resolve(targetId);
        Files.createDirectories(targetDir);
        JsonObject alias = new JsonObject();
        alias.addProperty("id", targetId);
        alias.addProperty("type", loaderId);
        alias.addProperty("inheritsFrom", parentId);
        alias.addProperty("releaseTime", Instant.now().toString());
        alias.addProperty(META_SIDE, ProfileSide.CLIENT.id());
        alias.addProperty(META_LOADER, loaderId);
        alias.addProperty(META_LOADER_VERSION, loaderVersion);
        Files.writeString(targetDir.resolve(targetId + ".json"), alias.toString(), StandardCharsets.UTF_8);
    }

    private Path versionJsonPath(String versionId) {
        return gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
    }

    private String detectLoaderFromMetadata(JsonObject meta) {
        String explicit = readString(meta, META_LOADER);
        if (!explicit.isBlank()) {
            return explicit.toLowerCase(Locale.ROOT);
        }

        String type = readString(meta, "type").toLowerCase(Locale.ROOT);
        if ("fabric".equals(type) || "quilt".equals(type) || "forge".equals(type) || "neoforge".equals(type)) {
            return type;
        }
        if (hasLibraryPrefix(meta, "net.fabricmc:fabric-loader")) {
            return "fabric";
        }
        if (hasLibraryPrefix(meta, "org.quiltmc:quilt-loader")) {
            return "quilt";
        }
        if (hasLibraryPrefix(meta, "net.neoforged:neoforge")) {
            return "neoforge";
        }
        if (hasLibraryPrefix(meta, "net.minecraftforge:forge")
                || hasLibraryPrefix(meta, "net.minecraftforge:fmlloader")
                || hasLibraryPrefix(meta, "cpw.mods:modlauncher")) {
            return "forge";
        }
        if ("custom".equals(type)) {
            return "custom";
        }
        return type.isBlank() ? "vanilla" : type;
    }

    private boolean hasLibraryPrefix(JsonObject meta, String prefix) {
        if (meta == null || prefix == null || prefix.isBlank()) {
            return false;
        }
        if (!meta.has("libraries") || !meta.get("libraries").isJsonArray()) {
            return false;
        }
        JsonArray libraries = meta.getAsJsonArray("libraries");
        for (int i = 0; i < libraries.size(); i++) {
            JsonObject lib = libraries.get(i).getAsJsonObject();
            String name = readString(lib, "name");
            if (!name.isBlank() && name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeToken(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String readString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
