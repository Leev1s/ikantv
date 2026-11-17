package com.lev1s.ikantv.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IkanHtmlParserTest {

    @Test
    public void distinguishesExplicitEmptySearchFromBrokenSearchMarkup() throws Exception {
        String emptyHtml = "<html><body><h1>搜索：不存在的片名</h1>"
                + "<span>没有搜索到结果，请更换关键词</span></body></html>";
        IkanDataSource.GridPage empty = IkanHtmlParser.parseSearch(emptyHtml,
                IkanDataSource.BASE + "/search?q=missing");

        assertTrue(empty.searchNoResults);
        assertEquals(0, IkanDataSource.checkSearchPage(empty).items.size());

        IkanDataSource.GridPage malformed = IkanHtmlParser.parseSearch(
                "<html><body>服务暂时不可用</body></html>",
                IkanDataSource.BASE + "/search?q=missing");
        assertFalse(malformed.searchNoResults);
        try {
            IkanDataSource.checkSearchPage(malformed);
        } catch (FetchException e) {
            assertEquals(FetchException.Kind.PARSE_EMPTY, e.kind);
            return;
        }
        throw new AssertionError("A blank search response must remain a parse failure");
    }

    @Test
    public void recognizesChallengePageWithoutMatchingOrdinaryContent() {
        assertTrue(HttpFetcher.isChallenge(
                "<title>Just a moment...</title><script src='https://challenges.cloudflare.com/a.js'></script>"));
        assertTrue(HttpFetcher.isChallenge("cf-challenge-running"));
        assertFalse(HttpFetcher.isChallenge("<title>搜索：电影</title><p>没有搜索到结果</p>"));
    }

    @Test
    public void followsSearchPaginationLinkWithOpaqueCursorUnchanged() {
        String current = "https://www.ikanbot.com/search?q=%E5%BA%86&p=1";
        String html = "<html><body><div class='media'><div class='media-left'>"
                + "<a class='cover-link' href='/play/123'><img class='media-pic' alt='庆余年'></a>"
                + "</div><div class='media-body'><a class='title-text' href='/play/123'>庆余年</a>"
                + "</div></div><div class='pagination'><a href="
                + "'/search?q=%E5%BA%86&amp;p=2&amp;n=Q1UBABl%2Fcursor%3D'>下一页</a>"
                + "</div></body></html>";

        IkanDataSource.GridPage page = IkanHtmlParser.parseSearch(html, current);

        assertEquals(1, page.items.size());
        assertNotNull(page.nextPageUrl);
        assertEquals("https://www.ikanbot.com/search?q=%E5%BA%86&p=2&n=Q1UBABl%2Fcursor%3D",
                page.nextPageUrl);
    }
}
