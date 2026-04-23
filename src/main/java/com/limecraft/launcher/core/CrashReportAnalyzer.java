package com.limecraft.launcher.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public final class CrashReportAnalyzer {
    public DiagnosisReport analyze(Path instanceDir) throws IOException {
        if (instanceDir == null || !Files.isDirectory(instanceDir)) {
            return new DiagnosisReport(
                    null,
                    null,
                    "No instance directory exists yet for this profile.",
                    List.of("This profile has not created any local game data yet."),
                    List.of("Launch the profile once, then retry diagnostics after the game writes logs.")
            );
        }

        Path latestCrash = latestFile(instanceDir.resolve("crash-reports"));
        Path latestLog = instanceDir.resolve("logs").resolve("latest.log");
        Path existingLog = Files.exists(latestLog) ? latestLog : null;
        String crashText = readSafe(latestCrash);
        String logText = readSafe(existingLog);
        String haystack = (crashText + "\n" + logText).toLowerCase(Locale.ROOT);

        if (haystack.isBlank()) {
            return new DiagnosisReport(
                    latestCrash,
                    existingLog,
                    "No crash report or latest.log was found for this profile.",
                    List.of("There is no recent crash evidence on disk yet."),
                    List.of("Open the profile once, reproduce the failure, then run diagnostics again.")
            );
        }

        Set<String> likelyCauses = new LinkedHashSet<>();
        Set<String> suggestedFixes = new LinkedHashSet<>();

        if (containsAny(haystack, "unsupportedclassversionerror", "has been compiled by a more recent version of the java runtime")) {
            likelyCauses.add("The selected Java runtime does not match what this Minecraft version or mod set requires.");
            suggestedFixes.add("Pick the recommended Java version for this profile, then relaunch.");
        }
        if (containsAny(haystack, "could not reserve enough space", "could not create the java virtual machine")) {
            likelyCauses.add("The configured RAM is too high for the selected runtime, or the runtime is 32-bit.");
            suggestedFixes.add("Lower the Xmx setting and use a 64-bit Java runtime.");
        }
        if (containsAny(haystack, "fabric loader", "requires fabric", "fabric mod")) {
            likelyCauses.add("At least one mod expects Fabric or a Fabric-compatible environment.");
            suggestedFixes.add("Use a Fabric-compatible profile, or remove Fabric-only mods from the shared mods folder.");
        }
        if (containsAny(haystack, "forge", "modloadingerror", "neoforge", "mods.toml")) {
            likelyCauses.add("Forge or NeoForge reported an invalid or incompatible mod load.");
            suggestedFixes.add("Verify the loader family and Minecraft version match the mods in the shared folder.");
        }
        if (containsAny(haystack, "depends on", "missing mandatory dependency", "requires mod")) {
            likelyCauses.add("A required mod dependency is missing.");
            suggestedFixes.add("Install the missing dependency or remove the mod that requires it.");
        }
        if (containsAny(haystack, "not present on dist dedicated_server", "cannot load class net.minecraft.client", "client-only")) {
            likelyCauses.add("A client-only mod was loaded on a server environment.");
            suggestedFixes.add("Remove client-only mods before launching the server.");
        }
        if (containsAny(haystack, "not present on dist client", "dedicatedserver", "server-only")) {
            likelyCauses.add("A server-only mod or server-targeted code was loaded on a client environment.");
            suggestedFixes.add("Remove server-only mods before launching the client.");
        }
        if (containsAny(haystack, "mixin apply failed", "mixin transformer")) {
            likelyCauses.add("A mixin failed to apply because of an outdated or conflicting mod.");
            suggestedFixes.add("Update or remove conflicting mods and retry.");
        }
        if (containsAny(haystack, "noclassdeffounderror", "classnotfoundexception", "nosuchmethoderror")) {
            likelyCauses.add("A mod or library does not match the current loader or Minecraft version.");
            suggestedFixes.add("Use mod builds that match the selected loader and Minecraft version exactly.");
        }
        if (containsAny(haystack, "requires minecraft", "incompatible with minecraft", "older version", "newer version")) {
            likelyCauses.add("One or more mods target a different Minecraft version.");
            suggestedFixes.add("Use mods built for the same Minecraft version as this profile.");
        }
        if (containsAny(haystack, "zip end header not found", "unexpected end of zlib input stream", "checksum", "corrupt", "invalid or corrupt jarfile")) {
            likelyCauses.add("A download or jar file looks incomplete or corrupted.");
            suggestedFixes.add("Run Repair Files and replace any damaged jars in the shared folders.");
        }
        if (containsAny(haystack, "glfw error", "opengl", "pixel format")) {
            likelyCauses.add("Graphics initialization failed.");
            suggestedFixes.add("Update graphics drivers and retry with the correct GPU selected.");
        }

        if (likelyCauses.isEmpty()) {
            likelyCauses.add("No simple crash signature matched this failure.");
            suggestedFixes.add("Open the latest log and crash report for the full stack trace.");
        }

        String summary = likelyCauses.iterator().next();
        return new DiagnosisReport(
                latestCrash,
                existingLog,
                summary,
                new ArrayList<>(likelyCauses),
                new ArrayList<>(suggestedFixes)
        );
    }

    public String diagnose(Path instanceDir) throws IOException {
        return analyze(instanceDir).formatForClipboard();
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Path latestFile(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(this::lastModified))
                    .orElse(null);
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private String readSafe(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return "";
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public record DiagnosisReport(
            Path latestCrashReport,
            Path latestLog,
            String summary,
            List<String> likelyCauses,
            List<String> suggestedFixes
    ) {
        public DiagnosisReport {
            likelyCauses = likelyCauses == null ? List.of() : List.copyOf(likelyCauses);
            suggestedFixes = suggestedFixes == null ? List.of() : List.copyOf(suggestedFixes);
            summary = summary == null ? "" : summary.trim();
        }

        public boolean hasEvidence() {
            return latestCrashReport != null || latestLog != null;
        }

        public String primaryCause() {
            return likelyCauses.isEmpty() ? "" : likelyCauses.get(0);
        }

        public String primaryFix() {
            return suggestedFixes.isEmpty() ? "" : suggestedFixes.get(0);
        }

        public String formatForClipboard() {
            StringBuilder out = new StringBuilder();
            out.append("Summary: ").append(summary.isBlank() ? "No summary available." : summary).append('\n');
            out.append("Latest crash report: ").append(latestCrashReport == null ? "none" : latestCrashReport).append('\n');
            out.append("Latest log: ").append(latestLog == null ? "none" : latestLog).append('\n');
            out.append('\n');
            if (!likelyCauses.isEmpty()) {
                out.append("Likely Causes:").append('\n');
                for (String cause : likelyCauses) {
                    out.append("- ").append(cause).append('\n');
                }
                out.append('\n');
            }
            if (!suggestedFixes.isEmpty()) {
                out.append("Suggested Fixes:").append('\n');
                for (String fix : suggestedFixes) {
                    out.append("- ").append(fix).append('\n');
                }
            }
            return out.toString().trim();
        }
    }
}
