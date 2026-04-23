package com.limecraft.launcher.auth;

public record MinecraftAccount(
        String accessToken,
        String username,
        String uuid,
        String xuid,
        String microsoftRefreshToken
) {
    public MinecraftAccount(String accessToken, String username, String uuid, String xuid) {
        this(accessToken, username, uuid, xuid, "");
    }
}
