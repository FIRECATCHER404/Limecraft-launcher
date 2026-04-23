package com.limecraft.launcher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AccountStore {
    private final Path accountsFile;
    private final SecureTokenStore tokenStore;

    public AccountStore(Path gameDir, SecureTokenStore tokenStore) {
        this.accountsFile = gameDir.resolve("accounts.json");
        this.tokenStore = tokenStore;
    }

    public synchronized List<SavedMicrosoftAccount> loadAccounts() throws IOException {
        if (!Files.exists(accountsFile)) {
            return List.of();
        }
        JsonArray items = JsonParser.parseString(Files.readString(accountsFile, StandardCharsets.UTF_8)).getAsJsonArray();
        List<SavedMicrosoftAccount> accounts = new ArrayList<>();
        for (JsonElement element : items) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject o = element.getAsJsonObject();
            accounts.add(new SavedMicrosoftAccount(
                    readString(o, "profileId"),
                    readString(o, "username"),
                    readString(o, "uuid"),
                    readString(o, "xuid"),
                    readString(o, "lastUsedAt")
            ));
        }
        accounts.sort(Comparator.comparing(SavedMicrosoftAccount::lastUsedAt, Comparator.nullsLast(String::compareTo)).reversed());
        return accounts;
    }

    public synchronized SavedMicrosoftAccount saveAccount(MinecraftAccount account) throws IOException {
        String profileId = profileIdFor(account);
        SavedMicrosoftAccount saved = new SavedMicrosoftAccount(
                profileId,
                account.username(),
                account.uuid(),
                account.xuid(),
                Instant.now().toString()
        );

        List<SavedMicrosoftAccount> accounts = new ArrayList<>(loadAccounts());
        accounts.removeIf(existing -> profileId.equalsIgnoreCase(existing.profileId()));
        accounts.add(saved);
        storeAccounts(accounts);

        String refreshToken = account.microsoftRefreshToken() == null ? "" : account.microsoftRefreshToken().trim();
        if (!refreshToken.isBlank()) {
            tokenStore.put(profileId, refreshToken);
        }
        return saved;
    }

    public synchronized void touchAccount(String profileId) throws IOException {
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        List<SavedMicrosoftAccount> accounts = new ArrayList<>(loadAccounts());
        for (int i = 0; i < accounts.size(); i++) {
            SavedMicrosoftAccount account = accounts.get(i);
            if (profileId.equalsIgnoreCase(account.profileId())) {
                accounts.set(i, new SavedMicrosoftAccount(
                        account.profileId(),
                        account.username(),
                        account.uuid(),
                        account.xuid(),
                        Instant.now().toString()
                ));
                break;
            }
        }
        storeAccounts(accounts);
    }

    public synchronized String loadRefreshToken(String profileId) throws IOException {
        return tokenStore.get(profileId);
    }

    public synchronized void removeAccount(String profileId) throws IOException {
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        List<SavedMicrosoftAccount> accounts = new ArrayList<>(loadAccounts());
        accounts.removeIf(existing -> profileId.equalsIgnoreCase(existing.profileId()));
        storeAccounts(accounts);
        tokenStore.remove(profileId);
    }

    public String profileIdFor(MinecraftAccount account) {
        if (account.uuid() != null && !account.uuid().isBlank()) {
            return account.uuid().trim();
        }
        if (account.username() != null && !account.username().isBlank()) {
            return account.username().trim().toLowerCase();
        }
        return "microsoft-account";
    }

    private void storeAccounts(List<SavedMicrosoftAccount> accounts) throws IOException {
        Files.createDirectories(accountsFile.getParent());
        JsonArray out = new JsonArray();
        accounts.stream()
                .sorted(Comparator.comparing(SavedMicrosoftAccount::lastUsedAt, Comparator.nullsLast(String::compareTo)).reversed())
                .forEach(account -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("profileId", account.profileId());
                    item.addProperty("username", account.username());
                    item.addProperty("uuid", account.uuid());
                    item.addProperty("xuid", account.xuid());
                    item.addProperty("lastUsedAt", account.lastUsedAt());
                    out.add(item);
                });
        Files.writeString(accountsFile, out.toString(), StandardCharsets.UTF_8);
    }

    private String readString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
