package com.limecraft.launcher.auth;

import java.io.IOException;

public interface SecureTokenStore {
    void put(String key, String token) throws IOException;

    String get(String key) throws IOException;

    void remove(String key) throws IOException;
}
