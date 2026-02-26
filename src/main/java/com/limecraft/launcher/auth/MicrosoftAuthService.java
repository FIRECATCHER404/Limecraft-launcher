package com.limecraft.launcher.auth;

import com.google.gson.JsonArray;
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
                json.get("user_code").getAsString(),
                json.get("verification_uri").getAsString(),
                json.get("message").getAsString(),
                json.get("device_code").getAsString(),
                json.get("interval").getAsInt()
        );
    }

    public MinecraftAccount completeDeviceLogin(DeviceCodeInfo deviceCodeInfo) throws Exception {
        String msAccessToken = pollForMicrosoftToken(deviceCodeInfo);

        JsonObject xblAuthReq = new JsonObject();
        JsonObject xblProps = new JsonObject();
        xblProps.addProperty("AuthMethod", "RPS");
        xblProps.addProperty("SiteName", "user.auth.xboxlive.com");
        xblProps.addProperty("RpsTicket", "d=" + msAccessToken);
        xblAuthReq.add("Properties", xblProps);
        xblAuthReq.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblAuthReq.addProperty("TokenType", "JWT");

        JsonObject xblRes = http.postJson("https://user.auth.xboxlive.com/user/authenticate", xblAuthReq);
        String xblToken = xblRes.get("Token").getAsString();
        String uhs = xblRes.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();

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
        String xstsToken = xstsRes.get("Token").getAsString();
        String xuid = xstsRes.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject().get("xid").getAsString();

        JsonObject mcLoginReq = new JsonObject();
        mcLoginReq.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        JsonObject mcLoginRes = http.postJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcLoginReq);
        String mcAccessToken = mcLoginRes.get("access_token").getAsString();

        JsonObject profileRes = profile(mcAccessToken);
        String uuid = profileRes.get("id").getAsString();
        String username = profileRes.get("name").getAsString();

        return new MinecraftAccount(mcAccessToken, username, uuid, xuid);
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
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Auth GET failed " + response.code() + " at " + url);
            }
            return new com.google.gson.Gson().fromJson(response.body().string(), JsonObject.class);
        }
    }

    private String pollForMicrosoftToken(DeviceCodeInfo info) throws Exception {
        while (true) {
            FormBody poll = new FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("client_id", clientId)
                    .add("device_code", info.deviceCode())
                    .build();
            try {
                JsonObject tokenRes = http.postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", poll);
                return tokenRes.get("access_token").getAsString();
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
                throw ex;
            }
        }
    }
}