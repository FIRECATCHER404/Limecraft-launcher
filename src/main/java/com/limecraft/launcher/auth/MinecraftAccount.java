package com.limecraft.launcher.auth;

public record MinecraftAccount(
        String accessToken,
        String username,
        String uuid,
        String xuid
) {}