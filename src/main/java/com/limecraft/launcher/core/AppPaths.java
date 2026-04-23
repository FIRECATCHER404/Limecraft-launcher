package com.limecraft.launcher.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AppPaths {
    private final Path appRoot;
    private final Path dataDir;
    private final Path executablePath;
    private final boolean portableData;
    private final boolean packagedApp;

    private AppPaths(Path appRoot, Path dataDir, Path executablePath, boolean portableData, boolean packagedApp) {
        this.appRoot = appRoot;
        this.dataDir = dataDir;
        this.executablePath = executablePath;
        this.portableData = portableData;
        this.packagedApp = packagedApp;
    }

    public static AppPaths detect() {
        Path packagedExecutable = detectPackagedExecutable();
        boolean packaged = packagedExecutable != null && Files.exists(packagedExecutable);
        Path root = packaged
                ? packagedExecutable.getParent().toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();

        Path portableDir = packaged ? siblingPortableDir(root) : root.resolve("data");
        Path legacyDir = Path.of(System.getProperty("user.home"), ".limecraft").toAbsolutePath().normalize();
        boolean portable = canUsePortable(portableDir);
        Path chosenDataDir = portable ? portableDir : legacyDir;

        return new AppPaths(root, chosenDataDir, packagedExecutable, portable, packaged);
    }

    public Path appRoot() {
        return appRoot;
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path legacyDataDir() {
        return Path.of(System.getProperty("user.home"), ".limecraft").toAbsolutePath().normalize();
    }

    public Path executablePath() {
        return executablePath;
    }

    public boolean portableData() {
        return portableData;
    }

    public boolean packagedApp() {
        return packagedApp;
    }

    public boolean canSelfUpdate() {
        return packagedApp && executablePath != null && Files.exists(executablePath);
    }

    public Path siblingPath(String suffix) {
        String baseName = appRoot.getFileName() == null ? AppVersion.APP_NAME : appRoot.getFileName().toString();
        Path parent = appRoot.getParent();
        if (parent == null) {
            return Path.of(baseName + suffix).toAbsolutePath().normalize();
        }
        return parent.resolve(baseName + suffix).toAbsolutePath().normalize();
    }

    public String storageModeLabel() {
        return portableData ? "portable" : "home";
    }

    private static Path detectPackagedExecutable() {
        String jpackageAppPath = System.getProperty("jpackage.app-path", "").trim();
        if (!jpackageAppPath.isBlank()) {
            try {
                return Path.of(jpackageAppPath).toAbsolutePath().normalize();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean canUsePortable(Path portableDir) {
        try {
            Files.createDirectories(portableDir);
            Path probe = portableDir.resolve(".limecraft-write-test");
            Files.writeString(probe, "ok", StandardCharsets.UTF_8);
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Path siblingPortableDir(Path root) {
        String baseName = root.getFileName() == null ? AppVersion.APP_NAME : root.getFileName().toString();
        Path parent = root.getParent();
        if (parent == null) {
            return Path.of(baseName + "-data").toAbsolutePath().normalize();
        }
        return parent.resolve(baseName + "-data").toAbsolutePath().normalize();
    }
}
