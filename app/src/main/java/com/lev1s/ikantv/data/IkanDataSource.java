package com.lev1s.ikantv.data;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 原生浏览层的数据入口（同步调用，请在后台线程使用）。
 * 每个方法 = 一次 HTML 请求 + 解析；解析出 0 条目按 PARSE_EMPTY 抛出，
 * 让 UI 层能按失败类型在应用内给出提示与重试，而不是展示空白页。
 */
public final class IkanDataSource {

    public static final String BASE = "https://www.ikanbot.com";
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(IkanDataSource.class.getName());

    private IkanDataSource() { }

    public static List<Section> fetchHome() throws FetchException {
        String html = HttpFetcher.get(BASE + "/");
        List<Section> sections = IkanHtmlParser.parseHome(html, BASE + "/");
        int total = 0;
        for (Section s : sections) total += s.items.size();
        if (total == 0) {
            throw new FetchException(FetchException.Kind.PARSE_EMPTY, "首页解析出 0 条目");
        }
        return sections;
    }

    /** /hot/ 或 /category/ 列表页（模板 A 网格）。 */
    public static GridPage fetchGrid(String url) throws FetchException {
        String html = HttpFetcher.get(url);
        GridPage page = IkanHtmlParser.parseGrid(html, url);
        if (page.items.isEmpty()) {
            throw new FetchException(FetchException.Kind.PARSE_EMPTY,
                    "列表页解析出 0 条目 @ " + url);
        }
        return page;
    }

    /** 搜索第 1 页（模板 B）。后续页用 fetchSearchNext 跟随游标链接。 */
    public static GridPage fetchSearch(String query) throws FetchException {
        String url;
        try {
            url = BASE + "/search?q=" + URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            url = BASE + "/search?q=" + query;
        }
        String html = HttpFetcher.get(url);
        GridPage page = IkanHtmlParser.parseSearch(html, url);
        return checkSearchPage(page);
    }

    /** 搜索下一页：nextPageUrl 来自上一页页内链接（含 n= 游标，原样跟随）。 */
    public static GridPage fetchSearchNext(String nextPageUrl) throws FetchException {
        String html = HttpFetcher.get(nextPageUrl);
        return checkSearchPage(IkanHtmlParser.parseSearch(html, nextPageUrl));
    }

    static GridPage checkSearchPage(GridPage page) throws FetchException {
        if (page.items.isEmpty()) {
            if (page.searchNoResults) {
                LOG.info("搜索页明确显示无结果");
            } else {
                LOG.warning("搜索页无结果条目且缺少无结果标记，解析失败");
                throw new FetchException(FetchException.Kind.PARSE_EMPTY, "搜索页解析出 0 条目");
            }
        }
        return page;
    }

    public static List<Section> fetchBillboard() throws FetchException {
        String url = BASE + "/billboard.html";
        String html = HttpFetcher.get(url);
        List<Section> sections = IkanHtmlParser.parseBillboard(html, url);
        int total = 0;
        for (Section s : sections) total += s.items.size();
        if (total == 0) {
            throw new FetchException(FetchException.Kind.PARSE_EMPTY, "榜单页解析出 0 条目");
        }
        return sections;
    }

    /** 首页/榜单的一个板块：标题 +「更多」链接 + 条目列表。 */
    public static class Section {
        public String title;
        /** 板块「更多»」的目标 URL（完整 URL），没有则为 null */
        public String moreUrl;
        public List<VideoItem> items = new ArrayList<>();
    }

    /** 列表页（/hot/、/category/、搜索）解析结果：本页条目 + 下一页 URL。 */
    public static class GridPage {
        public List<VideoItem> items = new ArrayList<>();
        /** 搜索页明确出现站点的无结果提示。 */
        public boolean searchNoResults;
        /**
         * 下一页完整 URL；null = 没有下一页。
         * /hot/ 与 /category/ 是从页内分页链接提取（也可按规律构造），
         * 搜索的 n= 游标不可构造、只能跟随页内链接提取。
         */
        public String nextPageUrl;
    }
}
