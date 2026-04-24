package com.limecraft.launcher.core;

public final class AppVersion {
    public static final String APP_NAME = "Limecraft";
    public static final String CURRENT = "1.5";
    public static final String GITHUB_REPO = "FIRECATCHER404/Limecraft-launcher";
    public static final String RELEASES_URL = "https://github.com/" + GITHUB_REPO + "/releases";
    public static final String ISSUES_URL = "https://github.com/" + GITHUB_REPO + "/issues/new/choose";

    private AppVersion() {
    }

    public static String userAgent() {
        return APP_NAME + "/" + CURRENT;
    }
}
