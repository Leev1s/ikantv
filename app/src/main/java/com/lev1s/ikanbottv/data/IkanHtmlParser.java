package com.lev1s.ikanbottv.data;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.lev1s.ikanbottv.data.IkanDataSource.GridPage;
import com.lev1s.ikanbottv.data.IkanDataSource.Section;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站点 HTML 解析器（纯 Java + Jsoup，不依赖 Android，可拿样本在 JVM 离线回归）。
 *
 * 全站只有两套条目模板（见 site-probe-report）：
 *   模板 A 网格卡：首页 .v-list、/hot/、/category/ —— a.item + img.lazy[data-src]
 *   模板 B 搜索卡：/search 的 .media 结构
 * 榜单页 /billboard.html 是第三种行式结构（#video-list > .item），单独解析。
 * 选择器全部集中在本类，站点改版时只改这里。
 */
public final class IkanHtmlParser {

    private static final Pattern PLAY_ID = Pattern.compile("/play/(\\d+)");
    private static final Pattern PAGE_IN_PATH = Pattern.compile("-p-(\\d+)\\.html");
    private static final Pattern PAGE_IN_QUERY = Pattern.compile("[?&]p=(\\d+)");

    private IkanHtmlParser() { }

    // ---------------- 首页 ----------------

    /** 首页：.v-list 板块（模板 A 海报卡）+ .article-list 口碑榜（名次文本条）。 */
    public static List<Section> parseHome(String html, String pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl);
        List<Section> out = new ArrayList<>();
        for (Element block : doc.select(".v-list")) {
            Section s = sectionFrom(block);
            for (Element a : block.select("a.item")) {
                VideoItem it = parseCardA(a);
                if (it != null) s.items.add(it);
            }
            if (!s.items.isEmpty()) out.add(s);
        }
        for (Element block : doc.select(".article-list")) {
            Section s = sectionFrom(block);
            for (Element li : block.select("ul li")) {
                VideoItem it = parseRankLi(li);
                if (it != null) s.items.add(it);
            }
            if (!s.items.isEmpty()) out.add(s);
        }
        return out;
    }

    private static Section sectionFrom(Element block) {
        Section s = new Section();
        Element h4 = block.selectFirst("h4");
        if (h4 != null) {
            s.title = h4.ownText().trim();
            Element more = h4.selectFirst("span a[href], a[href]");
            if (more != null) s.moreUrl = more.absUrl("href");
        }
        if (s.title == null || s.title.isEmpty()) s.title = "推荐";
        return s;
    }

    /** 首页口碑榜的一行：<li><span>名次</span><a href="/play/id 或 /search?q=词">标题</a></li> */
    private static VideoItem parseRankLi(Element li) {
        Element a = li.selectFirst("a[href]");
        if (a == null) return null;
        VideoItem it = new VideoItem();
        it.title = a.text().trim();
        if (it.title.isEmpty()) return null;
        String href = a.attr("href");
        Matcher m = PLAY_ID.matcher(href);
        if (m.find()) {
            it.videoId = Long.parseLong(m.group(1));
        } else if (href.contains("q=")) {
            // 指向站内搜索的条目：记住搜索词，点按时打开原生搜索结果
            String q = href.substring(href.indexOf("q=") + 2);
            int amp = q.indexOf('&');
            if (amp >= 0) q = q.substring(0, amp);
            try {
                it.searchQuery = java.net.URLDecoder.decode(q, "UTF-8");
            } catch (Exception e) {
                it.searchQuery = q;
            }
        } else {
            return null;
        }
        Element span = li.selectFirst("span");
        if (span != null) {
            try {
                it.rank = Integer.parseInt(span.text().trim());
            } catch (NumberFormatException ignored) { }
        }
        it.posterCandidates = PosterUrl.candidates(null, 0);
        return it;
    }

    // ---------------- 模板 A：网格列表页（/hot/、/category/） ----------------

    public static GridPage parseGrid(String html, String pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl);
        GridPage page = new GridPage();
        for (Element a : doc.select("a.item")) {
            VideoItem it = parseCardA(a);
            if (it != null) page.items.add(it);
        }
        page.nextPageUrl = findNextPage(doc, pageUrl);
        return page;
    }

    /** 模板 A 单卡：a.item[href=/play/id] > .cover-wp > img.lazy + <p>标题</p> */
    private static VideoItem parseCardA(Element a) {
        String href = a.attr("href");
        Matcher m = PLAY_ID.matcher(href);
        if (!m.find()) return null;
        VideoItem it = new VideoItem();
        it.videoId = Long.parseLong(m.group(1));
        Element img = a.selectFirst("img");
        String title = null;
        String posterRaw = null;
        if (img != null) {
            posterRaw = img.attr("data-src");
            title = img.attr("alt");
        }
        Element p = a.selectFirst("> p");
        if (p != null && !p.text().trim().isEmpty()) title = p.text().trim();
        it.title = title == null ? "" : title.trim();
        if (it.title.isEmpty()) return null;
        it.posterCandidates = PosterUrl.candidates(posterRaw, it.videoId);
        return it;
    }

    // ---------------- 模板 B：搜索结果页 ----------------

    public static GridPage parseSearch(String html, String pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl);
        GridPage page = new GridPage();
        for (Element media : doc.select("div.media")) {
            VideoItem it = parseCardB(media);
            if (it != null) page.items.add(it);
        }
        page.nextPageUrl = findNextPage(doc, pageUrl);
        return page;
    }

    /** 模板 B 单卡：.media > .media-left a.cover-link(img.media-pic) + .media-body */
    private static VideoItem parseCardB(Element media) {
        Element a = media.selectFirst("a.cover-link[href], .media-left a[href]");
        if (a == null) return null;
        Matcher m = PLAY_ID.matcher(a.attr("href"));
        if (!m.find()) return null;
        VideoItem it = new VideoItem();
        it.videoId = Long.parseLong(m.group(1));
        Element img = media.selectFirst("img.media-pic, img");
        String posterRaw = null;
        if (img != null) {
            posterRaw = img.attr("data-src");
            it.title = img.attr("alt");
        }
        Element titleA = media.selectFirst("a.title-text");
        if (titleA != null && !titleA.text().trim().isEmpty()) {
            it.title = titleA.text().trim();
        }
        if (it.title == null || it.title.isEmpty()) return null;
        // 副标题：media-body 里的 span.small（地区/别名、演职员两行）
        Elements smalls = media.select(".media-body span.small");
        StringBuilder sb = new StringBuilder();
        for (Element sp : smalls) {
            String t = sp.text().trim();
            if (!t.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(t);
            }
        }
        it.subtitle = sb.length() > 0 ? sb.toString() : null;
        it.posterCandidates = PosterUrl.candidates(posterRaw, it.videoId);
        return it;
    }

    // ---------------- 榜单页 ----------------

    /** /billboard.html：一页拼多个榜单，每榜是 div#video-list（id 重复出现），
     *  标题取同容器里的 h4；条目是行式 .item（名次 h3 + 海报 img.cover + 详情）。 */
    public static List<Section> parseBillboard(String html, String pageUrl) {
        Document doc = Jsoup.parse(html, pageUrl);
        List<Section> out = new ArrayList<>();
        for (Element list : doc.select("div#video-list")) {
            Section s = new Section();
            Element scope = list.parent();
            Element h4 = scope != null ? scope.selectFirst("> h4") : null;
            if (h4 == null && scope != null && scope.parent() != null) {
                h4 = scope.parent().selectFirst("h4");
            }
            s.title = h4 != null ? h4.ownText().trim() : "榜单";
            for (Element row : list.select("> .item, .item")) {
                VideoItem it = parseBillboardRow(row);
                if (it != null) s.items.add(it);
            }
            if (!s.items.isEmpty()) out.add(s);
        }
        return out;
    }

    private static VideoItem parseBillboardRow(Element row) {
        Element a = row.selectFirst("a[href]");
        if (a == null) return null;
        VideoItem it = new VideoItem();
        Matcher m = PLAY_ID.matcher(a.attr("href"));
        if (m.find()) {
            it.videoId = Long.parseLong(m.group(1));
        } else if (a.attr("href").contains("q=")) {
            // 与首页口碑榜同形态：该名次站内没有直达条目，链接指向搜索
            String href = a.attr("href");
            String q = href.substring(href.indexOf("q=") + 2);
            int amp = q.indexOf('&');
            if (amp >= 0) q = q.substring(0, amp);
            try {
                it.searchQuery = java.net.URLDecoder.decode(q, "UTF-8");
            } catch (Exception e) {
                it.searchQuery = q;
            }
        } else {
            return null;
        }
        Element img = row.selectFirst("img.cover, img");
        String posterRaw = null;
        if (img != null) {
            posterRaw = img.attr("data-src");
            it.title = img.attr("alt");
        }
        Element h5 = row.selectFirst("h5.title, h5");
        if (h5 != null && !h5.text().trim().isEmpty()) it.title = h5.text().trim();
        if (it.title == null || it.title.isEmpty()) return null;
        Element h3 = row.selectFirst("h3");
        if (h3 != null) {
            try {
                it.rank = Integer.parseInt(h3.text().trim());
            } catch (NumberFormatException ignored) { }
        }
        Element year = row.selectFirst(".meta.year");
        Element country = row.selectFirst(".meta.country");
        StringBuilder sb = new StringBuilder();
        if (year != null && !year.text().trim().isEmpty()) sb.append(year.text().trim());
        if (country != null && !country.text().trim().isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(country.text().trim());
        }
        it.subtitle = sb.length() > 0 ? sb.toString() : null;
        it.posterCandidates = PosterUrl.candidates(posterRaw, it.videoId);
        return it;
    }

    // ---------------- 分页 ----------------

    /**
     * 下一页 = 页内分页链接里页码恰为「当前页 + 1」的那条（取不到则取页码大于
     * 当前页的最小一条）。三种分页形态统一处理：
     *   /hot/ 路径式 -p-{n}.html、/category/ 查询式 ?p={n}、/search 游标式 &p={n}&n=…
     * 搜索的 n 游标不可构造，只能这样跟随页内链接——本方法正是为此。
     */
    static String findNextPage(Document doc, String pageUrl) {
        int cur = pageOf(pageUrl);
        String best = null;
        int bestPage = Integer.MAX_VALUE;
        for (Element a : doc.select("a[href]")) {
            String href = a.attr("href");
            int p = pageOf(href);
            if (p > cur && p < bestPage) {
                bestPage = p;
                best = a.absUrl("href");
            }
        }
        return best;
    }

    private static int pageOf(String url) {
        if (url == null) return 1;
        Matcher m = PAGE_IN_PATH.matcher(url);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = PAGE_IN_QUERY.matcher(url);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 1;
    }
}
