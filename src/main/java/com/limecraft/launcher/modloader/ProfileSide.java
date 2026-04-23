package com.limecraft.launcher.modloader;

public enum ProfileSide {
    CLIENT("client"),
    SERVER("server");

    private final String id;

    ProfileSide(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    @Override
    public String toString() {
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    public static ProfileSide fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return CLIENT;
        }
        for (ProfileSide side : values()) {
            if (side.id.equalsIgnoreCase(raw.trim())) {
                return side;
            }
        }
        return CLIENT;
    }
}
