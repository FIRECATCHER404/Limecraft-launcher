package com.limecraft.launcher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.limecraft.launcher.core.HttpClient;
import okhttp3.FormBody;

import java.io.IOException;

public final class MicrosoftAuthService {
    public static final String DEFAULT_CLIENT_ID = "YOUR_AZURE_APP_CLIENT_ID";

    private final HttpClient http;
    private final String clientId;

    public MicrosoftAuthService(HttpClient http, String clientId) {
        this.http = http;
        this.clientId = clientId;
    }

    public DeviceCodeInfo beginDeviceLogin() throws IOException {
        FormBody body = new FormBody.Builder()
                .add("client_id", clientId)
                .add("scope", "XboxLive.signin offline_access")
                .build();
        JsonObject json = http.postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", body);
        return new DeviceCodeInfo(
                requiredString(json, "user_code", "Microsoft device code response"),
                requiredString(json, "verification_uri", "Microsoft device code response"),
                requiredString(json, "message", "Microsoft device code response"),
                requiredString(json, "device_code", "Microsoft device code response"),
                json.has("interval") ? json.get("interval").getAsInt() : 5
        );
    }

    public MinecraftAccount completeDeviceLogin(DeviceCodeInfo deviceCodeInfo) throws Exception {
        MicrosoftToken msToken = pollForMicrosoftToken(deviceCodeInfo);
        return authenticateWithMicrosoftToken(msToken.accessToken(), msToken.refreshToken());
    }

    public MinecraftAccount signInWithRefreshToken(String refreshToken) throws Exception {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("Microsoft refresh token is missing.");
        }

        FormBody refreshBody = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", clientId)
                .add("refresh_token", refreshToken)
                .add("scope", "XboxLive.signin offline_access")
                .build();

        JsonObject tokenRes = http.postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", refreshBody);
        String msAccessToken = requiredString(tokenRes, "access_token", "Microsoft refresh token response");
        String nextRefreshToken = optionalString(tokenRes, "refresh_token");
        if (nextRefreshToken.isBlank()) {
            nextRefreshToken = refreshToken;
        }
        return authenticateWithMicrosoftToken(msAccessToken, nextRefreshToken);
    }

    private MinecraftAccount authenticateWithMicrosoftToken(String msAccessToken, String msRefreshToken) throws Exception {
        JsonObject xblAuthReq = new JsonObject();
        JsonObject xblProps = new JsonObject();
        xblProps.addProperty("AuthMethod", "RPS");
        xblProps.addProperty("SiteName", "user.auth.xboxlive.com");
        xblProps.addProperty("RpsTicket", "d=" + msAccessToken);
        xblAuthReq.add("Properties", xblProps);
        xblAuthReq.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblAuthReq.addProperty("TokenType", "JWT");

        JsonObject xblRes = http.postJson("https://user.auth.xboxlive.com/user/authenticate", xblAuthReq);
        String xblToken = requiredString(xblRes, "Token", "Xbox Live auth response");
        String uhs = requiredXuiClaim(xblRes, "uhs", "Xbox Live auth response");

        JsonObject xstsReq = new JsonObject();
        JsonObject xstsProps = new JsonObject();
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken);
        xstsProps.add("UserTokens", userTokens);
        xstsProps.addProperty("SandboxId", "RETAIL");
        xstsReq.add("Properties", xstsProps);
        xstsReq.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsReq.addProperty("TokenType", "JWT");

        JsonObject xstsRes = http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsReq);
        String xstsToken = requiredString(xstsRes, "Token", "XSTS auth response");
        String xuid = optionalXuiClaim(xstsRes, "xid");
        if (xuid.isBlank()) {
            xuid = optionalXuiClaim(xstsRes, "xuid");
        }

        JsonObject mcLoginReq = new JsonObject();
        mcLoginReq.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        JsonObject mcLoginRes;
        try {
            mcLoginRes = http.postJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcLoginReq);
        } catch (IOException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("Invalid app registration")) {
                throw new IOException("Minecraft Services rejected this Azure app registration. Open https://aka.ms/AppRegInfo and submit this Client ID for approval, then wait for approval propagation.", ex);
            }
            throw ex;
        }
        String mcAccessToken = requiredString(mcLoginRes, "access_token", "Minecraft Xbox login response");

        JsonObject profileRes = profile(mcAccessToken);
        String uuid = optionalString(profileRes, "id");
        String username = optionalString(profileRes, "name");
        if (uuid.isBlank() || username.isBlank()) {
            throw new IOException("Minecraft profile is missing id/name. Account may not own Java Edition or profile is not set up.");
        }

        return new MinecraftAccount(mcAccessToken, username, uuid, xuid, msRefreshToken);
    }

    public boolean hasGameOwnership(String minecraftAccessToken) throws IOException {
        JsonObject entitlements = authorizedGet("https://api.minecraftservices.com/entitlements/mcstore", minecraftAccessToken);
        return entitlements.has("items") && entitlements.getAsJsonArray("items").size() > 0;
    }

    private JsonObject profile(String minecraftAccessToken) throws IOException {
        return authorizedGet("https://api.minecraftservices.com/minecraft/profile", minecraftAccessToken);
    }

    private JsonObject authorizedGet(String url, String bearer) throws IOException {
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + bearer)
                .get()
                .build();
        try (okhttp3.Response response = http.raw().newCall(request).execute()) {
            String bodyText = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Auth GET failed " + response.code() + " at " + url + (bodyText.isBlank() ? "" : " - " + bodyText));
            }
            if (bodyText.isBlank()) {
                throw new IOException("Auth GET returned empty body at " + url);
            }
            return new com.google.gson.Gson().fromJson(bodyText, JsonObject.class);
        }
    }

    private MicrosoftToken pollForMicrosoftToken(DeviceCodeInfo info) throws Exception {
        while (true) {
            FormBody poll = new FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("client_id", clientId)
                    .add("device_code", info.deviceCode())
                    .add("scope", "XboxLive.signin offline_access")
                    .build();
            try {
                JsonObject tokenRes = http.postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", poll);
                String accessToken = requiredString(tokenRes, "access_token", "Microsoft token response");
                String refreshToken = optionalString(tokenRes, "refresh_token");
                return new MicrosoftToken(accessToken, refreshToken);
            } catch (IOException ex) {
                String message = ex.getMessage() == null ? "" : ex.getMessage();
                if (message.contains("authorization_pending")) {
                    Thread.sleep(Math.max(1, info.intervalSeconds()) * 1000L);
                    continue;
                }
                if (message.contains("slow_down")) {
                    Thread.sleep((Math.max(1, info.intervalSeconds()) + 5L) * 1000L);
                    continue;
                }
                if (message.contains("AADSTS70020") || (message.contains("invalid_grant") && message.contains("device_code"))) {
                    throw new IOException("Microsoft rejected the device code (AADSTS70020). Start sign-in again to request a new code.", ex);
                }
                throw ex;
            }
        }
    }

    private String requiredString(JsonObject obj, String key, String source) throws IOException {
        String value = optionalString(obj, key);
        if (value.isBlank()) {
            throw new IOException(source + " is missing required field '" + key + "'.");
        }
        return value;
    }

    private String optionalString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return "";
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return "";
        }
        try {
            return el.getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String requiredXuiClaim(JsonObject obj, String key, String source) throws IOException {
        String value = optionalXuiClaim(obj, key);
        if (value.isBlank()) {
            throw new IOException(source + " is missing xui claim '" + key + "'.");
        }
        return value;
    }

    private String optionalXuiClaim(JsonObject obj, String key) {
        if (obj == null || !obj.has("DisplayClaims")) {
            return "";
        }
        JsonObject claims = obj.getAsJsonObject("DisplayClaims");
        if (claims == null || !claims.has("xui")) {
            return "";
        }
        JsonArray xui = claims.getAsJsonArray("xui");
        if (xui == null || xui.isEmpty()) {
            return "";
        }
        JsonElement first = xui.get(0);
        if (first == null || !first.isJsonObject()) {
            return "";
        }
        return optionalString(first.getAsJsonObject(), key);
    }

    private record MicrosoftToken(String accessToken, String refreshToken) {}
}


