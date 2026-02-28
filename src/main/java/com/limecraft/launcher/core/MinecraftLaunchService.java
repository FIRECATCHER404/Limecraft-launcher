package com.limecraft.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.limecraft.launcher.auth.MinecraftAccount;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

public final class MinecraftLaunchService {
    private final Path gameDir;

    public MinecraftLaunchService(Path gameDir) {
        this.gameDir = gameDir;
    }

    public Process launch(String versionId, JsonObject versionMeta, MinecraftAccount account, String offlineUsername, String javaPath, String xmx, boolean includeLimecraftSuffix) throws Exception {
        String os = detectOs();
        JsonObject effectiveMeta = resolveEffectiveMeta(versionId, versionMeta, new HashSet<>());
        Path gameJar = resolveGameJar(versionId, versionMeta, new HashSet<>());
        if (!Files.exists(gameJar)) {
            throw new IllegalStateException("Missing client jar for " + versionId + " (or its parent version)");
        }

        Path instanceDir = gameDir.resolve("instances").resolve(safeFolderName(versionId));
        Files.createDirectories(instanceDir);

        List<String> classpathEntries = new ArrayList<>();
        JsonArray libs = effectiveMeta.has("libraries") && effectiveMeta.get("libraries").isJsonArray()
                ? effectiveMeta.getAsJsonArray("libraries")
                : new JsonArray();

        for (JsonElement libEl : libs) {
            JsonObject lib = libEl.getAsJsonObject();
            if (!isLibraryAllowed(lib, os)) {
                continue;
            }
            Path p = resolveLibraryPath(lib);
            if (p != null && Files.exists(p)) {
                classpathEntries.add(p.toString());
            }
        }
        classpathEntries.add(gameJar.toString());

        Path nativesDir = gameDir.resolve("natives").resolve(versionId);
        Files.createDirectories(nativesDir);
        extractNatives(effectiveMeta, nativesDir, os);

        Map<String, String> vars = buildVariables(effectiveMeta, account, offlineUsername, classpathEntries, nativesDir, instanceDir, versionId, includeLimecraftSuffix);
        List<String> args = new ArrayList<>();
        args.add(javaPath == null || javaPath.isBlank() ? "java" : javaPath);
        args.add("-Xmx" + (xmx == null || xmx.isBlank() ? "4G" : xmx));
        args.add("-Djava.library.path=" + nativesDir);
        String mainClass = effectiveMeta.get("mainClass").getAsString();
        if (shouldUseLegacyDirectMain(effectiveMeta, gameJar)) {
            mainClass = "net.minecraft.client.Minecraft";
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

        if (effectiveMeta.has("arguments")) {
            JsonObject arguments = effectiveMeta.getAsJsonObject("arguments");
            if (arguments.has("game") && arguments.get("game").isJsonArray()) {
                addArgArray(arguments.getAsJsonArray("game"), args, vars, os);
            }
        } else if (effectiveMeta.has("minecraftArguments")) {
            String raw = effectiveMeta.get("minecraftArguments").getAsString();
            for (String tok : raw.split(" ")) {
                args.add(replace(tok, vars));
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

        String parentId = child.get("inheritsFrom").getAsString();
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
            if ("inheritsFrom".equals(key) || "libraries".equals(key)) {
                continue;
            }
            if ("arguments".equals(key) && entry.getValue().isJsonObject()) {
                JsonObject parentArgs = merged.has("arguments") && merged.get("arguments").isJsonObject()
                        ? merged.getAsJsonObject("arguments")
                        : null;
                JsonObject childArgs = entry.getValue().getAsJsonObject();
                merged.add("arguments", mergeArguments(parentArgs, childArgs));
                continue;
            }
            merged.add(key, entry.getValue());
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
            if ("jvm".equals(key) || "game".equals(key)) {
                continue;
            }
            out.add(key, entry.getValue());
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
        if (Files.exists(candidate) || !meta.has("inheritsFrom")) {
            visiting.remove(versionId);
            return candidate;
        }

        String parentId = meta.get("inheritsFrom").getAsString();
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

    private Map<String, String> buildVariables(JsonObject meta, MinecraftAccount account, String offlineUsername, List<String> cp, Path nativesDir, Path instanceDir, String versionId, boolean includeLimecraftSuffix) {
        String username = account != null
                ? account.username()
                : (offlineUsername == null || offlineUsername.isBlank() ? "Player" : offlineUsername);
        String uuid = account != null ? account.uuid() : UUID.randomUUID().toString().replace("-", "");
        String token = account != null ? account.accessToken() : "0";
        String xuid = account != null ? account.xuid() : "0";
        String clientId = UUID.randomUUID().toString();

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", username);
        vars.put("version_name", versionId);
        vars.put("game_directory", instanceDir.toAbsolutePath().toString());
        vars.put("assets_root", gameDir.resolve("assets").toAbsolutePath().toString());
        vars.put("game_assets", gameDir.resolve("assets").toAbsolutePath().toString());
        vars.put("assets_index_name", meta.get("assets").getAsString());
        vars.put("auth_uuid", uuid);
        vars.put("auth_access_token", token);
        vars.put("auth_session", token);
        vars.put("auth_xuid", xuid);
        vars.put("clientid", clientId);
        vars.put("user_type", account != null ? "msa" : "legacy");
        vars.put("version_type", buildVersionType(meta, includeLimecraftSuffix));
        vars.put("natives_directory", nativesDir.toAbsolutePath().toString());
        vars.put("launcher_name", "Limecraft");
        vars.put("launcher_version", "1.0.0");
        vars.put("classpath", String.join(System.getProperty("path.separator"), cp));
        vars.put("resolution_width", "1280");
        vars.put("resolution_height", "720");
        return vars;
    }

    private boolean shouldUseLegacyDirectMain(JsonObject meta, Path gameJar) {
        String type = meta.has("type") ? meta.get("type").getAsString() : "";
        String mainClass = meta.has("mainClass") ? meta.get("mainClass").getAsString() : "";
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

    private String replace(String input, Map<String, String> vars) {
        String out = input;
        for (var e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    private String buildVersionType(JsonObject meta, boolean includeLimecraftSuffix) {
        String type = meta.has("type") ? meta.get("type").getAsString() : "custom";
        if (!includeLimecraftSuffix) {
            return type;
        }
        if ("release".equalsIgnoreCase(type)) {
            return "Limecraft";
        }
        return type + " (Limecraft)";
    }

    private String safeFolderName(String versionId) {
        return versionId.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String detectOs() {
        String name = System.getProperty("os.name").toLowerCase();
        if (name.contains("win")) return "windows";
        if (name.contains("mac")) return "osx";
        return "linux";
    }
}
