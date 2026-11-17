package com.lev1s.ikantv.data;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure playback parsing rules shared by native and WebView harvest results. */
public final class PlaybackRules {

    private PlaybackRules() { }

    /** VLC can stop reporting time shortly before EOF; reject truncated/unknown streams. */
    public static boolean isAtMediaEnd(long positionMs, long durationMs) {
        if (positionMs < 0 || durationMs <= 0) return false;
        long toleranceMs = Math.min(30_000L, Math.max(2_000L, durationMs / 100L));
        return positionMs >= durationMs - toleranceMs;
    }

    /** Reproduces the site's deterministic token from current_id and e_token. */
    public static String computeToken(String videoId, String eToken) {
        if (videoId == null || eToken == null || videoId.isEmpty()) return null;
        String last4 = videoId.substring(Math.max(0, videoId.length() - 4));
        StringBuilder token = new StringBuilder(32);
        int position = 0;
        for (int i = 0; i < last4.length(); i++) {
            char digit = last4.charAt(i);
            if (!Character.isDigit(digit)) return null;
            position += (digit - '0') % 3 + 1;
            if (position + 8 > eToken.length()) return null;
            token.append(eToken, position, position + 8);
            position += 8;
        }
        return token.toString();
    }

    /**
     * Parses the site's {@code name$url#name$url} resource field into playable
     * HLS URLs. The first '$' separates a name from its URL so URL query strings
     * and later '$' characters are retained as part of the URL.
     */
    public static Map<String, String> parseResourceUrls(String resourceData) {
        Map<String, String> urls = new LinkedHashMap<>();
        if (resourceData == null || resourceData.isEmpty()) return urls;
        for (String part : resourceData.split("#")) {
            int separator = part.indexOf('$');
            if (separator <= 0) continue;
            String name = part.substring(0, separator).replaceAll("\\s+", " ").trim();
            String url = part.substring(separator + 1).trim();
            if (!name.isEmpty() && !url.isEmpty()
                    && url.matches("(?i).*\\.m3u8(\\?.*)?$")) {
                urls.put(name, url);
            }
        }
        return urls;
    }
}
