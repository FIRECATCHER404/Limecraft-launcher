package com.limecraft.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.limecraft.launcher.auth.MinecraftAccount;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

public final class MinecraftLaunchService {
    private static final Pattern JAVA_VERSION_QUOTED = Pattern.compile("\"([^\"]+)\"");
    private final Path gameDir;

    public MinecraftLaunchService(Path gameDir) {
        this.gameDir = gameDir;
    }

    public Process launch(String versionId, JsonObject versionMeta, MinecraftAccount account, String offlineUsername, String javaPath, String xmx, boolean includeLimecraftSuffix, boolean hideCustomSuffix) throws Exception {
        String os = detectOs();
        JsonObject effectiveMeta = resolveEffectiveMeta(versionId, versionMeta, new HashSet<>());
        Path gameJar = resolveGameJar(versionId, versionMeta, new HashSet<>());
        if (!Files.exists(gameJar)) {
            throw new IllegalStateException("Missing client jar for " + versionId + " (or its parent version)");
        }

        Path instanceDir = gameDir.resolve("instances").resolve(safeFolderName(versionId));
        Files.createDirectories(instanceDir);

        List<String> classpathEntries = buildClasspathEntries(effectiveMeta, gameJar, os);

        Path nativesDir = gameDir.resolve("natives").resolve(versionId);
        Files.createDirectories(nativesDir);
        extractNatives(effectiveMeta, nativesDir, os);

        Map<String, String> vars = buildVariables(effectiveMeta, account, offlineUsername, classpathEntries, nativesDir, instanceDir, versionId, includeLimecraftSuffix, hideCustomSuffix);
        List<String> args = new ArrayList<>();
        args.add(javaPath == null || javaPath.isBlank() ? "java" : javaPath);
        args.add("-Xmx" + (xmx == null || xmx.isBlank() ? "4G" : xmx));
        args.add("-Djava.library.path=" + nativesDir);
        String mainClass = resolveMainClass(effectiveMeta, gameJar);
        String appletClass = "";
        if (shouldUseLegacyDirectMain(effectiveMeta, gameJar)) {
            mainClass = "net.minecraft.client.Minecraft";
        }
        if ("net.minecraft.client.MinecraftApplet".equals(mainClass)) {
            appletClass = mainClass;
            mainClass = "com.limecraft.launcher.core.LegacyAppletLauncher";
            addLauncherClasspath(classpathEntries);
        }
        if (mainClass.isBlank()) {
            throw new IllegalStateException("Manifest is missing a valid mainClass.");
        }
        if ("net.minecraft.launchwrapper.Launch".equals(mainClass)) {
            String configuredJava = javaPath == null || javaPath.isBlank() ? "java" : javaPath.trim();
            int javaMajor = readJavaMajorVersion(configuredJava);
            if (javaMajor > 8) {
                throw new IllegalStateException(
                        "This version uses LaunchWrapper and must run with Java 8. " +
                        "Current runtime is Java " + javaMajor + ". " +
                        "Pick a Java 8 executable in Java Path."
                );
            }
        }

        if (effectiveMeta.has("arguments")) {
            JsonObject arguments = effectiveMeta.getAsJsonObject("arguments");
            if (arguments.has("jvm") && arguments.get("jvm").isJsonArray()) {
                addArgArray(arguments.getAsJsonArray("jvm"), args, vars, os);
            }
        }

        args.add("-cp");
        args.add(String.join(System.getProperty("path.separator"), classpathEntries));
        args.add(mainClass);
        if (!appletClass.isBlank()) {
            args.add(appletClass);
            args.add("username=" + vars.getOrDefault("auth_player_name", "Player"));
            args.add("session=" + vars.getOrDefault("auth_session", "0"));
            args.add("gameDir=" + vars.getOrDefault("game_directory", instanceDir.toString()));
            args.add("assetsDir=" + vars.getOrDefault("assets_root", gameDir.resolve("assets").toString()));
            args.add("assetIndex=" + vars.getOrDefault("assets_index_name", "legacy"));
            args.add("width=" + vars.getOrDefault("resolution_width", "854"));
            args.add("height=" + vars.getOrDefault("resolution_height", "480"));
        }

        if (effectiveMeta.has("arguments")) {
            JsonObject arguments = effectiveMeta.getAsJsonObject("arguments");
            if (arguments.has("game") && arguments.get("game").isJsonArray()) {
                addArgArray(arguments.getAsJsonArray("game"), args, vars, os);
            }
        } else if (effectiveMeta.has("minecraftArguments")) {
            String raw = readString(effectiveMeta, "minecraftArguments");
            if (!raw.isBlank()) {
                for (String tok : raw.split(" ")) {
                    args.add(replace(tok, vars));
                }
            }
        }

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(instanceDir.toFile());
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private JsonObject resolveEffectiveMeta(String versionId, JsonObject meta, Set<String> visiting) throws IOException {
        if (!visiting.add(versionId)) {
            throw new IllegalStateException("Cyclic version inheritance detected at " + versionId);
        }

        JsonObject child = meta.deepCopy();
        if (!child.has("inheritsFrom")) {
            visiting.remove(versionId);
            return child;
        }

        String parentId = readString(child, "inheritsFrom");
        if (parentId.isBlank()) {
            visiting.remove(versionId);
            return child;
        }
        JsonObject parentMeta = loadVersionMeta(parentId);
        JsonObject parentResolved = resolveEffectiveMeta(parentId, parentMeta, visiting);

        JsonObject merged = parentResolved.deepCopy();

        JsonArray mergedLibraries = new JsonArray();
        if (parentResolved.has("libraries") && parentResolved.get("libraries").isJsonArray()) {
            for (JsonElement el : parentResolved.getAsJsonArray("libraries")) {
                mergedLibraries.add(el);
            }
        }
        if (child.has("libraries") && child.get("libraries").isJsonArray()) {
            for (JsonElement el : child.getAsJsonArray("libraries")) {
                mergedLibraries.add(el);
            }
        }

        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {
            String key = entry.getKey();
            JsonElement childValue = entry.getValue();
            if ("inheritsFrom".equals(key) || "libraries".equals(key)) {
                continue;
            }
            if (childValue == null || childValue.isJsonNull()) {
                // Preserve parent value when child explicitly sets null.
                continue;
            }
            if ("arguments".equals(key) && childValue.isJsonObject()) {
                JsonObject parentArgs = merged.has("arguments") && merged.get("arguments").isJsonObject()
                        ? merged.getAsJsonObject("arguments")
                        : null;
                JsonObject childArgs = childValue.getAsJsonObject();
                merged.add("arguments", mergeArguments(parentArgs, childArgs));
                continue;
            }
            merged.add(key, childValue);
        }
        merged.add("libraries", mergedLibraries);

        visiting.remove(versionId);
        return merged;
    }

    private JsonObject mergeArguments(JsonObject parentArgs, JsonObject childArgs) {
        JsonObject out = parentArgs == null ? new JsonObject() : parentArgs.deepCopy();
        if (childArgs == null) {
            return out;
        }

        mergeArgumentArray(out, parentArgs, childArgs, "jvm");
        mergeArgumentArray(out, parentArgs, childArgs, "game");

        for (Map.Entry<String, JsonElement> entry : childArgs.entrySet()) {
            String key = entry.getKey();
            JsonElement childValue = entry.getValue();
            if ("jvm".equals(key) || "game".equals(key)) {
                continue;
            }
            if (childValue == null || childValue.isJsonNull()) {
                continue;
            }
            out.add(key, childValue);
        }
        return out;
    }

    private void mergeArgumentArray(JsonObject out, JsonObject parentArgs, JsonObject childArgs, String key) {
        JsonArray merged = new JsonArray();
        if (parentArgs != null && parentArgs.has(key) && parentArgs.get(key).isJsonArray()) {
            for (JsonElement el : parentArgs.getAsJsonArray(key)) {
                merged.add(el);
            }
        }
        if (childArgs.has(key) && childArgs.get(key).isJsonArray()) {
            for (JsonElement el : childArgs.getAsJsonArray(key)) {
                merged.add(el);
            }
        }
        if (!merged.isEmpty()) {
            out.add(key, merged);
        } else if (out.has(key)) {
            out.remove(key);
        }
    }

    private JsonObject loadVersionMeta(String versionId) throws IOException {
        Path jsonPath = gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (!Files.exists(jsonPath)) {
            throw new IllegalStateException("Missing inherited version metadata for " + versionId);
        }
        return com.google.gson.JsonParser.parseString(Files.readString(jsonPath)).getAsJsonObject();
    }

    private Path resolveGameJar(String versionId, JsonObject meta, Set<String> visiting) throws IOException {
        if (!visiting.add(versionId)) {
            throw new IllegalStateException("Cyclic version inheritance detected at " + versionId);
        }

        Path candidate = gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".jar");
        String parentId = readString(meta, "inheritsFrom");
        if (Files.exists(candidate) || parentId.isBlank()) {
            visiting.remove(versionId);
            return candidate;
        }

        JsonObject parentMeta = loadVersionMeta(parentId);
        Path parentJar = resolveGameJar(parentId, parentMeta, visiting);
        visiting.remove(versionId);
        return parentJar;
    }

    private void addArgArray(JsonArray array, List<String> out, Map<String, String> vars, String os) {
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                out.add(replace(element.getAsString(), vars));
                continue;
            }
            JsonObject ruleArg = element.getAsJsonObject();
            if (ruleArg.has("rules") && !passesRules(ruleArg.getAsJsonArray("rules"), os)) {
                continue;
            }
            JsonElement value = ruleArg.get("value");
            if (value.isJsonPrimitive()) {
                out.add(replace(value.getAsString(), vars));
            } else {
                for (JsonElement v : value.getAsJsonArray()) {
                    out.add(replace(v.getAsString(), vars));
                }
            }
        }
    }

    private boolean passesRules(JsonArray rules, String os) {
        boolean allowed = false;
        for (JsonElement el : rules) {
            JsonObject rule = el.getAsJsonObject();
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

    private boolean isLibraryAllowed(JsonObject lib, String os) {
        if (!lib.has("rules")) {
            return true;
        }
        return passesRules(lib.getAsJsonArray("rules"), os);
    }

    private Path resolveLibraryPath(JsonObject lib) {
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
            String rel = lib.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString();
            return gameDir.resolve("libraries").resolve(rel);
        }
        if (!lib.has("name")) {
            return null;
        }
        return gameDir.resolve("libraries").resolve(toMavenPath(lib.get("name").getAsString()));
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

    private List<String> buildClasspathEntries(JsonObject effectiveMeta, Path gameJar, String os) {
        Map<String, Path> deduped = new LinkedHashMap<>();
        JsonArray libs = effectiveMeta.has("libraries") && effectiveMeta.get("libraries").isJsonArray()
                ? effectiveMeta.getAsJsonArray("libraries")
                : new JsonArray();

        for (JsonElement libEl : libs) {
            JsonObject lib = libEl.getAsJsonObject();
            if (!isLibraryAllowed(lib, os)) {
                continue;
            }
            Path libPath = resolveLibraryPath(lib);
            if (libPath == null || !Files.exists(libPath)) {
                continue;
            }

            String key = resolveLibraryKey(lib, libPath);
            // Keep the last declaration when the same artifact appears multiple times (common with modded inheritance).
            deduped.remove(key);
            deduped.put(key, libPath.toAbsolutePath().normalize());
        }

        List<String> classpathEntries = new ArrayList<>(deduped.size() + 1);
        for (Path path : deduped.values()) {
            classpathEntries.add(path.toString());
        }
        classpathEntries.add(gameJar.toAbsolutePath().normalize().toString());
        return classpathEntries;
    }

    private String resolveLibraryKey(JsonObject lib, Path libPath) {
        if (lib.has("name")) {
            String fromNotation = libraryKeyFromNotation(lib.get("name").getAsString());
            if (fromNotation != null) {
                return fromNotation;
            }
        }

        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
            String rel = lib.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString();
            String fromPath = libraryKeyFromMavenPath(rel);
            if (fromPath != null) {
                return fromPath;
            }
        }

        Path librariesRoot = gameDir.resolve("libraries").toAbsolutePath().normalize();
        Path absoluteLib = libPath.toAbsolutePath().normalize();
        if (absoluteLib.startsWith(librariesRoot)) {
            String rel = librariesRoot.relativize(absoluteLib).toString().replace('\\', '/');
            String fromPath = libraryKeyFromMavenPath(rel);
            if (fromPath != null) {
                return fromPath;
            }
        }

        return "path:" + absoluteLib;
    }

    private String libraryKeyFromNotation(String notation) {
        if (notation == null || notation.isBlank()) {
            return null;
        }
        String ext = "jar";
        String baseNotation = notation;
        int at = notation.indexOf('@');
        if (at >= 0) {
            baseNotation = notation.substring(0, at);
            if (at + 1 < notation.length()) {
                ext = notation.substring(at + 1);
            }
        }

        String[] parts = baseNotation.split(":");
        if (parts.length < 3) {
            return null;
        }
        String classifier = parts.length >= 4 ? parts[3] : null;
        return buildLibraryKey(parts[0], parts[1], classifier, ext);
    }

    private String libraryKeyFromMavenPath(String mavenPath) {
        if (mavenPath == null || mavenPath.isBlank()) {
            return null;
        }

        String normalized = mavenPath.replace('\\', '/');
        String[] segments = normalized.split("/");
        if (segments.length < 4) {
            return null;
        }

        int fileIndex = segments.length - 1;
        int versionIndex = segments.length - 2;
        int artifactIndex = segments.length - 3;

        String artifact = segments[artifactIndex];
        String version = segments[versionIndex];
        String fileName = segments[fileIndex];
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }

        String stem = fileName.substring(0, dot);
        String ext = fileName.substring(dot + 1);
        String prefix = artifact + "-" + version;
        if (!stem.startsWith(prefix)) {
            return null;
        }

        String classifier = null;
        if (stem.length() > prefix.length()) {
            if (stem.charAt(prefix.length()) != '-') {
                return null;
            }
            classifier = stem.substring(prefix.length() + 1);
        }

        StringBuilder group = new StringBuilder();
        for (int i = 0; i < artifactIndex; i++) {
            if (i > 0) {
                group.append('.');
            }
            group.append(segments[i]);
        }

        return buildLibraryKey(group.toString(), artifact, classifier, ext);
    }

    private String buildLibraryKey(String group, String artifact, String classifier, String ext) {
        if (group == null || group.isBlank() || artifact == null || artifact.isBlank()) {
            return null;
        }
        String safeExt = ext == null || ext.isBlank() ? "jar" : ext.trim();
        StringBuilder key = new StringBuilder();
        key.append(group.trim()).append(':').append(artifact.trim());
        if (classifier != null && !classifier.isBlank()) {
            key.append(':').append(classifier.trim());
        }
        key.append('@').append(safeExt);
        return key.toString();
    }

    private void extractNatives(JsonObject meta, Path nativesDir, String os) throws IOException {
        JsonArray libs = meta.has("libraries") && meta.get("libraries").isJsonArray()
                ? meta.getAsJsonArray("libraries")
                : new JsonArray();

        String key = switch (os) {
            case "windows" -> "natives-windows";
            case "osx" -> "natives-macos";
            default -> "natives-linux";
        };

        for (JsonElement libEl : libs) {
            JsonObject lib = libEl.getAsJsonObject();
            if (!lib.has("downloads") || !lib.getAsJsonObject("downloads").has("classifiers")) {
                continue;
            }
            JsonObject classifiers = lib.getAsJsonObject("downloads").getAsJsonObject("classifiers");
            String fallbackKey = "osx".equals(os) ? "natives-osx" : key;
            String chosenKey = classifiers.has(key) ? key : fallbackKey;
            if (!classifiers.has(chosenKey)) {
                continue;
            }
            String path = classifiers.getAsJsonObject(chosenKey).get("path").getAsString();
            Path nativeJar = gameDir.resolve("libraries").resolve(path);
            if (!Files.exists(nativeJar)) {
                Path fallbackJar = fallbackNativeJar(nativeJar);
                if (fallbackJar != null) {
                    nativeJar = fallbackJar;
                }
            }
            if (!Files.exists(nativeJar)) {
                continue;
            }
            try (JarFile jar = new JarFile(nativeJar.toFile())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || name.startsWith("META-INF")) {
                        continue;
                    }
                    Path out = nativesDir.resolve(name).normalize();
                    if (!out.startsWith(nativesDir)) {
                        continue;
                    }
                    Files.createDirectories(out.getParent());
                    try (var in = jar.getInputStream(entry)) {
                        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private Map<String, String> buildVariables(JsonObject meta, MinecraftAccount account, String offlineUsername, List<String> cp, Path nativesDir, Path instanceDir, String versionId, boolean includeLimecraftSuffix, boolean hideCustomSuffix) {
        String username = account != null
                ? account.username()
                : (offlineUsername == null || offlineUsername.isBlank() ? "Player" : offlineUsername);
        String uuid = account != null ? account.uuid() : offlineUuidForUsername(username);
        String token = account != null ? account.accessToken() : "0";
        String xuid = account != null ? account.xuid() : "0";
        String clientId = UUID.randomUUID().toString();

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", username);
        vars.put("version_name", versionId);
        vars.put("game_directory", instanceDir.toAbsolutePath().toString());
        vars.put("assets_root", gameDir.resolve("assets").toAbsolutePath().toString());
        vars.put("game_assets", gameDir.resolve("assets").toAbsolutePath().toString());
        vars.put("assets_index_name", resolveAssetsIndexName(meta));
        vars.put("auth_uuid", uuid);
        vars.put("auth_access_token", token);
        vars.put("auth_session", token);
        vars.put("auth_xuid", xuid);
        vars.put("clientid", clientId);
        vars.put("user_type", account != null ? "msa" : "legacy");
        vars.put("version_type", buildVersionType(meta, includeLimecraftSuffix, hideCustomSuffix));
        vars.put("natives_directory", nativesDir.toAbsolutePath().toString());
        vars.put("launcher_name", AppVersion.APP_NAME);
        vars.put("launcher_version", AppVersion.CURRENT);
        vars.put("classpath", String.join(System.getProperty("path.separator"), cp));
        vars.put("resolution_width", "1280");
        vars.put("resolution_height", "720");
        return vars;
    }

    private String offlineUuidForUsername(String username) {
        String source = "OfflinePlayer:" + username;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    private boolean shouldUseLegacyDirectMain(JsonObject meta, Path gameJar) {
        String type = readString(meta, "type");
        String mainClass = readString(meta, "mainClass");
        if (!"custom".equalsIgnoreCase(type)) {
            return false;
        }
        if (!"net.minecraft.launchwrapper.Launch".equals(mainClass)) {
            return false;
        }
        if (!meta.has("minecraftArguments")) {
            return false;
        }
        return jarContains(gameJar, "net/minecraft/client/Minecraft.class");
    }

    private boolean jarContains(Path jarPath, String entryName) {
        if (!Files.exists(jarPath)) {
            return false;
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.getEntry(entryName) != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    private Path fallbackNativeJar(Path primary) {
        if (primary == null) {
            return null;
        }
        String name = primary.getFileName().toString();
        String[] suffixes = new String[] { "-x86_64", "-x64", "-x86", "-64", "-32" };
        for (String suffix : suffixes) {
            String altName = name.replace(suffix + ".jar", ".jar");
            if (!altName.equals(name)) {
                Path alt = primary.getParent().resolve(altName);
                if (Files.exists(alt)) {
                    return alt;
                }
            }
        }
        return null;
    }

    private void addLauncherClasspath(List<String> classpathEntries) {
        try {
            var url = MinecraftLaunchService.class.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) {
                return;
            }
            Path path = java.nio.file.Paths.get(url.toURI());
            String cp = path.toAbsolutePath().normalize().toString();
            if (!classpathEntries.contains(cp)) {
                classpathEntries.add(0, cp);
            }
        } catch (Exception ignored) {
        }
    }

    private String resolveMainClass(JsonObject meta, Path gameJar) {
        String configured = readString(meta, "mainClass");
        if (!configured.isBlank()) {
            return configured;
        }
        String manifestMain = readJarManifestMainClass(gameJar);
        if (!manifestMain.isBlank()) {
            return manifestMain;
        }
        if (isLegacyClientMeta(meta)) {
            if (jarContains(gameJar, "net/minecraft/client/Minecraft.class")) {
                return "net.minecraft.client.Minecraft";
            }
            if (jarContains(gameJar, "net/minecraft/client/MinecraftApplet.class")) {
                return "net.minecraft.client.MinecraftApplet";
            }
        }
        if (metaHasArgumentToken(meta, "--launchTarget")) {
            return "cpw.mods.modlauncher.Launcher";
        }
        if (metaHasArgumentToken(meta, "--tweakClass")) {
            return "net.minecraft.launchwrapper.Launch";
        }
        if (hasLibraryPrefix(meta, "net.fabricmc:fabric-loader")) {
            return "net.fabricmc.loader.impl.launch.knot.KnotClient";
        }
        if (hasLibraryPrefix(meta, "org.quiltmc:quilt-loader")) {
            return "org.quiltmc.loader.impl.launch.knot.KnotClient";
        }
        if (hasLibraryPrefix(meta, "cpw.mods:modlauncher")
                || hasLibraryPrefix(meta, "net.minecraftforge:forge")
                || hasLibraryPrefix(meta, "net.minecraftforge:fmlloader")
                || hasLibraryPrefix(meta, "net.minecraftforge:fmlcore")) {
            return "cpw.mods.modlauncher.Launcher";
        }
        if (hasLibraryPrefix(meta, "net.minecraft:launchwrapper")
                || hasLibraryPrefix(meta, "net.minecraft.launchwrapper:launchwrapper")) {
            return "net.minecraft.launchwrapper.Launch";
        }
        if (jarContains(gameJar, "net/fabricmc/loader/impl/launch/knot/KnotClient.class")) {
            return "net.fabricmc.loader.impl.launch.knot.KnotClient";
        }
        if (jarContains(gameJar, "org/quiltmc/loader/impl/launch/knot/KnotClient.class")) {
            return "org.quiltmc.loader.impl.launch.knot.KnotClient";
        }
        if (jarContains(gameJar, "cpw/mods/modlauncher/Launcher.class")) {
            return "cpw.mods.modlauncher.Launcher";
        }
        if (jarContains(gameJar, "net/minecraftforge/fml/loading/Launcher.class")) {
            return "net.minecraftforge.fml.loading.Launcher";
        }
        if (jarContains(gameJar, "net/minecraft/client/main/Main.class")) {
            return "net.minecraft.client.main.Main";
        }
        if (jarContains(gameJar, "net/minecraft/client/Minecraft.class")) {
            return "net.minecraft.client.Minecraft";
        }
        if (jarContains(gameJar, "net/minecraft/client/MinecraftApplet.class")) {
            return "net.minecraft.client.MinecraftApplet";
        }
        return "";
    }

    private boolean isLegacyClientMeta(JsonObject meta) {
        if (meta == null) {
            return false;
        }
        String phase = readString(meta, "phase").toLowerCase();
        if (phase.equals("indev") || phase.equals("infdev") || phase.equals("alpha") || phase.equals("beta") || phase.equals("classic")) {
            return true;
        }
        int clientJsonVersion = readInt(meta, "clientJsonVersion", -1);
        if (clientJsonVersion > 0 && clientJsonVersion <= 1) {
            return true;
        }
        int compliance = readInt(meta, "complianceLevel", -1);
        return compliance == 0;
    }

    private boolean hasLibraryPrefix(JsonObject meta, String prefix) {
        if (meta == null || prefix == null || prefix.isBlank()) {
            return false;
        }
        JsonArray libs = meta.has("libraries") && meta.get("libraries").isJsonArray()
                ? meta.getAsJsonArray("libraries")
                : new JsonArray();
        for (JsonElement el : libs) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject lib = el.getAsJsonObject();
            String name = readString(lib, "name");
            if (!name.isBlank() && name.toLowerCase().startsWith(prefix.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean metaHasArgumentToken(JsonObject meta, String token) {
        if (meta == null || token == null || token.isBlank()) {
            return false;
        }
        String legacy = readString(meta, "minecraftArguments");
        if (!legacy.isBlank() && legacy.contains(token)) {
            return true;
        }
        if (!meta.has("arguments") || !meta.get("arguments").isJsonObject()) {
            return false;
        }
        JsonObject args = meta.getAsJsonObject("arguments");
        return arrayHasToken(args, "jvm", token) || arrayHasToken(args, "game", token);
    }

    private boolean arrayHasToken(JsonObject args, String key, String token) {
        if (args == null || !args.has(key) || !args.get(key).isJsonArray()) {
            return false;
        }
        JsonArray arr = args.getAsJsonArray(key);
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) {
                if (el.getAsString().contains(token)) {
                    return true;
                }
                continue;
            }
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!obj.has("value")) {
                continue;
            }
            JsonElement value = obj.get("value");
            if (value.isJsonPrimitive()) {
                if (value.getAsString().contains(token)) {
                    return true;
                }
            } else if (value.isJsonArray()) {
                for (JsonElement v : value.getAsJsonArray()) {
                    if (v.isJsonPrimitive() && v.getAsString().contains(token)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String readJarManifestMainClass(Path jarPath) {
        if (!Files.exists(jarPath)) {
            return "";
        }
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return "";
            }
            String mainClass = manifest.getMainAttributes().getValue("Main-Class");
            return mainClass == null ? "" : mainClass.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String replace(String input, Map<String, String> vars) {
        String out = input;
        for (var e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    private String buildVersionType(JsonObject meta, boolean includeLimecraftSuffix, boolean hideCustomSuffix) {
        String type = readString(meta, "type");
        if (type.isBlank()) {
            type = "custom";
        }
        if (hideCustomSuffix && "custom".equalsIgnoreCase(type)) {
            type = "release";
        }
        if (!includeLimecraftSuffix) {
            return type;
        }
        if ("release".equalsIgnoreCase(type)) {
            return "Limecraft";
        }
        return type + " (Limecraft)";
    }

    private String resolveAssetsIndexName(JsonObject meta) {
        String assets = readString(meta, "assets");
        if (!assets.isBlank()) {
            return assets;
        }
        if (meta.has("assetIndex") && meta.get("assetIndex").isJsonObject()) {
            String id = readString(meta.getAsJsonObject("assetIndex"), "id");
            if (!id.isBlank()) {
                return id;
            }
        }
        return "legacy";
    }

    private String safeFolderName(String versionId) {
        return versionId.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private int readJavaMajorVersion(String javaExecutable) {
        try {
            Process process = new ProcessBuilder(javaExecutable, "-version")
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            }
            process.waitFor(5, TimeUnit.SECONDS);
            String versionText = output.toString(StandardCharsets.UTF_8);
            Matcher matcher = JAVA_VERSION_QUOTED.matcher(versionText);
            if (matcher.find()) {
                return parseJavaMajor(matcher.group(1));
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private int parseJavaMajor(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        String trimmed = version.trim();
        if (trimmed.startsWith("1.")) {
            int dot = trimmed.indexOf('.', 2);
            String major = dot > 2 ? trimmed.substring(2, dot) : trimmed.substring(2);
            return parseIntSafe(major);
        }
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        return parseIntSafe(trimmed.substring(0, end));
    }

    private int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String readString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key) == null || obj.get(key).isJsonNull()) {
            return "";
        }
        JsonElement value = obj.get(key);
        if (!value.isJsonPrimitive()) {
            return "";
        }
        try {
            return value.getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private int readInt(JsonObject obj, String key, int fallback) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key) == null || obj.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement value = obj.get(key);
        if (!value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String detectOs() {
        String name = System.getProperty("os.name").toLowerCase();
        if (name.contains("win")) return "windows";
        if (name.contains("mac")) return "osx";
        return "linux";
    }
}
