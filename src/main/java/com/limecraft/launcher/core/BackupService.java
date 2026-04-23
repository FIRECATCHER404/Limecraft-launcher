package com.limecraft.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class BackupService {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path backupRoot;

    public BackupService(Path gameDir) {
        this.backupRoot = gameDir.resolve("backups");
    }

    public Path snapshotVersion(String versionId, Path versionDir, Path instanceDir) throws IOException {
        List<Path> sources = new ArrayList<>();
        if (versionDir != null && Files.exists(versionDir)) {
            sources.add(versionDir);
        }
        if (instanceDir != null && Files.exists(instanceDir)) {
            sources.add(instanceDir);
        }
        if (sources.isEmpty()) {
            return null;
        }

        Path target = backupRoot.resolve("versions").resolve(safeName(versionId + "-" + STAMP.format(LocalDateTime.now())));
        Files.createDirectories(target);
        for (Path source : sources) {
            copyDirectory(source, target.resolve(source.getFileName().toString()));
        }
        return target;
    }

    public Path snapshotWorld(String sourceVersionId, String worldName, Path worldDir) throws IOException {
        if (worldDir == null || !Files.exists(worldDir)) {
            return null;
        }
        Path target = backupRoot.resolve("worlds").resolve(safeName(sourceVersionId + "-" + worldName + "-" + STAMP.format(LocalDateTime.now())));
        Files.createDirectories(target.getParent());
        copyDirectory(worldDir, target);
        return target;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path rel = source.relativize(path);
                Path dest = target.resolve(rel);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String safeName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
