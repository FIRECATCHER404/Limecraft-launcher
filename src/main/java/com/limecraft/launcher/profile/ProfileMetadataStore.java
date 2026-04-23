package com.limecraft.launcher.profile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProfileMetadataStore {
    private final Path file;

    public ProfileMetadataStore(Path gameDir) {
        this.file = gameDir.resolve("profiles.json");
    }

    public synchronized ProfileMetadata get(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return ProfileMetadata.defaults("");
        }
        return loadAll().getOrDefault(versionId, ProfileMetadata.defaults(versionId));
    }

    public synchronized void save(ProfileMetadata metadata) throws IOException {
        Map<String, ProfileMetadata> all = loadAll();
        all.put(metadata.versionId(), metadata);
        store(all);
    }

    public synchronized void recordPlaySession(String versionId, long sessionSeconds) throws IOException {
        if (versionId == null || versionId.isBlank() || sessionSeconds <= 0) {
            return;
        }
        ProfileMetadata current = get(versionId);
        save(new ProfileMetadata(
                versionId,
                current.favorite(),
                current.notes(),
                current.tags(),
                current.groupName(),
                current.iconPath(),
                current.playTimeSeconds() + sessionSeconds,
                Instant.now().toString()
        ));
    }

    public synchronized Map<String, ProfileMetadata> loadAll() {
        Map<String, ProfileMetadata> out = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject o = entry.getValue().getAsJsonObject();
                String versionId = entry.getKey();
                out.put(versionId, new ProfileMetadata(
                        versionId,
                        readBoolean(o, "favorite"),
                        readString(o, "notes"),
                        readString(o, "tags"),
                        readString(o, "groupName"),
                        readString(o, "iconPath"),
                        readLong(o, "playTimeSeconds"),
                        readString(o, "lastPlayedAt")
                ));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void store(Map<String, ProfileMetadata> all) throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        for (ProfileMetadata metadata : all.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("favorite", metadata.favorite());
            o.addProperty("notes", metadata.notes());
            o.addProperty("tags", metadata.tags());
            o.addProperty("groupName", metadata.groupName());
            o.addProperty("iconPath", metadata.iconPath());
            o.addProperty("playTimeSeconds", metadata.playTimeSeconds());
            o.addProperty("lastPlayedAt", metadata.lastPlayedAt());
            root.add(metadata.versionId(), o);
        }
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
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

    private boolean readBoolean(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return false;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private long readLong(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
