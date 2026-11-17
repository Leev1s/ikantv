package com.lev1s.ikantv.data;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds one logical episode list and maps every playback line onto it. */
public final class PlaybackCatalog {

    private static final Pattern SEASON_EPISODE =
            Pattern.compile("(?i)\\bS0*(\\d{1,3})\\s*E0*(\\d+)\\b");
    private static final Pattern CHINESE_SEASON_EPISODE =
            Pattern.compile("第\\s*0*(\\d+)\\s*季.*?第\\s*0*(\\d+)\\s*(?:集|话|話|回|期)");
    private static final Pattern CHINESE_EPISODE =
            Pattern.compile("第\\s*0*(\\d+)\\s*(?:集|话|話|回|期)");
    private static final Pattern ENGLISH_EPISODE =
            Pattern.compile("(?i)\\b(?:episode|ep|e)\\s*0*(\\d+)\\b");
    private static final Pattern SUFFIX_EPISODE =
            Pattern.compile("(?i)(?<!\\d)0*(\\d+)\\s*(?:集|话|話|回|期|ep)(?:\\b|$)");

    private PlaybackCatalog() { }

    /**
     * Normalized catalog. {@code legacyEpisodes} is the old richest-line list,
     * kept in the old numeric order so pre-normalization resume indexes still
     * resolve to the same logical episode.
     */
    public static final class Catalog {
        public final List<String> episodes;
        public final List<String> legacyEpisodes;
        public final List<String> legacyActiveEpisodes;
        public final List<Map<String, String>> lineUrls;
        private final Map<String, String> displayByIdentity;
        private final boolean movie;

        private Catalog(List<String> episodes, List<String> legacyEpisodes,
                List<String> legacyActiveEpisodes,
                List<Map<String, String>> lineUrls, Map<String, String> displayByIdentity,
                boolean movie) {
            this.episodes = episodes;
            this.legacyEpisodes = legacyEpisodes;
            this.legacyActiveEpisodes = legacyActiveEpisodes;
            this.lineUrls = lineUrls;
            this.displayByIdentity = displayByIdentity;
            this.movie = movie;
        }

        /** Resolve a saved original line name, including its leading-zero aliases. */
        public String canonicalEpisode(String name) {
            if (name == null) return null;
            return displayByIdentity.get(identity(name, movie));
        }

        /**
         * Resolve the current resume format (URL-safe Base64 name) and the older
         * name-less index format. The index always uses the pre-merge line order.
         */
        public String resolveSavedEpisode(String encodedName, int legacyIndex) {
            if (encodedName != null && !encodedName.isEmpty()) {
                try {
                    String decoded = new String(Base64.getUrlDecoder().decode(encodedName),
                            StandardCharsets.UTF_8);
                    String resolved = canonicalEpisode(decoded);
                    if (resolved != null) return resolved;
                } catch (IllegalArgumentException ignored) { }
            }
            if (legacyIndex < 0 || legacyIndex >= legacyEpisodes.size()) return null;
            return canonicalEpisode(legacyEpisodes.get(legacyIndex));
        }

        /** Resolve the pre-normalization active_player episode index from the Home intent. */
        public String resolveLegacyActiveEpisode(int legacyIndex) {
            if (legacyIndex < 0 || legacyIndex >= legacyActiveEpisodes.size()) return null;
            return canonicalEpisode(legacyActiveEpisodes.get(legacyIndex));
        }
    }

    /**
     * @param sourceLines one raw name-to-URL map per source, in source order
     * @param movie movie/version pages keep each distinct label as its own item
     */
    public static Catalog fromLines(List<? extends Map<String, String>> sourceLines,
            boolean movie) {
        List<Map<String, String>> lines = new ArrayList<>();
        Map<String, Group> groups = new LinkedHashMap<>();
        List<String> legacyEpisodes = new ArrayList<>();
        Set<String> legacyUnion = new HashSet<>();

        int richestLine = -1;
        int richestSize = -1;
        for (int lineIndex = 0; lineIndex < sourceLines.size(); lineIndex++) {
            Map<String, String> sourceLine = sourceLines.get(lineIndex);
            Map<String, String> line = sourceLine == null ? Collections.emptyMap() : sourceLine;
            if (line.size() > richestSize) {
                richestSize = line.size();
                richestLine = lineIndex;
            }
            List<String> names = new ArrayList<>(line.keySet());
            names.sort(Comparator.nullsFirst(String::compareTo));
            for (String rawName : names) {
                if (rawName == null || rawName.trim().isEmpty()) continue;
                legacyUnion.add(rawName);
                String id = identity(rawName, movie);
                Group group = groups.get(id);
                if (group == null) {
                    group = new Group(id, displayName(rawName, movie), movie ? null : episode(rawName));
                    groups.put(id, group);
                }
            }
            lines.add(line);
        }

        if (richestLine >= 0) {
            Map<String, String> richest = sourceLines.get(richestLine);
            if (richest != null) legacyEpisodes.addAll(richest.keySet());
            legacyEpisodes.sort(PlaybackCatalog::compareLegacyEpisodes);
        }
        List<String> legacyActiveEpisodes = new ArrayList<>(legacyUnion);
        legacyActiveEpisodes.sort(PlaybackCatalog::compareLegacyEpisodes);

        List<Group> ordered = new ArrayList<>(groups.values());
        ordered.sort((a, b) -> compareGroups(a, b, movie));
        List<String> episodes = new ArrayList<>(ordered.size());
        Map<String, String> displayByIdentity = new LinkedHashMap<>();
        for (Group group : ordered) {
            episodes.add(group.display);
            displayByIdentity.put(group.identity, group.display);
        }

        List<Map<String, String>> normalizedLines = new ArrayList<>(lines.size());
        for (Map<String, String> line : lines) {
            Map<String, String> normalized = new LinkedHashMap<>();
            List<Map.Entry<String, String>> entries = new ArrayList<>(line.entrySet());
            entries.sort((a, b) -> compareAliases(a.getKey(), b.getKey(), displayByIdentity, movie));
            for (Map.Entry<String, String> entry : entries) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) continue;
                String display = displayByIdentity.get(identity(entry.getKey(), movie));
                if (display != null) normalized.putIfAbsent(display, entry.getValue());
            }
            normalizedLines.add(normalized);
        }

        return new Catalog(episodes, legacyEpisodes, legacyActiveEpisodes,
                normalizedLines, displayByIdentity, movie);
    }

    private static int compareAliases(String a, String b, Map<String, String> displayByIdentity,
            boolean movie) {
        if (a == null) return b == null ? 0 : 1;
        if (b == null) return -1;
        String displayA = displayByIdentity.get(identity(a, movie));
        String displayB = displayByIdentity.get(identity(b, movie));
        boolean canonicalA = clean(a).equals(displayA);
        boolean canonicalB = clean(b).equals(displayB);
        if (canonicalA != canonicalB) return canonicalA ? -1 : 1;
        int length = Integer.compare(clean(a).length(), clean(b).length());
        return length != 0 ? length : a.compareTo(b);
    }

    private static String identity(String name, boolean movie) {
        String clean = clean(name);
        if (!movie) {
            EpisodeRef episode = episode(clean);
            if (episode != null) return episode.identity();
        }
        return (movie ? "movie:" : "name:") + clean.toLowerCase(Locale.ROOT);
    }

    private static String displayName(String name, boolean movie) {
        String clean = clean(name);
        EpisodeRef episode = movie ? null : episode(clean);
        return episode == null ? clean : episode.displayName();
    }

    private static String clean(String name) {
        return name == null ? "" : name.replaceAll("\\s+", " ").trim();
    }

    private static EpisodeRef episode(String name) {
        Matcher m = SEASON_EPISODE.matcher(name);
        if (m.find()) return seasonEpisode(m, 1, 2, name);
        m = CHINESE_SEASON_EPISODE.matcher(name);
        if (m.find()) return seasonEpisode(m, 1, 2, name);
        m = CHINESE_EPISODE.matcher(name);
        if (m.find()) return bareEpisode(m, name);
        m = ENGLISH_EPISODE.matcher(name);
        if (m.find()) return bareEpisode(m, name);
        m = SUFFIX_EPISODE.matcher(name);
        return m.find() ? bareEpisode(m, name) : null;
    }

    private static EpisodeRef seasonEpisode(Matcher m, int seasonGroup, int numberGroup,
            String name) {
        try {
            return new EpisodeRef(Integer.parseInt(m.group(seasonGroup)),
                    Integer.parseInt(m.group(numberGroup)), partMarker(name.substring(m.end())));
        } catch (NumberFormatException ignored) { return null; }
    }

    private static EpisodeRef bareEpisode(Matcher m, String name) {
        try {
            return new EpisodeRef(null, Integer.parseInt(m.group(1)),
                    partMarker(name.substring(m.end())));
        } catch (NumberFormatException ignored) { return null; }
    }

    private static String partMarker(String remainder) {
        Matcher m = Pattern.compile("(?i)^\\s*[-:：]?\\s*[（(【\\[]?\\s*"
                + "(上半|下半|上集|下集|上|下|前篇|后篇|前|后|part\\s*\\d+)"
                + "\\s*[）)】\\]]?").matcher(remainder);
        if (!m.find()) return "";
        String part = m.group(1).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if ("上集".equals(part)) return "上";
        if ("下集".equals(part)) return "下";
        return part;
    }

    private static int compareGroups(Group a, Group b, boolean movie) {
        if (movie) return compareLegacyEpisodes(a.display, b.display);
        if (a.episode != null && b.episode != null) {
            int season = compareNullable(a.episode.season, b.episode.season);
            if (season != 0) return season;
            int number = Integer.compare(a.episode.number, b.episode.number);
            if (number != 0) return number;
            int part = Integer.compare(partOrder(a.episode.part), partOrder(b.episode.part));
            if (part != 0) return part;
            return a.episode.part.compareTo(b.episode.part);
        }
        if (a.episode != null) return -1;
        if (b.episode != null) return 1;
        return a.display.compareToIgnoreCase(b.display);
    }

    private static int compareNullable(Integer a, Integer b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        return Integer.compare(a, b);
    }

    private static int partOrder(String part) {
        if (part.isEmpty()) return 0;
        if ("上".equals(part) || "前".equals(part) || "前篇".equals(part)
                || part.startsWith("part 1")) return 1;
        if ("下".equals(part) || "后".equals(part) || "后篇".equals(part)
                || part.startsWith("part 2")) return 2;
        return 3;
    }

    // Match the sort used by PlayerActivity before logical episodes were merged.
    private static int compareLegacyEpisodes(String a, String b) {
        int na = firstNumber(a), nb = firstNumber(b);
        if (na != nb) return Integer.compare(na, nb);
        boolean za = a != null && a.contains("正片");
        boolean zb = b != null && b.contains("正片");
        if (za != zb) return za ? -1 : 1;
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static int firstNumber(String name) {
        if (name == null) return Integer.MAX_VALUE;
        Matcher m = Pattern.compile("\\d+").matcher(name);
        if (!m.find()) return Integer.MAX_VALUE;
        try { return Integer.parseInt(m.group()); }
        catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
    }

    private static final class Group {
        final String identity;
        final String display;
        final EpisodeRef episode;

        Group(String identity, String display, EpisodeRef episode) {
            this.identity = identity;
            this.display = display;
            this.episode = episode;
        }
    }

    private static final class EpisodeRef {
        final Integer season;
        final int number;
        final String part;

        EpisodeRef(Integer season, int number, String part) {
            this.season = season;
            this.number = number;
            this.part = part;
        }

        String identity() {
            return "episode:" + (season == null ? "?" : season) + ":" + number + ":" + part;
        }

        String displayName() {
            return (season == null ? "" : "第" + season + "季") + "第" + number + "集"
                    + (part.isEmpty() ? "" : " " + part);
        }
    }
}
