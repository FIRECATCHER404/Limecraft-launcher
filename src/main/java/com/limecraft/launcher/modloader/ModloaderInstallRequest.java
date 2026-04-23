package com.limecraft.launcher.modloader;

import com.limecraft.launcher.core.VersionEntry;

public record ModloaderInstallRequest(
        LoaderFamily family,
        ProfileSide side,
        VersionEntry baseVersion,
        String customName,
        String loaderVersion,
        String javaExecutable
) {}
