package com.limecraft.launcher.server;

public record ServerProfileSettings(
        String javaPath,
        String xmx,
        int port,
        String motd,
        int maxPlayers,
        boolean onlineMode,
        boolean pvp,
        boolean commandBlocks,
        boolean nogui
) {
    public static ServerProfileSettings defaults(String javaPath) {
        return new ServerProfileSettings(javaPath, "2G", 25565, "A Limecraft Server", 20, true, true, false, true);
    }
}
