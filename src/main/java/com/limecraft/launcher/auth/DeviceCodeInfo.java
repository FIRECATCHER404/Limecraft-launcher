package com.limecraft.launcher.auth;

public record DeviceCodeInfo(
        String userCode,
        String verificationUri,
        String message,
        String deviceCode,
        int intervalSeconds
) {}