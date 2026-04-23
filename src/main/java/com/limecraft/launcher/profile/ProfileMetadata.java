package com.limecraft.launcher.profile;

public record ProfileMetadata(
        String versionId,
        boolean favorite,
        String notes,
        String tags,
        String groupName,
        String iconPath,
        long playTimeSeconds,
        String lastPlayedAt
) {
    public static ProfileMetadata defaults(String versionId) {
        return new ProfileMetadata(versionId, false, "", "", "", "", 0L, "");
    }
}
