package com.limecraft.launcher.core;

public record VersionEntry(String id, String type, String url, String releaseTime, String side) {
    public VersionEntry(String id, String type, String url, String releaseTime) {
        this(id, type, url, releaseTime, "both");
    }

    public String displayType() {
        String lowerId = id.toLowerCase();
        String lowerType = type.toLowerCase();

        if (isAprilFools(lowerId)) {
            return "april fools";
        }
        if ("old_alpha".equals(lowerType)) {
            if (lowerId.startsWith("c0.") || lowerId.startsWith("rd-")) {
                return "classic";
            }
            if (lowerId.startsWith("in-")) {
                return "indev";
            }
            if (lowerId.startsWith("inf-")) {
                return "infdev";
            }
        }
        return type;
    }

    private boolean isAprilFools(String lowerId) {
        return lowerId.contains("20w14infinite")
                || lowerId.contains("22w13oneblockatatime")
                || lowerId.contains("23w13a_or_b")
                || lowerId.contains("25w14craftmine")
                || lowerId.contains("3d shareware v1.34")
                || lowerId.contains("1.rv-pre1")
                || lowerId.contains("24w14potato");
    }

    @Override
    public String toString() {
        return id + " (" + displayType() + ")";
    }

    public boolean supportsClient() {
        return !"server".equalsIgnoreCase(side);
    }

    public boolean supportsServer() {
        return !"client".equalsIgnoreCase(side);
    }
}
