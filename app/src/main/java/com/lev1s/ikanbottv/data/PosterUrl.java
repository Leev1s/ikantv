package com.lev1s.ikanbottv.data;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 海报 URL 规则（照搬站点 common.js 的改写逻辑 + tv.js 已真机验证的主链）：
 * 只有 doubanio.com 的图会被改写成站点自家图片代理，其余原图直连。
 *
 * 候选链（顺序即加载优先级，加载器逐个回退）：
 *   imgp.ikanbot.eu.org → imgp1 → imgp2 → 站点 /proxy?url= → 豆瓣原图直连
 * tv.js 的实际行为是「imgp 主链 + 失败直连原图」，原生侧把报告摸清的三级
 * 回退补全在中间，任何一级活着都能出图。
 */
public final class PosterUrl {

    private PosterUrl() { }

    /** @param raw img@data-src 原图；@param videoId 海报所属视频 ID（代理路径要用） */
    public static List<String> candidates(String raw, long videoId) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        if (raw.contains("doubanio.com") && videoId > 0) {
            String u = base64Url(raw);
            out.add("https://imgp.ikanbot.eu.org/i/" + videoId + "?u=" + u);
            out.add("https://imgp1.ikanbot.eu.org/i/" + videoId + "?u=" + u);
            out.add("https://imgp2.ikanbot.eu.org/i/" + videoId + "?u=" + u);
            try {
                out.add("https://www.ikanbot.com/proxy?url="
                        + URLEncoder.encode(raw, "UTF-8"));
            } catch (UnsupportedEncodingException ignored) { }
        }
        out.add(raw);
        return out;
    }

    /** 与 tv.js 的 b64url 同规则：标准 Base64 后 +→-、/→_、去尾 =。
     *  java.util.Base64 在 Android API 26+ 可用（本 App minSdk 28），JVM 测试亦可跑。 */
    static String base64Url(String s) {
        byte[] data;
        try {
            data = s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            data = s.getBytes();
        }
        String b64 = java.util.Base64.getEncoder().encodeToString(data)
                .replace('+', '-').replace('/', '_');
        int end = b64.length();
        while (end > 0 && b64.charAt(end - 1) == '=') end--;
        return b64.substring(0, end);
    }
}
