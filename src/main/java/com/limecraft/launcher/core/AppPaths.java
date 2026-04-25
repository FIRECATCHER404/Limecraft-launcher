package com.limecraft.launcher.core;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AppPaths {
    private final Path appRoot;
    private final Path dataDir;
    private final Path executablePath;
    private final boolean packagedApp;

    private AppPaths(Path appRoot, Path dataDir, Path executablePath, boolean packagedApp) {
        this.appRoot = appRoot;
        this.dataDir = dataDir;
        this.executablePath = executablePath;
        this.packagedApp = packagedApp;
    }

    public static AppPaths detect() {
        Path packagedExecutable = detectPackagedExecutable();
        boolean packaged = packagedExecutable != null && Files.exists(packagedExecutable);
        Path root = packaged
                ? packagedExecutable.getParent().toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();

        Path homeDataDir = homeDataDir();

        return new AppPaths(root, homeDataDir, packagedExecutable, packaged);
    }

    public Path appRoot() {
        return appRoot;
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path legacyDataDir() {
        return homeDataDir();
    }

    public Path deprecatedPortableDataDir() {
        return packagedApp ? siblingPath("-data") : appRoot.resolve("data").toAbsolutePath().normalize();
    }

    public Path executablePath() {
        return executablePath;
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
        return "home";
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

    private static Path homeDataDir() {
        return Path.of(System.getProperty("user.home"), ".limecraft").toAbsolutePath().normalize();
    }
}
