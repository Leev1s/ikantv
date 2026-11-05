package com.lev1s.ikantv.data;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 原生 HTTP 取数：只用 HttpURLConnection（播放器 fetchLinesNative 已真机验证此路可通），
 * 桌面 UA + 站点 Referer，请求节制（单请求、不用并发轰炸）。
 * 纯 Java 实现（不依赖 Android API），便于在 JVM 上离线回归解析器。
 */
public final class HttpFetcher {

    /* 与 WebView / 播放器取数完全一致的桌面 Chrome UA：
       老盒子 WebView 自带 UA 会被 Cloudflare 在 /play 拦下，桌面身份此前全线放行。 */
    public static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final String REFERER = "https://www.ikanbot.com/";
    private static final int MAX_BYTES = 2_500_000;

    private HttpFetcher() { }

    /**
     * GET 一个页面并返回 UTF-8 文本。
     * @throws FetchException NETWORK / HTTP / CHALLENGED（已做挑战页特征识别）
     */
    public static String get(String url) throws FetchException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(sanitize(url)).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", DESKTOP_UA);
            c.setRequestProperty("Referer", REFERER);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            int code = c.getResponseCode();
            if (code != 200) {
                // Cloudflare 拦截常见 403/503；再结合正文特征在下方统一判定，
                // 这里先把错误流读出来看一眼（可能就是挑战页）
                String errBody = readQuietly(c.getErrorStream());
                if (isChallenge(errBody)) {
                    throw new FetchException(FetchException.Kind.CHALLENGED,
                            "疑似 Cloudflare 校验（HTTP " + code + "）");
                }
                throw new FetchException(FetchException.Kind.HTTP,
                        "HTTP " + code + " @ " + url);
            }
            String body;
            try (InputStream is = c.getInputStream()) {
                body = readAll(is);
            }
            if (isChallenge(body)) {
                throw new FetchException(FetchException.Kind.CHALLENGED,
                        "疑似 Cloudflare 校验页");
            }
            return body;
        } catch (FetchException e) {
            throw e;
        } catch (Exception e) {
            throw new FetchException(FetchException.Kind.NETWORK,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // 注意不要 disconnect()：响应流读尽并关闭后，连接会被平台连接池
        // 回收复用（keep-alive）；disconnect 会把可复用的连接直接销毁。
    }

    /** Cloudflare 挑战页特征：标题 Just a moment / 挑战脚本域名 / 校验文案。 */
    static boolean isChallenge(String body) {
        if (body == null) return false;
        return body.contains("Just a moment")
                || body.contains("challenges.cloudflare.com")
                || body.contains("Attention Required! | Cloudflare")
                || body.contains("cf-challenge-running");
    }

    /** URL 里若含未编码的非 ASCII（如中文标签）或空格，逐字百分号编码（UTF-8）。 */
    static String sanitize(String url) {
        StringBuilder sb = new StringBuilder(url.length() + 16);
        for (int i = 0; i < url.length(); i++) {
            char ch = url.charAt(i);
            if (ch > 127 || ch == ' ') {
                byte[] bs = String.valueOf(ch).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : bs) sb.append('%').append(String.format("%02X", b & 0xFF));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n, total = 0;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > MAX_BYTES) break;
        }
        return bos.toString("UTF-8");
    }

    private static String readQuietly(InputStream is) {
        if (is == null) return null;
        try {
            return readAll(is);
        } catch (Exception e) {
            return null;
        } finally {
            // 错误流也要关闭：读尽并关闭后连接才能回池（或被实现干净回收）
            try { is.close(); } catch (Exception ignored) { }
        }
    }
}
