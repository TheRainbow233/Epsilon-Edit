package com.github.epsilon.utils.auth;

import com.github.epsilon.Constants;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Microsoft → Xbox → Minecraft 认证链。
 */
public class MicrosoftAuth {

    // ── Session token / refresh（Minecraft 旧 client）────────────────────────

    private static final String MC_CLIENT_ID = "00000000402B5328";
    private static final String MC_REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";
    private static final String MC_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    // ── 浏览器 OAuth（Azure 注册 client，支持 localhost 回调）─────────────────
    //不想注册，先用水影的吧(

    private static final String OAUTH_CLIENT_ID = "0add8caf-2cc6-4546-b798-c3d171217dd9";
    private static final String OAUTH_SCOPE = "XboxLive.signin offline_access";

    // ── 共享端点 ──────────────────────────────────────────────────────────────

    private static final String MS_AUTHORIZE_URL =
            "https://login.live.com/oauth20_authorize.srf";
    private static final String MS_TOKEN_URL =
            "https://login.live.com/oauth20_token.srf";
    private static final String XBL_AUTH_URL =
            "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL =
            "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile";

    private static final long AUTH_TIMEOUT_SECONDS = 120;

    public record AuthResult(String name, String uuid, String accessToken, String refreshToken) {}

    // ── Session token 认证（保留给现有 addSessionAccount / login）─────────────

    /**
     * 用 refresh token (M.R3_BAY... 或 eyJ...) 走 MS refresh → XBL → XSTS → MC 完整链。
     */
    public static AuthResult authenticate(String token) throws AuthException {
        Constants.LOGGER.debug("[MSAuth] Step 1: refresh...");
        String msToken = refreshMcToken(token);

        Constants.LOGGER.debug("[MSAuth] Step 2: XBL...");
        var xbl = xblAuth(msToken);
        String xblToken = xbl.token();
        String uhs = xbl.uhs();

        Constants.LOGGER.debug("[MSAuth] Step 3: XSTS...");
        String xstsToken = xstsAuth(xblToken);

        Constants.LOGGER.debug("[MSAuth] Step 4: MC login...");
        String mcToken = mcLogin(uhs, xstsToken);

        Constants.LOGGER.debug("[MSAuth] Step 5: Profile...");
        JsonObject profile = json(httpGet(MC_PROFILE_URL, mcToken));
        String name = profile.get("name").getAsString();
        String uuid = profile.get("id").getAsString();
        Constants.LOGGER.debug("[MSAuth] SUCCESS: {} / {}", name, uuid);

        return new AuthResult(name, uuid, mcToken, token);
    }

    /**
     * 刷新 MICROSOFT 类型账号的 token（Azure client，带 d= 前缀的 XBL 认证）。
     */
    public static AuthResult authenticateMicrosoft(String refreshToken) throws AuthException {
        Constants.LOGGER.debug("[MSAuth] Step 1: refresh (Azure client)...");
        String msToken = refreshOAuthToken(refreshToken);

        Constants.LOGGER.debug("[MSAuth] Step 2: XBL (d= prefix)...");
        var xbl = xblAuth("d=" + msToken);
        String xblToken = xbl.token();
        String uhs = xbl.uhs();

        Constants.LOGGER.debug("[MSAuth] Step 3: XSTS...");
        String xstsToken = xstsAuth(xblToken);

        Constants.LOGGER.debug("[MSAuth] Step 4: MC login...");
        String mcToken = mcLogin(uhs, xstsToken);

        Constants.LOGGER.debug("[MSAuth] Step 5: Profile...");
        JsonObject profile = json(httpGet(MC_PROFILE_URL, mcToken));
        String name = profile.get("name").getAsString();
        String uuid = profile.get("id").getAsString();
        Constants.LOGGER.debug("[MSAuth] SUCCESS: {} / {}", name, uuid);

        return new AuthResult(name, uuid, mcToken, refreshToken);
    }

    // ── 浏览器 OAuth 登录 ─────────────────────────────────────────────────────

    /**
     * 启动本地 HTTP 服务器 + 打开浏览器，阻塞等待 Microsoft 授权回调。
     * 完成后自动走 XBL→XSTS→MC 链，返回完整认证结果。
     */
    public static AuthResult loginWithBrowser() throws AuthException {
        HttpServer server = null;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int port = server.getAddress().getPort();
            String redirectUri = "http://localhost:" + port + "/login";
            CountDownLatch latch = new CountDownLatch(1);
            String[] codeHolder = new String[1];
            String[] errorHolder = new String[1];

            server.createContext("/login", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                String code = extractParam(query, "code");
                String error = extractParam(query, "error");
                if (code != null) {
                    codeHolder[0] = code;
                    sendHtml(exchange, 200, SUCCESS_HTML);
                    latch.countDown();
                } else if (error != null) {
                    errorHolder[0] = error + ": " + extractParam(query, "error_description");
                    sendHtml(exchange, 400, FAIL_HTML);
                    latch.countDown();
                } else {
                    sendHtml(exchange, 400, FAIL_HTML);
                }
            });
            server.start();

            String authUrl = MS_AUTHORIZE_URL
                    + "?client_id=" + enc(OAUTH_CLIENT_ID)
                    + "&response_type=code"
                    + "&redirect_uri=" + enc(redirectUri)
                    + "&scope=" + enc(OAUTH_SCOPE)
                    + "&prompt=select_account";

            Constants.LOGGER.info("[MSAuth] Opening browser, port={}", port);
            openBrowser(authUrl);

            boolean done = latch.await(AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            server.stop(1);

            if (!done) throw new AuthException("Authentication timed out");
            if (errorHolder[0] != null) throw new AuthException(errorHolder[0]);
            if (codeHolder[0] == null) throw new AuthException("No auth code received");

            // 用 auth code 换 token
            String codeBody = "client_id=" + enc(OAUTH_CLIENT_ID)
                    + "&code=" + enc(codeHolder[0])
                    + "&grant_type=authorization_code"
                    + "&redirect_uri=" + enc(redirectUri);

            JsonObject tokenResp = json(httpPostForm(MS_TOKEN_URL, codeBody));
            if (tokenResp.has("error"))
                throw new AuthException("Code exchange: " + tokenResp.get("error").getAsString());
            if (!tokenResp.has("access_token") || !tokenResp.has("refresh_token"))
                throw new AuthException("No token in response");

            String accessToken = tokenResp.get("access_token").getAsString();
            String refreshToken = tokenResp.get("refresh_token").getAsString();

            Constants.LOGGER.debug("[MSAuth] Code exchanged, running XBL→XSTS→MC...");

            var xbl = xblAuth("d=" + accessToken);
            String xstsToken = xstsAuth(xbl.token());
            String mcToken = mcLogin(xbl.uhs(), xstsToken);

            JsonObject profile = json(httpGet(MC_PROFILE_URL, mcToken));
            String name = profile.get("name").getAsString();
            String uuid = profile.get("id").getAsString();
            Constants.LOGGER.info("[MSAuth] Browser login OK: {} / {}", name, uuid);

            return new AuthResult(name, uuid, mcToken, refreshToken);

        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthException("Browser auth: " + e.getMessage());
        } finally {
            if (server != null) {
                try { server.stop(0); } catch (Exception ignored) {}
            }
        }
    }

    // ── XBL / XSTS / MC 公共步骤 ─────────────────────────────────────────────

    private record XblResult(String token, String uhs) {}

    private static XblResult xblAuth(String rpsTicket) throws AuthException {
        var xblProps = new JsonObject();
        xblProps.addProperty("AuthMethod", "RPS");
        xblProps.addProperty("SiteName", "user.auth.xboxlive.com");
        xblProps.addProperty("RpsTicket", rpsTicket);
        var xblReq = new JsonObject();
        xblReq.add("Properties", xblProps);
        xblReq.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblReq.addProperty("TokenType", "JWT");
        JsonObject xbl = json(httpPostJson(XBL_AUTH_URL, new Gson().toJson(xblReq)));
        String token = xbl.get("Token").getAsString();
        String uhs = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
                .get(0).getAsJsonObject().get("uhs").getAsString();
        return new XblResult(token, uhs);
    }

    private static String xstsAuth(String xblToken) throws AuthException {
        var tokens = new JsonArray();
        tokens.add(xblToken);
        var xstsProps = new JsonObject();
        xstsProps.addProperty("SandboxId", "RETAIL");
        xstsProps.add("UserTokens", tokens);
        var xstsReq = new JsonObject();
        xstsReq.add("Properties", xstsProps);
        xstsReq.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsReq.addProperty("TokenType", "JWT");
        JsonObject xsts = json(httpPostJson(XSTS_AUTH_URL, new Gson().toJson(xstsReq)));
        if (xsts.has("XErr"))
            throw new AuthException("XSTS XErr=" + xsts.get("XErr").getAsString());
        return xsts.get("Token").getAsString();
    }

    private static String mcLogin(String uhs, String xstsToken) throws AuthException {
        var mcReq = new JsonObject();
        mcReq.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        JsonObject mc = json(httpPostJson(MC_LOGIN_URL, new Gson().toJson(mcReq)));
        return mc.get("access_token").getAsString();
    }

    // ── Session token 刷新 ────────────────────────────────────────────────────

    private static String refreshMcToken(String token) throws AuthException {
        String body = "client_id=" + enc(MC_CLIENT_ID)
                + "&scope=" + enc(MC_SCOPE)
                + "&grant_type=refresh_token"
                + "&redirect_uri=" + enc(MC_REDIRECT_URI)
                + "&refresh_token=" + enc(token);

        String resp = httpPostForm(MS_TOKEN_URL, body);
        JsonObject obj = json(resp);
        if (obj.has("error"))
            throw new AuthException("MS refresh: " + obj.get("error").getAsString()
                    + (obj.has("error_description")
                    ? " - " + obj.get("error_description").getAsString() : ""));
        if (!obj.has("access_token"))
            throw new AuthException("No access_token in response");
        return obj.get("access_token").getAsString();
    }

    /** 使用 Azure client ID 刷新 OAuth token（给 MICROSOFT 类型账号）。 */
    private static String refreshOAuthToken(String token) throws AuthException {
        String body = "client_id=" + enc(OAUTH_CLIENT_ID)
                + "&scope=" + enc(OAUTH_SCOPE)
                + "&grant_type=refresh_token"
                + "&refresh_token=" + enc(token);

        String resp = httpPostForm(MS_TOKEN_URL, body);
        JsonObject obj = json(resp);
        if (obj.has("error"))
            throw new AuthException("OAuth refresh: " + obj.get("error").getAsString()
                    + (obj.has("error_description")
                    ? " - " + obj.get("error_description").getAsString() : ""));
        if (!obj.has("access_token"))
            throw new AuthException("No access_token in response");
        return obj.get("access_token").getAsString();
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static String httpGet(String url, String bearer) throws AuthException {
        try {
            var c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestProperty("Authorization", "Bearer " + bearer);
            c.setConnectTimeout(15000); c.setReadTimeout(15000);
            return read(c);
        } catch (IOException e) { throw new AuthException("GET " + url + ": " + e); }
    }

    private static String httpPostForm(String url, String body) throws AuthException {
        try {
            var c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setConnectTimeout(15000); c.setReadTimeout(15000);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return read(c);
        } catch (IOException e) { throw new AuthException("POST " + url + ": " + e); }
    }

    private static String httpPostJson(String url, String body) throws AuthException {
        try {
            var c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(15000); c.setReadTimeout(15000);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return read(c);
        } catch (IOException e) { throw new AuthException("POST " + url + ": " + e); }
    }

    private static String read(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        try (InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream()) {
            if (is == null) throw new IOException("Empty, HTTP " + code);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String extractParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf("=");
            if (eq > 0 && key.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static void openBrowser(String url) {
        try {
            var desktop = java.awt.Desktop.getDesktop();
            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                desktop.browse(URI.create(url));
            }
        } catch (Exception e) {
            Constants.LOGGER.warn("[MSAuth] Failed to open browser: {}", e.getMessage());
        }
    }

    private static void sendHtml(com.sun.net.httpserver.HttpExchange exchange,
                                  int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final String SUCCESS_HTML = """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"><title>Epsilon</title>
            <style>body{font-family:Arial;background:#121212;color:#fff;text-align:center;padding:50px;}
            .box{background:#1E1E1E;padding:24px;border-radius:12px;display:inline-block;}
            h1{color:#4CAF50;}</style></head>
            <body><div class="box"><h1>Login Successful</h1>
            <p>You can close this tab now.</p></div></body></html>
            """;

    private static final String FAIL_HTML = """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"><title>Epsilon</title>
            <style>body{font-family:Arial;background:#121212;color:#fff;text-align:center;padding:50px;}
            .box{background:#1E1E1E;padding:24px;border-radius:12px;display:inline-block;}
            h1{color:#E53935;}</style></head>
            <body><div class="box"><h1>Login Failed</h1>
            <p>Please close this tab and try again.</p></div></body></html>
            """;

    public static class AuthException extends Exception {
        public AuthException(String msg) { super(msg); }
    }
}
