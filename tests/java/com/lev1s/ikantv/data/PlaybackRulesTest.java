package com.lev1s.ikantv.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class PlaybackRulesTest {

    @Test
    public void rejectsPrematureAndUnknownEndsButAcceptsFinalTimeSamples() {
        assertFalse(PlaybackRules.isAtMediaEnd(-1, 60_000));
        assertFalse(PlaybackRules.isAtMediaEnd(60_000, -1));
        assertFalse(PlaybackRules.isAtMediaEnd(0, 0));
        assertFalse(PlaybackRules.isAtMediaEnd(5_000, 60_000));
        assertFalse(PlaybackRules.isAtMediaEnd(57_999, 60_000));
        assertTrue(PlaybackRules.isAtMediaEnd(58_000, 60_000));
        assertFalse(PlaybackRules.isAtMediaEnd(593_999, 600_000));
        assertTrue(PlaybackRules.isAtMediaEnd(594_000, 600_000));
        assertFalse(PlaybackRules.isAtMediaEnd(10_109_999, 10_140_000));
        assertTrue(PlaybackRules.isAtMediaEnd(10_110_000, 10_140_000));
    }

    @Test
    public void mergesLeadingZeroAndMixedEpisodeNamesAcrossLines() {
        Map<String, String> chineseLine = new LinkedHashMap<>();
        Map<String, String> englishLine = new LinkedHashMap<>();
        for (int i = 1; i <= 13; i++) {
            chineseLine.put(String.format("第%02d集", i), "https://a.test/ep" + i + ".m3u8");
            String alias = i == 1 ? "Episode 01" : "EP" + String.format("%02d", i);
            englishLine.put(alias, "https://b.test/ep" + i + ".m3u8");
        }

        PlaybackCatalog.Catalog catalog = PlaybackCatalog.fromLines(
                lines(chineseLine, englishLine), false);

        assertEquals(13, catalog.episodes.size());
        assertEquals("第1集", catalog.episodes.get(0));
        assertEquals("第2集", catalog.episodes.get(1));
        assertEquals("第3集", catalog.episodes.get(2));
        assertEquals("第1集", catalog.canonicalEpisode("第01集"));
        assertEquals("第1集", catalog.canonicalEpisode("Episode 01"));
        assertEquals("第2集", catalog.canonicalEpisode("EP02"));
        assertEquals("https://a.test/ep2.m3u8", catalog.lineUrls.get(0).get("第2集"));
        assertEquals("https://b.test/ep2.m3u8", catalog.lineUrls.get(1).get("第2集"));
        assertEquals(26, catalog.legacyActiveEpisodes.size());
        assertEquals("第2集", catalog.resolveLegacyActiveEpisode(
                catalog.legacyActiveEpisodes.indexOf("EP02")));
    }

    @Test
    public void keepsSeasonsAndEpisodePartsSeparate() {
        Map<String, String> line = new LinkedHashMap<>();
        line.put("第01集", "https://a.test/bare.m3u8");
        line.put("第1集 上", "https://a.test/up.m3u8");
        line.put("第1集 下", "https://a.test/down.m3u8");
        line.put("S01E01", "https://a.test/s1.m3u8");
        line.put("S02E01", "https://a.test/s2.m3u8");

        PlaybackCatalog.Catalog catalog = PlaybackCatalog.fromLines(lines(line), false);

        assertEquals(5, catalog.episodes.size());
        assertEquals("第1集", catalog.episodes.get(0));
        assertEquals("第1集 上", catalog.episodes.get(1));
        assertEquals("第1集 下", catalog.episodes.get(2));
        assertEquals("第1季第1集", catalog.canonicalEpisode("S01E01"));
        assertEquals("第2季第1集", catalog.canonicalEpisode("S02E01"));
        assertEquals("第2季第1集", catalog.canonicalEpisode("第2季第01集"));
        assertEquals("第1集 上", catalog.canonicalEpisode("第01集（上）"));
        assertEquals("第1集 下", catalog.canonicalEpisode("第1集下"));
    }

    @Test
    public void keepsMovieVersionsSeparate() {
        Map<String, String> firstLine = new LinkedHashMap<>();
        firstLine.put("正片", "https://a.test/full.m3u8");
        firstLine.put("720P", "https://a.test/720.m3u8");
        firstLine.put("1080P", "https://a.test/1080.m3u8");
        Map<String, String> secondLine = new LinkedHashMap<>();
        secondLine.put("正片", "https://b.test/full.m3u8");

        PlaybackCatalog.Catalog catalog = PlaybackCatalog.fromLines(
                lines(firstLine, secondLine), true);

        assertEquals(3, catalog.episodes.size());
        assertTrue(catalog.episodes.contains("正片"));
        assertTrue(catalog.episodes.contains("720P"));
        assertTrue(catalog.episodes.contains("1080P"));
        assertNull(catalog.canonicalEpisode("1080"));
    }

    @Test
    public void migratesBase64NamesAndOldRichestLineIndexes() {
        Map<String, String> oldRichest = new LinkedHashMap<>();
        oldRichest.put("第1集", "https://a.test/1.m3u8");
        oldRichest.put("第2集", "https://a.test/2.m3u8");
        oldRichest.put("第3集", "https://a.test/3.m3u8");
        Map<String, String> newLine = new LinkedHashMap<>();
        newLine.put("第01集", "https://b.test/1.m3u8");
        newLine.put("第02集", "https://b.test/2.m3u8");
        PlaybackCatalog.Catalog catalog = PlaybackCatalog.fromLines(
                lines(oldRichest, newLine), false);

        String encodedOldName = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "第02集".getBytes(StandardCharsets.UTF_8));
        assertEquals("第2集", catalog.resolveSavedEpisode(encodedOldName, 0));
        assertEquals("第2集", catalog.resolveSavedEpisode(null, 1));
        assertEquals("https://b.test/2.m3u8", catalog.lineUrls.get(1).get("第2集"));
    }

    @Test
    public void parsesOnlyHlsResourceEntriesAndComputesSiteToken() {
        Map<String, String> urls = PlaybackRules.parseResourceUrls(
                " 第01集 $ https://cdn.test/one.m3u8?token=a#720P$https://cdn.test/720.mp4"
                        + "#备用$https://cdn.test/two.M3U8?x=1&y=2");

        assertEquals(2, urls.size());
        assertEquals("https://cdn.test/one.m3u8?token=a", urls.get("第01集"));
        assertEquals("https://cdn.test/two.M3U8?x=1&y=2", urls.get("备用"));
        assertEquals("23456789DEFGHIJKMNOPQRSTWXYZabcd",
                PlaybackRules.computeToken("987654321234",
                        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"));
        assertNull(PlaybackRules.computeToken("12x4", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
    }

    @SafeVarargs
    private static List<Map<String, String>> lines(Map<String, String>... maps) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> map : maps) result.add(map);
        return result;
    }
}
