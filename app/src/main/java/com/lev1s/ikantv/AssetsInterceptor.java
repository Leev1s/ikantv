package com.lev1s.ikantv;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 静态资源本地化：站点页面依赖的公共库（Bootstrap、图标字体、zepto、
 * lazyload、video.js）全部随 APK 打包，WebView 请求这些固定 URL 时
 * 直接从 assets 返回，不走网络——盒子外网质量差，少一个远程依赖
 * 就少一个奇怪 bug，页面打开也更快。文件与 cdnjs 官方版本逐字节一致，
 * 页面上的 SRI integrity 校验照常通过。
 */
public class AssetsInterceptor {

    private static final Map<String, String[]> MAP = new HashMap<>();
    static {
        String cdn = "https://cdnjs.cloudflare.com/ajax/libs/";
        MAP.put(cdn + "twitter-bootstrap/3.4.1/css/bootstrap.min.css",
                new String[]{"vendor/bootstrap.min.css", "text/css"});
        MAP.put(cdn + "twitter-bootstrap/3.4.1/fonts/glyphicons-halflings-regular.woff2",
                new String[]{"vendor/glyphicons-halflings-regular.woff2", "font/woff2"});
        MAP.put(cdn + "twitter-bootstrap/3.4.1/fonts/glyphicons-halflings-regular.woff",
                new String[]{"vendor/glyphicons-halflings-regular.woff", "font/woff"});
        MAP.put(cdn + "twitter-bootstrap/3.4.1/fonts/glyphicons-halflings-regular.ttf",
                new String[]{"vendor/glyphicons-halflings-regular.ttf", "font/ttf"});
        MAP.put(cdn + "zepto/1.1.6/zepto.min.js",
                new String[]{"vendor/zepto.min.js", "text/javascript"});
        MAP.put(cdn + "vanilla-lazyload/17.8.3/lazyload.min.js",
                new String[]{"vendor/lazyload.min.js", "text/javascript"});
        MAP.put(cdn + "video.js/8.4.0/video.min.js",
                new String[]{"vendor/video.min.js", "text/javascript"});
        MAP.put(cdn + "video.js/8.4.0/lang/zh-CN.min.js",
                new String[]{"vendor/zh-CN.min.js", "text/javascript"});
        MAP.put(cdn + "video.js/8.4.0/alt/video-js-cdn.min.css",
                new String[]{"vendor/video-js-cdn.min.css", "text/css"});
    }

    /** 命中本地资源返回响应，未命中返回 null（照常走网络） */
    public static WebResourceResponse intercept(Context ctx, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) return null;
        String url = request.getUrl().toString();
        // 字体 URL 可能带 ?v= 查询或 #iefix 片段，按主串匹配
        String clean = url.split("[?#]")[0];
        String[] hit = MAP.get(clean);
        if (hit == null) return null;
        try {
            InputStream is = ctx.getAssets().open(hit[0]);
            return new WebResourceResponse(hit[1], "UTF-8", is);
        } catch (Exception e) {
            return null;
        }
    }
}
