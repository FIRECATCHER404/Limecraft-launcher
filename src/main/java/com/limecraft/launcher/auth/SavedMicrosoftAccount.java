package com.limecraft.launcher.auth;

public record SavedMicrosoftAccount(
        String profileId,
        String username,
        String uuid,
        String xuid,
        String lastUsedAt
) {
    @Override
    public String toString() {
        if (username == null || username.isBlank()) {
            return profileId == null ? "Unknown Account" : profileId;
        }
        if (uuid == null || uuid.isBlank()) {
            return username;
        }
        String shortUuid = uuid.length() <= 8 ? uuid : uuid.substring(0, 8);
        return username + " (" + shortUuid + ")";
    }
}
