package com.limecraft.launcher.modloader;

import com.limecraft.launcher.core.VersionEntry;

import java.nio.file.Path;

public record ModloaderInstallResult(
        VersionEntry versionEntry,
        String loaderVersion,
        Path serverDirectory
) {}
