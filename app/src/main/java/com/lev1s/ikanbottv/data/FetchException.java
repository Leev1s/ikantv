package com.lev1s.ikanbottv.data;

/** 取数失败的明确类型，UI 层按类型决定提示与降级动作。 */
public class FetchException extends Exception {

    public enum Kind {
        /** 网络层失败（连不上、超时、DNS 等） */
        NETWORK,
        /** HTTP 非 200（非挑战类） */
        HTTP,
        /** 疑似被 Cloudflare 人机校验拦截：原生 HTTP 没有执行 JS 挑战的能力，
         *  UI 层应降级到 WebView 原站模式 */
        CHALLENGED,
        /** 页面拿到了但解析出 0 条目：大概率站点改版或返回了异常页，
         *  UI 层同样应降级到原站模式 */
        PARSE_EMPTY
    }

    public final Kind kind;

    public FetchException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }
}
