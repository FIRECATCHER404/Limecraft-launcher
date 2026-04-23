package com.limecraft.launcher.modloader;

public enum LoaderFamily {
    FABRIC("fabric", "Fabric", true, true),
    QUILT("quilt", "Quilt", true, true),
    FORGE("forge", "Forge", true, true),
    NEOFORGE("neoforge", "NeoForge", true, true);

    private final String id;
    private final String displayName;
    private final boolean clientSupported;
    private final boolean serverSupported;

    LoaderFamily(String id, String displayName, boolean clientSupported, boolean serverSupported) {
        this.id = id;
        this.displayName = displayName;
        this.clientSupported = clientSupported;
        this.serverSupported = serverSupported;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public boolean supports(ProfileSide side) {
        return switch (side) {
            case CLIENT -> clientSupported;
            case SERVER -> serverSupported;
        };
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static LoaderFamily fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return FABRIC;
        }
        for (LoaderFamily family : values()) {
            if (family.id.equalsIgnoreCase(raw.trim())) {
                return family;
            }
        }
        return FABRIC;
    }
}
