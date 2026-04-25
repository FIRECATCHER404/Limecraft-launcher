package com.limecraft.launcher.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.limecraft.launcher.core.AppVersion;
import com.limecraft.launcher.core.Downloader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ModrinthService {
    private final OkHttpClient client;
    private final Downloader downloader;

    public ModrinthService(OkHttpClient client) {
        this.client = client;
        this.downloader = new Downloader(client);
    }

    public List<Project> searchProjects(String query, String loader, String gameVersion, String side) throws IOException {
        String normalizedQuery = query == null ? "" : query.trim();
        String normalizedLoader = loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
        String normalizedGameVersion = gameVersion == null ? "" : gameVersion.trim();
        String normalizedSide = side == null ? "client" : side.trim().toLowerCase(Locale.ROOT);

        StringBuilder facets = new StringBuilder("[");
        facets.append("[\"project_type:mod\"]");
        if (!normalizedLoader.isBlank() && !"vanilla".equals(normalizedLoader)) {
            facets.append(",[\"categories:").append(normalizedLoader).append("\"]");
        }
        if (!normalizedGameVersion.isBlank()) {
            facets.append(",[\"versions:").append(normalizedGameVersion).append("\"]");
        }
        facets.append(']');

        String url = "https://api.modrinth.com/v2/search?limit=40&index=relevance&query="
                + URLEncoder.encode(normalizedQuery, StandardCharsets.UTF_8)
                + "&facets=" + URLEncoder.encode(facets.toString(), StandardCharsets.UTF_8);
        JsonObject root = getJson(url);
        JsonArray hits = root.has("hits") && root.get("hits").isJsonArray() ? root.getAsJsonArray("hits") : new JsonArray();

        List<Project> out = new ArrayList<>();
        for (JsonElement hit : hits) {
            if (!hit.isJsonObject()) {
                continue;
            }
            JsonObject o = hit.getAsJsonObject();
            Project project = new Project(
                    readString(o, "project_id"),
                    readString(o, "slug"),
                    readString(o, "title"),
                    readString(o, "author"),
                    readString(o, "description"),
                    readString(o, "client_side"),
                    readString(o, "server_side"),
                    readStringArray(o, "display_categories", "categories"),
                    readLong(o, "downloads"),
                    readLong(o, "follows", "followers"),
                    readString(o, "icon_url")
            );
            if (matchesSide(project, normalizedSide)) {
                out.add(project);
            }
        }
        return out;
    }

    public ProjectDetails getProjectDetails(String projectId) throws IOException {
        JsonObject object = getJson("https://api.modrinth.com/v2/project/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8));
        return new ProjectDetails(
                readString(object, "id"),
                readString(object, "slug"),
                readString(object, "title"),
                readString(object, "author"),
                readString(object, "description"),
                readString(object, "body"),
                readString(object, "client_side"),
                readString(object, "server_side"),
                readStringArray(object, "display_categories", "categories"),
                readLong(object, "downloads"),
                readLong(object, "follows", "followers"),
                readString(object, "icon_url")
        );
    }

    public List<Version> listVersions(String projectId, String loader) throws IOException {
        return listVersions(projectId, loader, "");
    }

    public List<Version> listVersions(String projectId, String loader, String gameVersion) throws IOException {
        StringBuilder url = new StringBuilder("https://api.modrinth.com/v2/project/")
                .append(URLEncoder.encode(projectId, StandardCharsets.UTF_8))
                .append("/version?");
        boolean wroteParam = false;
        if (loader != null && !loader.isBlank() && !"vanilla".equalsIgnoreCase(loader)) {
            url.append("loaders=").append(URLEncoder.encode("[\"" + loader.toLowerCase(Locale.ROOT) + "\"]", StandardCharsets.UTF_8));
            wroteParam = true;
        }
        if (gameVersion != null && !gameVersion.isBlank()) {
            if (wroteParam) {
                url.append('&');
            }
            url.append("game_versions=").append(URLEncoder.encode("[\"" + gameVersion + "\"]", StandardCharsets.UTF_8));
        }

        JsonArray versions = getJsonArray(url.toString());
        List<Version> out = new ArrayList<>();
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) {
                continue;
            }
            out.add(parseVersion(element.getAsJsonObject()));
        }
        return out;
    }

    public Version resolveLatestVersion(String projectId, String loader, String gameVersion) throws IOException {
        for (Version version : listVersions(projectId, loader, gameVersion)) {
            if (!version.files().isEmpty()) {
                return version;
            }
        }
        return null;
    }

    public Path downloadPrimaryFile(Version version, Path modsDir) throws IOException {
        if (version == null || version.files().isEmpty()) {
            throw new IOException("No downloadable Modrinth file was resolved.");
        }
        FileRef file = version.files().stream().filter(FileRef::primary).findFirst().orElse(version.files().get(0));
        String filename = safeFilename(file.filename());
        if (filename.isBlank()) {
            throw new IOException("The selected Modrinth file has no valid filename.");
        }
        Path root = modsDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Refusing to download outside the mods folder: " + file.filename());
        }
        downloader.downloadTo(file.url(), target);
        return target;
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String onlyName = Path.of(filename.replace('\\', '/')).getFileName().toString();
        return onlyName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private Version parseVersion(JsonObject object) {
        List<FileRef> files = new ArrayList<>();
        if (object.has("files") && object.get("files").isJsonArray()) {
            for (JsonElement element : object.getAsJsonArray("files")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject file = element.getAsJsonObject();
                files.add(new FileRef(
                        readString(file, "filename"),
                        readString(file, "url"),
                        file.has("primary") && file.get("primary").getAsBoolean()
                ));
            }
        }
        int dependencyCount = 0;
        if (object.has("dependencies") && object.get("dependencies").isJsonArray()) {
            dependencyCount = object.getAsJsonArray("dependencies").size();
        }
        return new Version(
                readString(object, "id"),
                readString(object, "name"),
                readString(object, "version_number"),
                readString(object, "version_type"),
                readStringArray(object, "game_versions"),
                readStringArray(object, "loaders"),
                readString(object, "date_published"),
                readString(object, "changelog"),
                dependencyCount,
                files
        );
    }

    private boolean matchesSide(Project project, String side) {
        if ("server".equals(side)) {
            return !"unsupported".equalsIgnoreCase(project.serverSide());
        }
        return !"unsupported".equalsIgnoreCase(project.clientSide());
    }

    private JsonObject getJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", AppVersion.userAgent())
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Modrinth HTTP " + response.code() + " for " + url);
            }
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        }
    }

    private JsonArray getJsonArray(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", AppVersion.userAgent())
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Modrinth HTTP " + response.code() + " for " + url);
            }
            return JsonParser.parseString(response.body().string()).getAsJsonArray();
        }
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

    private List<String> readStringArray(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object == null || key == null || !object.has(key) || !object.get(key).isJsonArray()) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (JsonElement element : object.getAsJsonArray(key)) {
                if (element == null || element.isJsonNull()) {
                    continue;
                }
                try {
                    String value = element.getAsString();
                    if (value != null && !value.isBlank()) {
                        values.add(value.trim());
                    }
                } catch (Exception ignored) {
                    // Ignore malformed entries and keep parsing the rest.
                }
            }
            if (!values.isEmpty()) {
                return values;
            }
        }
        return List.of();
    }

    private long readLong(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return 0L;
        }
        for (String key : keys) {
            if (key == null || !object.has(key) || object.get(key).isJsonNull()) {
                continue;
            }
            try {
                return object.get(key).getAsLong();
            } catch (Exception ignored) {
                // Ignore malformed numeric fields and keep searching fallbacks.
            }
        }
        return 0L;
    }

    public record Project(
            String id,
            String slug,
            String title,
            String author,
            String description,
            String clientSide,
            String serverSide,
            List<String> categories,
            long downloads,
            long follows,
            String iconUrl
    ) {
        @Override
        public String toString() {
            String byline = author == null || author.isBlank() ? "" : " by " + author;
            return title + byline;
        }
    }

    public record ProjectDetails(
            String id,
            String slug,
            String title,
            String author,
            String description,
            String body,
            String clientSide,
            String serverSide,
            List<String> categories,
            long downloads,
            long follows,
            String iconUrl
    ) {}

    public record Version(
            String id,
            String name,
            String versionNumber,
            String versionType,
            List<String> gameVersions,
            List<String> loaders,
            String publishedAt,
            String changelog,
            int dependencyCount,
            List<FileRef> files
    ) {}

    public record FileRef(String filename, String url, boolean primary) {}
}
