package com.limecraft.launcher.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class CrashReportAnalyzer {
    public String diagnose(Path instanceDir) throws IOException {
        if (instanceDir == null || !Files.isDirectory(instanceDir)) {
            return "No instance directory exists yet for this profile.";
        }

        Path latestCrash = latestFile(instanceDir.resolve("crash-reports"));
        Path latestLog = instanceDir.resolve("logs").resolve("latest.log");
        String crashText = readSafe(latestCrash);
        String logText = readSafe(latestLog);
        String haystack = (crashText + "\n" + logText).toLowerCase(Locale.ROOT);

        if (haystack.isBlank()) {
            return "No crash report or latest.log was found for this profile.";
        }

        List<String> findings = new java.util.ArrayList<>();
        if (haystack.contains("unsupportedclassversionerror") || haystack.contains("has been compiled by a more recent version of the java runtime")) {
            findings.add("Likely wrong Java version for this profile.");
        }
        if (haystack.contains("could not reserve enough space") || haystack.contains("could not create the java virtual machine")) {
            findings.add("The configured RAM may be too high for the selected Java runtime, or the runtime may be 32-bit.");
        }
        if (haystack.contains("noclassdeffounderror") || haystack.contains("classnotfoundexception") || haystack.contains("nosuchmethoderror")) {
            findings.add("This usually points to incompatible mods, libraries, or the wrong loader/game-version combination.");
        }
        if (haystack.contains("mixin apply failed") || haystack.contains("mixin transformer")) {
            findings.add("A mixin failed to apply, which usually means conflicting or outdated mods.");
        }
        if (haystack.contains("glfw error") || haystack.contains("opengl") || haystack.contains("pixel format")) {
            findings.add("This looks like a graphics or driver initialization problem.");
        }
        if (haystack.contains("fabric") && haystack.contains("depends on")) {
            findings.add("A Fabric mod dependency appears to be missing.");
        }
        if (haystack.contains("forge") && haystack.contains("modloadingerror")) {
            findings.add("Forge reported a mod loading error, usually caused by version mismatch or missing dependencies.");
        }

        if (findings.isEmpty()) {
            findings.add("No simple signature matched. Open the latest log and crash report for the full stack trace.");
        }

        StringBuilder out = new StringBuilder();
        out.append("Latest crash report: ").append(latestCrash == null ? "none" : latestCrash).append('\n');
        out.append("Latest log: ").append(Files.exists(latestLog) ? latestLog : "none").append('\n');
        out.append('\n');
        for (String finding : findings) {
            out.append("- ").append(finding).append('\n');
        }
        return out.toString().trim();
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
}
