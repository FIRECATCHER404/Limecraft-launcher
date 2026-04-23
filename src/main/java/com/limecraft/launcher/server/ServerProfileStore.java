package com.limecraft.launcher.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ServerProfileStore {
    private static final String PROFILE_FILE = "server-profile.properties";

    public ServerProfileSettings load(Path serverDir, String defaultJavaPath) throws IOException {
        ServerProfileSettings defaults = ServerProfileSettings.defaults(defaultJavaPath == null || defaultJavaPath.isBlank() ? "java" : defaultJavaPath.trim());
        Properties props = new Properties();
        Path profileFile = serverDir.resolve(PROFILE_FILE);
        if (Files.exists(profileFile)) {
            try (Reader reader = Files.newBufferedReader(profileFile, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            return fromProperties(props, defaults);
        }

        Path serverProperties = serverDir.resolve("server.properties");
        if (Files.exists(serverProperties)) {
            try (Reader reader = Files.newBufferedReader(serverProperties, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            return fromProperties(props, defaults);
        }
        return defaults;
    }

    public void save(Path serverDir, ServerProfileSettings settings) throws IOException {
        Files.createDirectories(serverDir);
        Properties props = new Properties();
        props.setProperty("java_path", normalize(settings.javaPath(), "java"));
        props.setProperty("xmx", normalize(settings.xmx(), "2G"));
        props.setProperty("server-port", String.valueOf(settings.port()));
        props.setProperty("motd", normalize(settings.motd(), "A Limecraft Server"));
        props.setProperty("max-players", String.valueOf(settings.maxPlayers()));
        props.setProperty("online-mode", String.valueOf(settings.onlineMode()));
        props.setProperty("pvp", String.valueOf(settings.pvp()));
        props.setProperty("enable-command-block", String.valueOf(settings.commandBlocks()));
        props.setProperty("nogui", String.valueOf(settings.nogui()));

        Path profileFile = serverDir.resolve(PROFILE_FILE);
        try (Writer writer = Files.newBufferedWriter(profileFile, StandardCharsets.UTF_8)) {
            props.store(writer, "Limecraft server profile");
        }
    }

    public void mergeIntoServerProperties(Path serverDir, ServerProfileSettings settings) throws IOException {
        Files.createDirectories(serverDir);
        Path propertiesFile = serverDir.resolve("server.properties");
        Properties props = new Properties();
        if (Files.exists(propertiesFile)) {
            try (Reader reader = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        }

        props.setProperty("server-port", String.valueOf(settings.port()));
        props.setProperty("motd", normalize(settings.motd(), "A Limecraft Server"));
        props.setProperty("max-players", String.valueOf(settings.maxPlayers()));
        props.setProperty("online-mode", String.valueOf(settings.onlineMode()));
        props.setProperty("pvp", String.valueOf(settings.pvp()));
        props.setProperty("enable-command-block", String.valueOf(settings.commandBlocks()));

        try (Writer writer = Files.newBufferedWriter(propertiesFile, StandardCharsets.UTF_8)) {
            props.store(writer, "Minecraft server properties updated by Limecraft");
        }
    }

    private ServerProfileSettings fromProperties(Properties props, ServerProfileSettings defaults) {
        return new ServerProfileSettings(
                props.getProperty("java_path", defaults.javaPath()).trim(),
                props.getProperty("xmx", defaults.xmx()).trim(),
                parseInt(props.getProperty("server-port"), defaults.port()),
                normalize(props.getProperty("motd"), defaults.motd()),
                parseInt(props.getProperty("max-players"), defaults.maxPlayers()),
                parseBoolean(props.getProperty("online-mode"), defaults.onlineMode()),
                parseBoolean(props.getProperty("pvp"), defaults.pvp()),
                parseBoolean(props.getProperty("enable-command-block"), defaults.commandBlocks()),
                parseBoolean(props.getProperty("nogui"), defaults.nogui())
        );
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.trim().isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
