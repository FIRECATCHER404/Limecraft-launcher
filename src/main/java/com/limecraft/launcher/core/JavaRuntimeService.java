package com.limecraft.launcher.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class JavaRuntimeService {
    private static final Pattern JAVA_VERSION_QUOTED = Pattern.compile("\"([^\"]+)\"");

    public record JavaRuntime(String version, String architecture, String path, String source) {}

    public List<JavaRuntime> detectInstalledJavas() {
        Map<String, JavaRuntime> found = new LinkedHashMap<>();

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            addJavaHome(found, Path.of(javaHome.trim()), "JAVA_HOME");
        }

        addRoots(found, "Program Files Java",
                Path.of("C:/Program Files/Java"),
                Path.of("C:/Program Files/Eclipse Adoptium"),
                Path.of("C:/Program Files/AdoptOpenJDK"),
                Path.of("C:/Program Files/Microsoft"),
                Path.of(System.getProperty("user.home"), ".jdks"));

        Path minecraftRuntime = Path.of(System.getProperty("user.home"), "AppData", "Roaming", ".minecraft", "runtime");
        if (Files.isDirectory(minecraftRuntime)) {
            try (Stream<Path> stream = Files.walk(minecraftRuntime, 3)) {
                stream.filter(Files::isDirectory).forEach(path -> addJavaHome(found, path, "Minecraft Runtime"));
            } catch (Exception ignored) {
            }
        }

        addWhere(found, "javaw.exe");
        addWhere(found, "java.exe");

        return found.values().stream()
                .sorted(Comparator.comparing(JavaRuntime::version, Comparator.nullsLast(String::compareTo)).reversed())
                .toList();
    }

    public int recommendJavaMajor(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return 21;
        }
        String lower = versionId.toLowerCase(Locale.ROOT);
        if (lower.startsWith("24w") || lower.startsWith("25w") || lower.startsWith("26w")) {
            return 21;
        }
        Matcher release = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*").matcher(lower);
        if (release.matches()) {
            int major = parseInt(release.group(1));
            int minor = parseInt(release.group(2));
            int patch = release.group(3) == null ? 0 : parseInt(release.group(3));
            if (major > 1 || minor >= 21 || (minor == 20 && patch >= 5)) {
                return 21;
            }
            if (minor >= 18) {
                return 17;
            }
            if (minor == 17) {
                return 16;
            }
        }
        return 8;
    }

    public int readJavaMajor(String javaExecutable) {
        return parseJavaMajor(readJavaVersion(javaExecutable));
    }

    private void addRoots(Map<String, JavaRuntime> found, String source, Path... roots) {
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(Files::isDirectory).forEach(home -> addJavaHome(found, home, source));
            } catch (Exception ignored) {
            }
        }
    }

    private void addWhere(Map<String, JavaRuntime> found, String executable) {
        try {
            Process process = new ProcessBuilder("where.exe", executable)
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            }
            process.waitFor();
            String text = output.toString(StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                Path executablePath = Path.of(trimmed);
                String normalized = executablePath.toAbsolutePath().normalize().toString();
                if (!found.containsKey(normalized)) {
                    String version = readJavaVersion(executablePath);
                    String arch = inferArchitecture(executablePath.getParent() == null ? executablePath : executablePath.getParent());
                    found.put(normalized, new JavaRuntime(version, arch, normalized, "PATH"));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void addJavaHome(Map<String, JavaRuntime> found, Path javaHome, String source) {
        Path javaw = javaHome.resolve("bin").resolve("javaw.exe");
        Path java = javaHome.resolve("bin").resolve("java.exe");
        Path chosen = Files.exists(javaw) ? javaw : java;
        if (!Files.exists(chosen)) {
            return;
        }
        String normalized = chosen.toAbsolutePath().normalize().toString();
        if (found.containsKey(normalized)) {
            return;
        }
        String version = readJavaVersion(chosen);
        String arch = inferArchitecture(javaHome);
        found.put(normalized, new JavaRuntime(version, arch, normalized, source));
    }

    private String readJavaVersion(Path javaExecutable) {
        return readJavaVersion(javaExecutable == null ? "" : javaExecutable.toString());
    }

    private String readJavaVersion(String javaExecutable) {
        String command = versionProbeCommand(javaExecutable);
        if (command.isBlank()) {
            command = "java";
        }
        try {
            Process process = new ProcessBuilder(command, "-version")
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                in.transferTo(output);
            }
            process.waitFor();
            Matcher matcher = JAVA_VERSION_QUOTED.matcher(output.toString(StandardCharsets.UTF_8));
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception ignored) {
        }
        Path javaPath = Path.of(javaExecutable == null || javaExecutable.isBlank() ? command : javaExecutable);
        if (!Files.exists(javaPath)) {
            return javaExecutable == null ? "" : javaExecutable;
        }
        Path fileName = javaPath.getParent() == null ? javaPath.getFileName() : javaPath.getParent().getParent().getFileName();
        return fileName == null ? command : fileName.toString();
    }

    private String versionProbeCommand(String javaExecutable) {
        String configured = javaExecutable == null || javaExecutable.isBlank() ? "java" : javaExecutable.trim();
        String lower = configured.toLowerCase(Locale.ROOT);
        if ("javaw".equals(lower) || "javaw.exe".equals(lower)) {
            return "java";
        }
        try {
            Path path = Path.of(configured);
            Path fileName = path.getFileName();
            if (fileName != null && fileName.toString().equalsIgnoreCase("javaw.exe")) {
                Path siblingJava = path.resolveSibling("java.exe");
                if (Files.exists(siblingJava)) {
                    return siblingJava.toString();
                }
            }
        } catch (Exception ignored) {
        }
        return configured;
    }

    private int parseJavaMajor(String version) {
        if (version == null || version.isBlank()) {
            return -1;
        }
        String trimmed = version.trim();
        if (trimmed.startsWith("1.")) {
            int dot = trimmed.indexOf('.', 2);
            String major = dot > 2 ? trimmed.substring(2, dot) : trimmed.substring(2);
            return parseInt(major);
        }
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) {
            Matcher matcher = Pattern.compile("(?:jdk|jre|java)[-_ ]?(\\d+)").matcher(trimmed.toLowerCase(Locale.ROOT));
            if (matcher.find()) {
                return parseInt(matcher.group(1));
            }
            return -1;
        }
        return parseInt(trimmed.substring(0, end));
    }

    private String inferArchitecture(Path javaHome) {
        String name = javaHome.toString().toLowerCase(Locale.ROOT);
        if (name.contains("x86") || name.contains("32")) {
            return "x86";
        }
        if (name.contains("arm") || name.contains("aarch64")) {
            return "arm64";
        }
        return "amd64";
    }

    private int parseInt(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return -1;
        }
    }
}
