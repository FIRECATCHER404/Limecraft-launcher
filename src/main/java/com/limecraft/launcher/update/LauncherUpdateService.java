package com.limecraft.launcher.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.limecraft.launcher.core.AppPaths;
import com.limecraft.launcher.core.AppVersion;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LauncherUpdateService {
    private static final String LATEST_RELEASE_URL = "https://api.github.com/repos/" + AppVersion.GITHUB_REPO + "/releases/latest";

    private final OkHttpClient client;

    public LauncherUpdateService(OkHttpClient client) {
        this.client = client;
    }

    public ReleaseInfo fetchLatestRelease() throws IOException {
        Request request = new Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", AppVersion.userAgent())
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("GitHub update check failed with HTTP " + response.code() + ".");
            }
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            ReleaseAsset asset = pickReleaseAsset(root.has("assets") && root.get("assets").isJsonArray()
                    ? root.getAsJsonArray("assets")
                    : new JsonArray());
            String tagName = readString(root, "tag_name");
            return new ReleaseInfo(
                    tagName,
                    normalizeVersion(tagName),
                    readString(root, "name"),
                    readString(root, "html_url"),
                    readString(root, "body"),
                    asset
            );
        }
    }

    public boolean isNewerThanCurrent(ReleaseInfo releaseInfo) {
        if (releaseInfo == null || releaseInfo.version().isBlank()) {
            return false;
        }
        return compareVersions(releaseInfo.version(), AppVersion.CURRENT) > 0;
    }

    public Path writeWindowsUpdaterScript(ReleaseInfo releaseInfo, AppPaths appPaths, long currentPid) throws IOException {
        if (releaseInfo == null) {
            throw new IOException("No update release is available.");
        }
        if (releaseInfo.asset() == null || releaseInfo.asset().downloadUrl().isBlank()) {
            throw new IOException("The latest release does not expose a downloadable Windows zip asset.");
        }
        if (!appPaths.canSelfUpdate()) {
            throw new IOException("Self-update is only available from the packaged Limecraft app.");
        }

        String safeVersion = safeFileName(releaseInfo.version().isBlank() ? "update" : releaseInfo.version());
        Path scriptPath = appPaths.siblingPath("-apply-update-" + safeVersion + "-" + currentPid + ".ps1");
        String executableName = appPaths.executablePath().getFileName() == null
                ? AppVersion.APP_NAME + ".exe"
                : appPaths.executablePath().getFileName().toString();
        String script = """
                $ErrorActionPreference = 'Stop'
                $pidToWait = %d
                $appRoot = '%s'
                $appExe = '%s'
                $stagingRoot = '%s'
                $workRoot = Join-Path $stagingRoot ('update-' + [guid]::NewGuid().ToString('N'))
                $extractRoot = Join-Path $workRoot 'extract'
                $downloadTemp = Join-Path $workRoot ('download-' + [guid]::NewGuid().ToString('N') + '.tmp')
                $downloadZip = Join-Path $workRoot '%s'
                $backupRoot = '%s'
                $assetUrl = '%s'
                $releaseUrl = '%s'
                $userAgent = '%s'

                function Show-UpdateError([string]$message) {
                    try {
                        Add-Type -AssemblyName PresentationFramework -ErrorAction SilentlyContinue
                        [System.Windows.MessageBox]::Show($message, 'Limecraft Update Failed') | Out-Null
                    } catch {
                    }
                }

                function Invoke-WithRetry([scriptblock]$action, [string]$description) {
                    $lastError = $null
                    for ($attempt = 1; $attempt -le 20; $attempt++) {
                        try {
                            & $action
                            return
                        } catch {
                            $lastError = $_
                            Start-Sleep -Milliseconds ([Math]::Min(2500, 150 * $attempt))
                        }
                    }
                    throw ($description + ' failed after retries: ' + $lastError.Exception.Message)
                }

                try {
                    while (Get-Process -Id $pidToWait -ErrorAction SilentlyContinue) {
                        Start-Sleep -Milliseconds 500
                    }
                    Start-Sleep -Milliseconds 750

                    New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
                    Get-ChildItem -LiteralPath $stagingRoot -Directory -ErrorAction SilentlyContinue |
                        Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-3) } |
                        ForEach-Object {
                            try {
                                Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction Stop
                            } catch {
                            }
                        }

                    if (Test-Path $workRoot) {
                        Remove-Item -LiteralPath $workRoot -Recurse -Force -ErrorAction SilentlyContinue
                    }
                    New-Item -ItemType Directory -Path $workRoot | Out-Null

                    Invoke-WithRetry {
                        Invoke-WebRequest -Uri $assetUrl -Headers @{ 'User-Agent' = $userAgent; 'Accept' = 'application/octet-stream' } -OutFile $downloadTemp
                    } 'Download update zip'

                    Invoke-WithRetry {
                        Move-Item -LiteralPath $downloadTemp -Destination $downloadZip -Force
                    } 'Finalize update zip'

                    Invoke-WithRetry {
                        Expand-Archive -LiteralPath $downloadZip -DestinationPath $extractRoot -Force
                    } 'Extract update zip'

                    $packageRoot = Get-ChildItem -LiteralPath $extractRoot -Directory | Select-Object -First 1
                    if ($null -eq $packageRoot) {
                        throw 'The update zip did not contain a packaged app folder.'
                    }
                    if (-not (Test-Path (Join-Path $packageRoot.FullName '%s'))) {
                        throw 'The update zip is missing %s.'
                    }
                    if (-not (Test-Path (Join-Path $packageRoot.FullName 'app'))) {
                        throw 'The update zip is missing the app folder.'
                    }
                    if (-not (Test-Path (Join-Path $packageRoot.FullName 'runtime'))) {
                        throw 'The update zip is missing the runtime folder.'
                    }

                    if (Test-Path $backupRoot) {
                        Invoke-WithRetry {
                            Remove-Item -LiteralPath $backupRoot -Recurse -Force
                        } 'Remove old update backup'
                    }
                    if (Test-Path $appRoot) {
                        Invoke-WithRetry {
                            Move-Item -LiteralPath $appRoot -Destination $backupRoot
                        } 'Move current app to backup'
                    }
                    Invoke-WithRetry {
                        Move-Item -LiteralPath $packageRoot.FullName -Destination $appRoot
                    } 'Move updated app into place'

                    Start-Process -FilePath $appExe
                } catch {
                    if ((-not (Test-Path $appRoot)) -and (Test-Path $backupRoot)) {
                        try {
                            Move-Item -LiteralPath $backupRoot -Destination $appRoot
                        } catch {
                        }
                    }
                    if (Test-Path $appExe) {
                        Start-Process -FilePath $appExe
                    }
                    Show-UpdateError(("Limecraft could not apply the update." + "`n`n" + $_.Exception.Message + "`n`nRelease page:`n" + $releaseUrl))
                }
                """.formatted(
                currentPid,
                escapePowerShellLiteral(appPaths.appRoot().toString()),
                escapePowerShellLiteral(appPaths.executablePath().toString()),
                escapePowerShellLiteral(appPaths.siblingPath("-update-staging").toString()),
                escapePowerShellLiteral(safeFileName(releaseInfo.asset().name())),
                escapePowerShellLiteral(appPaths.siblingPath("-backup-" + safeVersion + "-" + currentPid).toString()),
                escapePowerShellLiteral(releaseInfo.asset().downloadUrl()),
                escapePowerShellLiteral(releaseInfo.htmlUrl().isBlank() ? AppVersion.RELEASES_URL : releaseInfo.htmlUrl()),
                escapePowerShellLiteral(AppVersion.userAgent()),
                escapePowerShellLiteral(executableName),
                escapePowerShellLiteral(executableName)
        );
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8);
        return scriptPath;
    }

    public static int compareVersions(String left, String right) {
        List<String> leftParts = versionParts(left);
        List<String> rightParts = versionParts(right);
        int max = Math.max(leftParts.size(), rightParts.size());
        for (int i = 0; i < max; i++) {
            String a = i < leftParts.size() ? leftParts.get(i) : "0";
            String b = i < rightParts.size() ? rightParts.get(i) : "0";
            int compared = compareVersionPart(a, b);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    private ReleaseAsset pickReleaseAsset(JsonArray assets) {
        ReleaseAsset firstZip = null;
        for (JsonElement element : assets) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject asset = element.getAsJsonObject();
            String name = readString(asset, "name");
            String url = readString(asset, "browser_download_url");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".zip") || url.isBlank()) {
                continue;
            }
            ReleaseAsset candidate = new ReleaseAsset(name, url);
            if (firstZip == null) {
                firstZip = candidate;
            }
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.contains("windows") || normalized.contains("limecraft")) {
                return candidate;
            }
        }
        return firstZip;
    }

    private static List<String> versionParts(String rawVersion) {
        String normalized = normalizeVersion(rawVersion);
        List<String> out = new ArrayList<>();
        for (String part : normalized.split("[^0-9A-Za-z]+")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    private static int compareVersionPart(String left, String right) {
        boolean leftNumeric = left.chars().allMatch(Character::isDigit);
        boolean rightNumeric = right.chars().allMatch(Character::isDigit);
        if (leftNumeric && rightNumeric) {
            return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? 1 : -1;
        }
        return left.compareToIgnoreCase(right);
    }

    private static String normalizeVersion(String rawVersion) {
        String trimmed = rawVersion == null ? "" : rawVersion.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            return trimmed.substring(1);
        }
        return trimmed;
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

    private String escapePowerShellLiteral(String value) {
        return (value == null ? "" : value).replace("'", "''");
    }

    private String safeFileName(String value) {
        String cleaned = (value == null || value.isBlank() ? "update" : value).replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isBlank() ? "update" : cleaned;
    }

    public record ReleaseAsset(String name, String downloadUrl) {
    }

    public record ReleaseInfo(String tagName, String version, String name, String htmlUrl, String body, ReleaseAsset asset) {
    }
}
