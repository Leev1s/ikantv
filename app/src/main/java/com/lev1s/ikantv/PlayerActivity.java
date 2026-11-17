package com.lev1s.ikantv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lev1s.ikantv.data.PlaybackCatalog;
import com.lev1s.ikantv.data.PlaybackRules;

import org.json.JSONArray;
import org.json.JSONObject;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原生播放器：针对这台 Amlogic 盒子（Android 9）用 libVLC 内核播 HLS——
 * 支持站点常见的 AES-128 加密 HLS（系统 MediaPlayer 不支持、会直接报错），
 * 解复用容错也远好于 WebView 的 MSE；视频走 MediaCodec 硬解。
 * 数据（选集 × 线路 × m3u8）优先原生直取：在本地算出令牌、两次普通
 * HTTPS 请求调 /api/getResN 拿到（见 fetchLinesNative）；直取失败
 * （字段缺失 / Cloudflare 校验页 / 站点改了令牌算法）才回退到应用内
 * 隐藏 WebView 路线——在站点页面上下文里调同一 API，令牌和校验都
 * 由页面自己处理，壳只消费结果。
 *
 * 遥控键位（极简）：
 *   OK = 播放/暂停（长按切换倍速）  ◀ ▶ = 快退/快进 30 秒（连按加速）
 *   ▲ = 选集面板   ▼ / 菜单 = 换源面板   返回 = 关面板 / 退出播放
 */
public class PlayerActivity extends Activity {

    private static final String TAG = "IkanPlayer";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    public static final String EXTRA_URL = "play_url";

    // ---------- 数据模型 ----------
    static class LineData {
        String label;                       // 线路1、线路2…
        Map<String, String> urls = new HashMap<>();  // 集名 -> m3u8
    }

    private String playUrl;
    private String videoId = "";
    private String videoTitle = "";
    private boolean isMovie = false;
    private final List<String> episodes = new ArrayList<>();
    private PlaybackCatalog.Catalog episodeCatalog;
    private final List<LineData> lines = new ArrayList<>();
    private boolean resumeStateRestored = false;
    private int curEp = 0;
    private int curLine = 0;
    private final Set<String> badSources = new HashSet<>();       // "ep|line"
    private final Map<Integer, Integer> lineLatency = new HashMap<>(); // line -> ms, -1=失败
    private final Map<Integer, Boolean> lineProbeDone = new HashMap<>();
    private final Set<String> lineProbesInFlight = new HashSet<>();
    private final Map<String, String> declaredQuality = new HashMap<>(); // 集名 + URL -> master 声明
    private final Map<String, Long> hostCooldown = new HashMap<>();
    private final Map<String, Integer> hostFailures = new HashMap<>();
    private final java.util.concurrent.Semaphore probePermit = new java.util.concurrent.Semaphore(1);
    public static final int LATENCY_FASTEST_MS = 100, LATENCY_FAST_MS = 300,
            LATENCY_NORMAL_MS = 700, LATENCY_SLOW_MS = 1500;

    public static String latencyGrade(int ms) {
        if (ms < 0) return "未连通";
        if (ms < LATENCY_FASTEST_MS) return "飞快";
        if (ms < LATENCY_FAST_MS) return "快";
        if (ms < LATENCY_NORMAL_MS) return "一般";
        if (ms < LATENCY_SLOW_MS) return "慢";
        return "很慢";
    }

    /** 主线程调用；每次打开面板取一次，返回的原始线路索引列表不随测速回调变化。 */
    public List<Integer> snapshotLineOrder() {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) order.add(i);
        final String ep = episodes.get(curEp);
        order.sort((a, b) -> {
            int ca = lineSortCategory(a, ep), cb = lineSortCategory(b, ep);
            if (ca != cb) return Integer.compare(ca, cb);
            if (ca == 0) {
                int c = Integer.compare(lineLatency.get(a), lineLatency.get(b));
                if (c != 0) return c;
            }
            return Integer.compare(a, b);
        });
        return order;
    }

    private int lineSortCategory(int index, String ep) {
        if (!lines.get(index).urls.containsKey(ep)) return 3;
        if (badSources.contains(curEp + "|" + index)) return 2;
        Integer ms = lineLatency.get(index);
        return ms == null ? 1 : ms < 0 ? 2 : 0;
    }

    /** 主线程调用；无 master 声明时返回 null。 */
    public String getDeclaredQuality(int line) {
        if (episodes.isEmpty() || line < 0 || line >= lines.size()) return null;
        String ep = episodes.get(curEp), url = lines.get(line).urls.get(ep);
        return declaredQuality.get(ep + "|" + url);
    }

    // ---------- 播放器（libVLC 现成播放器内核：HTTPS + AES-128 HLS + MediaCodec 硬解） ----------
    private volatile LibVLC libVlc;
    private volatile org.videolan.libvlc.MediaPlayer vp;
    private org.videolan.libvlc.util.VLCVideoLayout surface;
    private boolean videoViewsAttached = false;
    private boolean dataReady = false;
    private volatile boolean prepared = false;
    private volatile boolean userPaused = false;
    private volatile boolean foreground = false;
    private volatile boolean pendingStart = false;
    private volatile long pendingSeekMs = -1;
    private int resumeRetryGeneration = -1;
    private boolean suppressOldSave = false;
    private static final int RESUME_SEEK_ATTEMPTS = 30;
    private static final long RESUME_SEEK_INTERVAL_MS = 1000;
    private int speedIdx = 0;
    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f};

    // ---------- UI ----------
    private View loadingOverlay;
    private TextView loadingText;
    private View topBar;
    private TextView titleText, subText;
    private TextView playerClock, videoStats, audioStats;
    private int statsTick = 0, statsVisibilityEpoch = 0;
    private PlaybackStatsSnapshot visibleStats;
    private View controller;
    private SeekBar seekBar;
    private TextView timeText;
    private TextView centerIndicator;
    private TextView osdText;
    private View panel;
    private TextView panelTitle;
    private GridView epGrid;
    private ListView lineList;
    private LineAdapter lineAdapter;
    private List<Integer> lineDisplayOrder = new ArrayList<>();
    private boolean panelOpen = false;
    private boolean panelEpMode = true;

    private WebView harvestView;
    private int cfRetries = 0;
    private boolean harvestDone = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService probePool = Executors.newFixedThreadPool(4);
    // 播放器串行执行器（H1）：会阻塞的 libVLC 调用（stop/setMedia/play/release）
    // 只在这里单线程串行执行，主线程永不直接 stop——3.x 的 stop() 同步阻塞
    // 到输入线程退出，正是历史「切源 ANR」的机制。
    private final ExecutorService playerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "vlc-player");
        th.setDaemon(true);
        return th;
    });
    // 每次切换绑定独立 listener；stop 完成后才安装新会话 listener。
    private final AtomicInteger playGeneration = new AtomicInteger(0);
    private final AtomicInteger playerIntentGeneration = new AtomicInteger(0);
    private final AtomicInteger speedIntentGeneration = new AtomicInteger(0);
    private final AtomicBoolean positionSamplePending = new AtomicBoolean(false);
    private volatile boolean playerReleased = false;
    private volatile int playingGeneration = -1;
    private volatile int confirmedGeneration = -1;
    private Runnable delayedPlayError;
    /** Position and duration are one immutable, generation-scoped sample. */
    private static final class PositionSnapshot {
        final int generation;
        final long positionMs;
        final long durationMs;
        PositionSnapshot(int generation, long positionMs, long durationMs) {
            this.generation = generation;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }
    private final Object positionSnapshotLock = new Object();
    private volatile PositionSnapshot positionSnapshot = new PositionSnapshot(-1, -1, -1);
    // 播放器里实际在播的集/线（setMedia 时更新）：saveState 以它为准，
    // 切集先改 curEp 也不会把旧集位置写到新集名下（M1）。
    private volatile int playingEp = -1;
    private volatile int playingLine = -1;
    // 自动换线节流（H2）：上次自动换线时刻；手动切集/切线清零、不受限。
    private volatile long lastAutoSwitchAt = 0;
    private SharedPreferences prefs;

    private final Runnable hideChrome = () -> {
        topBar.setVisibility(View.GONE);
        controller.setVisibility(View.GONE);
        playerClock.setVisibility(View.GONE);
        visibleStats = null;
        statsVisibilityEpoch++;
        resetPlaybackStatsBaseline();
    };
    private final Runnable hideIndicator = () -> centerIndicator.setVisibility(View.GONE);
    private final Runnable hideOsd = () -> osdText.setVisibility(View.GONE);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            requestPositionSample();
            updateProgress();
            if (++statsTick % 2 == 0 && (topBar.getVisibility() == View.VISIBLE
                    || (panelOpen && !panelEpMode))) updatePlaybackStats();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_player);
        prefs = getSharedPreferences("player", MODE_PRIVATE);

        playUrl = getIntent().getStringExtra(EXTRA_URL);
        if (playUrl == null) playUrl = "https://www.ikanbot.com/";

        surface = findViewById(R.id.surface);
        loadingOverlay = findViewById(R.id.loading_overlay);
        loadingText = findViewById(R.id.loading_text);
        topBar = findViewById(R.id.top_bar);
        titleText = findViewById(R.id.title_text);
        subText = findViewById(R.id.sub_text);
        playerClock = findViewById(R.id.player_clock);
        videoStats = findViewById(R.id.video_stats);
        audioStats = findViewById(R.id.audio_stats);
        controller = findViewById(R.id.controller);
        seekBar = findViewById(R.id.seek_bar);
        timeText = findViewById(R.id.time_text);
        centerIndicator = findViewById(R.id.center_indicator);
        osdText = findViewById(R.id.osd_text);
        panel = findViewById(R.id.panel);
        panelTitle = findViewById(R.id.panel_title);
        epGrid = findViewById(R.id.ep_grid);
        lineList = findViewById(R.id.line_list);

        epGrid.setOnItemClickListener((parent, view, position, id) -> {
            closePanel();
            if (position != curEp) switchEpisode(position, true);
        });
        lineList.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= lineDisplayOrder.size()) return;
            int line = lineDisplayOrder.get(position);
            if (!lines.get(line).urls.containsKey(episodes.get(curEp))) return;
            closePanel();
            if (line != curLine) switchLine(line, true, safePos());
        });

        setLoading(true, "正在打开播放页，获取线路…");
        startHarvest();
        handler.post(tick);
    }

    // ================= 数据获取（原生直取优先，WebView harvest 回退） =================

    /**
     * 原生直取：token 算法已逆向（见 research/site-probe-report.md 并经主线 live 复验）——
     * 只依赖播放页的 current_id 与 e_token 两个隐藏字段，无 cookie 绑定。
     * 两次普通 HTTPS 请求即可拿到全部线路（比加载整个播放页快得多）；
     * 任何失败（字段缺失 / CF 校验页 / state=-401=算法被站点改掉）都回退 WebView 路线。
     */
    private void startHarvest() {
        probePool.execute(() -> {
            JSONObject o = null;
            try { o = fetchLinesNative(); } catch (Exception e) {
                Log.w(TAG, "native fetch fail", e);
            }
            if (o != null) {
                final JSONObject fo = o;
                handler.post(() -> onHarvestJson(fo.toString()));
            } else {
                handler.post(() -> { if (!playerReleased && !harvestDone) startWebViewHarvest(); });
            }
        });
    }

    private JSONObject fetchLinesNative() throws Exception {
        String html = httpGet(playUrl, null);
        if (html == null || html.contains("Just a moment")) return null;
        String cid = findField(html, "current_id");
        String et = findField(html, "e_token");
        String mt = findField(html, "mtype");
        if (cid == null || et == null) return null;
        if (mt == null) mt = "1";
        String tk = PlaybackRules.computeToken(cid, et);
        if (tk == null) return null;
        String api = "https://www.ikanbot.com/api/getResN?videoId=" + cid
                + "&mtype=" + mt + "&token=" + tk;
        String body = httpGet(api, playUrl);
        if (body == null) return null;
        JSONObject j = new JSONObject(body);
        if (j.optInt("state", -1) != 1) {
            Log.w(TAG, "native api state=" + j.optInt("state", -1) + "（回退 WebView）");
            return null;
        }
        JSONArray list = j.getJSONObject("data").getJSONArray("list");
        JSONArray linesArr = new JSONArray();
        boolean hasPlayableUrl = false;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            JSONObject urls = new JSONObject();
            try {
                JSONArray res = new JSONArray(item.optString("resData", "[]"));
                for (int k = 0; k < res.length(); k++) {
                    String u = res.getJSONObject(k).optString("url", "");
                    for (Map.Entry<String, String> entry : PlaybackRules.parseResourceUrls(u).entrySet()) {
                        urls.put(entry.getKey(), entry.getValue());
                        hasPlayableUrl = true;
                    }
                }
            } catch (Exception ignored) { }
            JSONObject lo = new JSONObject();
            lo.put("id", item.optString("id", ""));
            lo.put("urls", urls);
            linesArr.put(lo);
        }
        if (!hasPlayableUrl) {
            Log.w(TAG, "native api has no playable lines; falling back to WebView");
            return null;
        }
        String title = "";
        java.util.regex.Matcher tm = java.util.regex.Pattern
                .compile("id=\"video_title\"[^>]*>([^<]+)<").matcher(html);
        if (tm.find()) title = tm.group(1).replaceAll("\\s+", " ").trim();
        JSONObject out = new JSONObject();
        out.put("title", title);
        out.put("mtype", mt);
        out.put("videoId", cid);
        out.put("lines", linesArr);
        return out;
    }

    private static String findField(String html, String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("id=\"" + id + "\" value=\"([^\"]*)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String httpGet(String url, String referer) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(9000);
            c.setRequestProperty("User-Agent", DESKTOP_UA);
            if (referer != null) c.setRequestProperty("Referer", referer);
            if (c.getResponseCode() != 200) {
                java.io.InputStream es = c.getErrorStream();
                if (es != null) {
                    try { es.close(); } catch (Exception ignored) { }
                }
                return null;
            }
            java.io.InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            try {
                while ((n = is.read(buf)) > 0) {
                    total += n;
                    if (total > 600_000) {
                        Log.w(TAG, "player response exceeds size limit");
                        return null;
                    }
                    bos.write(buf, 0, n);
                }
            } finally {
                // 读尽并关闭流后连接可回池复用，不要 disconnect（会销毁连接）
                is.close();
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private void startWebViewHarvest() {
        if (playerReleased) return;
        harvestView = new WebView(this);
        WebSettings s = harvestView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(DESKTOP_UA);
        s.setMediaPlaybackRequiresUserGesture(false);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(harvestView, true);
        harvestView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        harvestView.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                android.webkit.WebResourceResponse local =
                        AssetsInterceptor.intercept(PlayerActivity.this, request);
                return local != null ? local
                        : super.shouldInterceptRequest(view, request);
            }

            @Override public void onPageFinished(WebView view, String url) {
                handler.postDelayed(() -> {
                    if (playerReleased || harvestDone || harvestView == null) return;
                    harvestView.evaluateJavascript("document.title", value -> {
                        if (playerReleased || harvestView == null) return;
                        if (value != null && value.contains("Just a moment")) {
                            if (cfRetries < 3) {
                                cfRetries++;
                                setLoading(true, "网站正在校验，自动重试（" + cfRetries + "/3）…");
                                handler.postDelayed(() -> {
                                    if (!playerReleased && harvestView != null) harvestView.loadUrl(playUrl);
                                }, 4000);
                            } else {
                                setLoading(true, "网站校验暂时过不去，请返回后稍再再试");
                            }
                            return;
                        }
                        harvestView.evaluateJavascript(HARVEST_JS, null);
                        // 兜底超时
                        handler.postDelayed(() -> {
                            if (!playerReleased && !harvestDone) setLoading(true, "线路获取较慢，仍在等待…（可返回重试）");
                        }, 20000);
                    });
                }, 900);
            }
        });
        harvestView.loadUrl(playUrl);
    }

    private static final String HARVEST_JS =
            "(function(){"
            + "function send(o){try{AndroidBridge.onHarvest(JSON.stringify(o));}catch(e){}}"
            + "var idEl=document.getElementById('current_id');"
            + "if(!idEl){send({error:'no_id'});return;}"
            + "var id=idEl.value;"
            + "var mtEl=document.getElementById('mtype');var mt=mtEl?mtEl.value:'1';"
            + "var tEl=document.getElementById('video_title');"
            + "var title=tEl?tEl.textContent.replace(/\\s+/g,' ').trim():document.title;"
            + "var tk='';try{tk=(typeof v_tks!=='undefined'&&v_tks)?v_tks:'';}catch(e){}"
            + "fetch('/api/getResN?videoId='+encodeURIComponent(id)+'&mtype='+encodeURIComponent(mt)"
            + "+'&token='+encodeURIComponent(tk),{credentials:'include'})"
            + ".then(function(r){return r.json();})"
            + ".then(function(j){"
            + "  if(!j||j.state!=1||!j.data||!j.data.list){send({error:'api',state:j?j.state:-1});return;}"
            + "  var lines=[];"
            + "  j.data.list.forEach(function(item){"
            + "    var urls={};var res=null;"
            + "    try{res=eval(item.resData);}catch(e){}"
            + "    if(res){res.forEach(function(en){"
            + "      if(!en||!en.url)return;"
            + "      en.url.split('#').forEach(function(part){"
            + "        var kv=part.split('$');"
            + "        if(kv.length>1){var n=kv[0].replace(/\\s+/g,' ').trim();var u=kv[1].trim();"
            + "        if(u&&/\\.m3u8(\\?|$)/i.test(u))urls[n]=u;}"
            + "      });"
            + "    });}"
            + "    lines.push({id:item.id,urls:urls});"
            + "  });"
            + "  send({title:title,mtype:mt,videoId:id,lines:lines});"
            + "})"
            + ".catch(function(e){send({error:'fetch'});});"
            + "})();";

    private class Bridge {
        @JavascriptInterface
        public void onHarvest(String json) {
            handler.post(() -> onHarvestJson(json));
        }
    }

    private void onHarvestJson(String json) {
        if (playerReleased || harvestDone) return;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("error")) {
                Log.w(TAG, "harvest error: " + o.optString("error"));
                setLoading(true, "这部片子的线路没取到（" + o.optString("error") + "），请返回换一部或重试");
                return;
            }
            videoTitle = o.optString("title", "");
            videoId = o.optString("videoId", "");
            isMovie = "1".equals(o.optString("mtype", "1"));
            JSONArray arr = o.optJSONArray("lines");
            List<Map<String, String>> rawLineUrls = new ArrayList<>();
            List<String> rawLineLabels = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject urls = arr.getJSONObject(i).optJSONObject("urls");
                    Map<String, String> raw = new HashMap<>();
                    if (urls != null) {
                        java.util.Iterator<String> it = urls.keys();
                        while (it.hasNext()) {
                            String name = it.next();
                            String url = urls.optString(name, "");
                            if (!name.trim().isEmpty()
                                    && url.matches("(?i).*\\.m3u8(\\?.*)?$")) raw.put(name, url);
                        }
                    }
                    if (!raw.isEmpty()) {
                        rawLineUrls.add(raw);
                        rawLineLabels.add("线路" + (i + 1));
                    }
                }
            }
            if (rawLineUrls.isEmpty()) {
                lines.clear();
                setLoading(true, "这部片子暂时没有可用线路，请返回换一部");
                return;
            }
            episodeCatalog = PlaybackCatalog.fromLines(rawLineUrls, isMovie);
            lines.clear();
            episodes.clear();
            episodes.addAll(episodeCatalog.episodes);
            for (int i = 0; i < episodeCatalog.lineUrls.size(); i++) {
                LineData line = new LineData();
                line.label = rawLineLabels.get(i);
                line.urls.putAll(episodeCatalog.lineUrls.get(i));
                if (!line.urls.isEmpty()) lines.add(line);
            }
            if (lines.isEmpty()) {
                setLoading(true, "这部片子暂时没有可用线路，请返回换一部");
                return;
            }
            // 恢复上次进度（同片同站）
            restoreState();
            applyActivePlayerEntry();
            harvestDone = true;
            dataReady = true;
            if (harvestView != null) {
                harvestView.stopLoading();
                harvestView.destroy();
                harvestView = null;
            }
            titleText.setText(videoTitle);
            updateSubText();
            maybePrepare();
            probeAllLines();
        } catch (Exception e) {
            Log.e(TAG, "harvest parse fail", e);
            setLoading(true, "线路数据解析失败，请返回重试");
        }
    }

    private void restoreState() {
        resumeStateRestored = false;
        if (videoId.isEmpty()) return;
        String saved = prefs.getString("resume_" + videoId, null);
        if (saved == null) return;
        try {
            String[] p = saved.split("\\|");
            int ep = Integer.parseInt(p[0]);
            int ln = Integer.parseInt(p[1]);
            long pos = Long.parseLong(p[2]);
            String name = episodeCatalog == null ? null
                    : episodeCatalog.resolveSavedEpisode(p.length >= 4 ? p[3] : null, ep);
            int mapped = name == null ? -1 : episodes.indexOf(name);
            if (mapped < 0) return;
            curEp = mapped;
            resumeStateRestored = true;
            if (ln >= 0 && ln < lines.size()
                    && lines.get(ln).urls.containsKey(episodes.get(curEp))) curLine = ln;
            if (pos > 0) pendingSeekMs = pos;
        } catch (Exception ignored) { }
    }

    private void applyActivePlayerEntry() {
        Intent intent = getIntent();
        if (!intent.hasExtra("active_player_episode") || !intent.hasExtra("active_player_line")) return;
        int episode = intent.getIntExtra("active_player_episode", -1);
        int line = intent.getIntExtra("active_player_line", -1);
        int activeFormat = prefs.getInt("active_player_format", 0);
        if (activeFormat >= 1) {
            String savedName = episodeCatalog == null ? null
                    : episodeCatalog.canonicalEpisode(prefs.getString("active_player_episode_name", null));
            int mapped = savedName == null ? -1 : episodes.indexOf(savedName);
            if (mapped >= 0) episode = mapped;
        } else {
            // The old marker index came from the larger, unnormalized episode union.
            // A name-and-position resume record is stronger when it was recoverable.
            if (resumeStateRestored) return;
            String oldName = episodeCatalog == null ? null
                    : episodeCatalog.resolveLegacyActiveEpisode(episode);
            episode = oldName == null ? -1 : episodes.indexOf(oldName);
        }
        if (episode < 0 || episode >= episodes.size() || line < 0 || line >= lines.size()
                || !lines.get(line).urls.containsKey(episodes.get(episode))) return;
        if (episode != curEp) pendingSeekMs = -1;
        curEp = episode;
        curLine = line;
        String title = intent.getStringExtra("active_player_title");
        if (title != null && !title.trim().isEmpty()) videoTitle = title;
    }

    private void writeActivePlayer() {
        if (videoId.isEmpty() || playingEp < 0 || playingLine < 0) return;
        prefs.edit().putString("active_player", videoId + "|" + playingEp + "|" + playingLine)
                .putString("active_player_title", videoTitle)
                .putString("active_player_episode_name", episodes.get(playingEp))
                .putInt("active_player_format", 1).apply();
    }

    // 保存与 stop/setMedia 串行：身份和位置始终来自同一媒体会话。
    private void saveState() {
        if (!playerReleased && pendingSeekMs <= 0) playerExecutor.execute(this::saveStateOnPlayerThread);
    }

    private void saveStateOnPlayerThread() {
        if (videoId.isEmpty() || vp == null || playingEp < 0 || playingGeneration < 0
                || playingGeneration != confirmedGeneration || pendingSeekMs > 0) return;
        try {
            long pos = vp.getTime();
            if (pos < 0) return;
            String name = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    episodes.get(playingEp).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            prefs.edit().putString("resume_" + videoId,
                    playingEp + "|" + playingLine + "|" + pos + "|" + name).apply();
        } catch (Exception ignored) { }
    }

    // ================= 播放控制（libVLC） =================

    private String currentUrl() {
        if (episodes.isEmpty() || lines.isEmpty()) return null;
        return lines.get(curLine).urls.get(episodes.get(curEp));
    }

    private void maybePrepare() {
        if (!dataReady) return;
        String url = currentUrl();
        if (url == null) {
            // 当前线路没有这一集，找一条有的
            int alt = findLineForEpisode(curEp, -1);
            if (alt >= 0) { curLine = alt; url = currentUrl(); }
        }
        if (url == null) {
            setLoading(true, "所有线路都没有这一集，请返回");
            return;
        }
        playUrlInternal(url);
    }

    private void ensureVlc() {
        if (libVlc == null) {
            java.util.ArrayList<String> opts = new java.util.ArrayList<>();
            opts.add("--http-user-agent=" + DESKTOP_UA);
            opts.add("--http-referrer=https://www.ikanbot.com/");
            opts.add("--network-caching=2000");
            opts.add("--no-video-title-show");
            libVlc = new LibVLC(this, opts);
        }
        if (vp == null) {
            vp = new org.videolan.libvlc.MediaPlayer(libVlc);
        }
        attachVideoViews();
    }

    /** View plumbing is kept on the main thread and tied to the Activity window lifetime. */
    private void attachVideoViews() {
        if (playerReleased || !foreground || vp == null || surface == null || videoViewsAttached) return;
        try {
            // Clear a stale helper if Android destroyed the Surface before onPause ran.
            // MediaPlayer.detachViews() is safe when no helper is attached.
            vp.detachViews();
            // VLCVideoLayout installs VideoHelper to keep the video's DAR and surface geometry.
            vp.attachViews(surface, null, false, false);
            vp.setVideoScale(org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_BEST_FIT);
            videoViewsAttached = true;
            Log.i(TAG, "video surface attached");
        } catch (RuntimeException e) {
            videoViewsAttached = false;
            try { vp.detachViews(); }
            catch (RuntimeException cleanupError) {
                Log.e(TAG, "video surface attach cleanup failed", cleanupError);
            }
            Log.e(TAG, "video surface attach failed", e);
        }
    }

    /** Detach before the Activity window can destroy its SurfaceView. */
    private void detachVideoViews(String reason) {
        if (vp == null || !videoViewsAttached) return;
        videoViewsAttached = false;
        try {
            vp.detachViews();
            Log.i(TAG, "video surface detached: " + reason);
        } catch (RuntimeException e) {
            Log.e(TAG, "video surface detach failed: " + reason, e);
        }
    }

    private org.videolan.libvlc.MediaPlayer.EventListener listenerForSession(
            final int gen, final int sessionEp, final int sessionLine) {
        return event -> handler.post(() -> {
            if (vp == null || playerReleased) return;
            if (gen != playGeneration.get()) return;
            switch (event.type) {
                case org.videolan.libvlc.MediaPlayer.Event.Buffering: {
                    float pc = event.getBuffering();
                    if (!prepared && pc > 0 && pc < 100) {
                        loadingText.setText("正在缓冲：" + episodes.get(curEp) + " · "
                                + lines.get(curLine).label + "（" + (int) pc + "%）");
                    }
                    if (prepared && pc < 100) setLoading(true, "正在缓冲…（" + (int) pc + "%）");
                    if (prepared && pc >= 100) setLoading(false, "");
                    break;
                }
                case org.videolan.libvlc.MediaPlayer.Event.Playing: {
                    if (!foreground) {
                        // 已在途的 play 与 onPause 交错时，暂停命令仍在播放器线程串行执行。
                        playerExecutor.execute(() -> {
                            if (!foreground && !playerReleased && gen == playGeneration.get()
                                    && gen == playingGeneration && vp != null) vp.pause();
                        });
                    }
                    if (delayedPlayError != null) {
                        handler.removeCallbacks(delayedPlayError);
                        delayedPlayError = null;
                    }
                    confirmedGeneration = gen;
                    prefs.edit().putInt("lastline_" + videoId, playingLine).apply();
                    writeActivePlayer();
                    prepared = true;
                    setLoading(false, "");
                    applySpeed(gen);
                    if (pendingSeekMs > 0 && resumeRetryGeneration != gen) {
                        resumeRetryGeneration = gen;
                        showOsd("正在续播到 " + fmtTime(pendingSeekMs));
                        retryResumeSeek(gen, pendingSeekMs, RESUME_SEEK_ATTEMPTS);
                    }
                    saveState();
                    showChrome();
                    break;
                }
                case org.videolan.libvlc.MediaPlayer.Event.TimeChanged: {
                    long time = event.getTimeChanged();
                    if (time >= 0) updatePositionSnapshot(gen, time, -1);
                    if (pendingSeekMs > 0 && event.getTimeChanged() > 0
                            && Math.abs(event.getTimeChanged() - pendingSeekMs)
                                    <= Math.min(2000, Math.max(250, pendingSeekMs / 4))) {
                        pendingSeekMs = -1;
                        saveState();
                    }
                    break;
                }
                case org.videolan.libvlc.MediaPlayer.Event.EndReached: {
                    if (sessionEp != curEp || sessionLine != curLine
                            || sessionEp != playingEp || sessionLine != playingLine
                            || gen != playingGeneration) break;
                    PositionSnapshot snapshot = positionSnapshot;
                    long position = snapshot.generation == gen ? snapshot.positionMs : -1;
                    long duration = snapshot.generation == gen ? snapshot.durationMs : -1;
                    boolean confirmed = gen == confirmedGeneration;
                    boolean reachedEnd = PlaybackRules.isAtMediaEnd(position, duration);
                    Log.i(TAG, "EndReached gen=" + gen + " ep=" + sessionEp + " line="
                            + sessionLine + " isMovie=" + isMovie + " confirmed=" + confirmed
                            + " positionMs=" + position + " durationMs=" + duration);
                    if (!confirmed || !reachedEnd) {
                        onPlayError(confirmed ? "线路提前结束" : "线路未正常开始就结束");
                        break;
                    }
                    if (isMovie) {
                        showOsd("播放完毕");
                        showChrome();
                    } else if (sessionEp + 1 < episodes.size()) {
                        showOsd("本集播完，自动播放下一集");
                        // 延迟期内若已切集/切线（代际已变），过期动作作废
                        handler.postDelayed(() -> {
                            if (gen != playGeneration.get() || playerReleased
                                    || sessionEp != curEp || sessionLine != curLine
                                    || gen != playingGeneration || gen != confirmedGeneration) return;
                            switchEpisode(sessionEp + 1, false);
                        }, 1200);
                    } else {
                        showOsd("全部播完");
                        showChrome();
                    }
                    break;
                }
                case org.videolan.libvlc.MediaPlayer.Event.EncounteredError: {
                    Log.w(TAG, "VLC encountered error");
                    onPlayError("播放错误");
                    break;
                }
                default:
                    break;
            }
        });
    }

    private void retryResumeSeek(final int gen, final long target, final int attempts) {
        if (playerReleased || !foreground || gen != playGeneration.get() || pendingSeekMs != target) return;
        if (attempts <= 0) {
            Log.w(TAG, "resume seek unconfirmed; preserving saved position " + target);
            showOsd("续播位置恢复失败，已保留原进度");
            return;
        }
        playerExecutor.execute(() -> {
            if (isCurrentPlayerSession(gen) && foreground && pendingSeekMs == target
                    && vp.isSeekable()) {
                try { vp.setTime(target); } catch (Exception e) { Log.w(TAG, "resume seek", e); }
            }
        });
        handler.postDelayed(() -> retryResumeSeek(gen, target, attempts - 1), RESUME_SEEK_INTERVAL_MS);
    }

    private void playUrlInternal(String url) {
        if (playerReleased) return;
        if (delayedPlayError != null) {
            handler.removeCallbacks(delayedPlayError);
            delayedPlayError = null;
        }
        final int gen = playGeneration.incrementAndGet();
        final int intent = playerIntentGeneration.incrementAndGet();
        final int targetEp = curEp;
        final int targetLine = curLine;
        final boolean skipOldSave = suppressOldSave;
        suppressOldSave = false;
        Log.i(TAG, "play requested gen=" + gen + " ep=" + targetEp + " line=" + targetLine
                + " isMovie=" + isMovie);
        pendingStart = true;
        prepared = false;
        userPaused = false;
        resetPositionSnapshot(gen);
        setLoading(true, "正在缓冲：" + episodes.get(curEp) + " · " + lines.get(curLine).label + "…");
        updateSubText();
        if (!foreground) return;
        // 播放器创建与视图挂载留在主线程（VLCVideoLayout 属 View 体系）；
        // 会阻塞的 stop/setMedia/play 整段投串行执行器（H1）。
        ensureVlc();
        final org.videolan.libvlc.MediaPlayer player = vp;
        final LibVLC lc = libVlc;
        if (player == null || lc == null) return;
        playerExecutor.execute(() -> {
            // 已有更新一代在排队时本任务直接作废：不 stop、不换 Media，
            // 后一代任务完成真正的切换（串行队列保证最终态是最新一代）。
            if (gen != playGeneration.get() || intent != playerIntentGeneration.get()
                    || playerReleased || !foreground) return;
            try {
                // saveState 读 getTime：须在 stop 之前、与 stop 同任务。
                // 仅在确有旧媒体在播时才存（playingEp>=0）——首次播放时存量
                // 续播位置还没被消费，不能拿新播放器的 0 位置把它覆掉。
                if (!skipOldSave) saveStateOnPlayerThread();
                // 清掉旧 listener 及其排队事件；stop 同步等待旧输入线程退出。
                player.setEventListener(null);
                player.stop();
                playingGeneration = -1;
                if (gen != playGeneration.get() || intent != playerIntentGeneration.get()
                        || playerReleased || !foreground) return;
                Media media = new Media(lc, android.net.Uri.parse(url));
                // 硬解优先：MediaCodec（NDK→JNI），不行自动回退软解
                media.addOption(":codec=mediacodec_ndk,mediacodec_jni,any");
                media.addOption(":network-caching=2000");
                player.setMedia(media);
                media.release();
                playingEp = targetEp;
                playingLine = targetLine;
                playingGeneration = gen;
                player.setEventListener(listenerForSession(gen, targetEp, targetLine));
                if (gen != playGeneration.get() || intent != playerIntentGeneration.get()
                        || playerReleased || !foreground) return;
                pendingStart = false;
                player.play();
            } catch (Exception e) {
                Log.e(TAG, "play failed", e);
                handler.post(() -> {
                    if (gen == playGeneration.get() && !playerReleased) {
                        onPlayError("播放器异常");
                    }
                });
            }
        });
    }

    private void onPlayError(String reason) {
        Log.w(TAG, "playback failed gen=" + playGeneration.get() + " ep=" + curEp + " line="
                + curLine + " isMovie=" + isMovie + " reason=" + reason);
        // 自动换线节流（H2 / AGENTS 口径 1.5s）：窗口内不重复自动换线，
        // 防迟到事件或连环错误短时间内把线路表扫穿；手动切换清零、不受限。
        long now = SystemClock.uptimeMillis();
        if (lastAutoSwitchAt != 0 && now - lastAutoSwitchAt < 1500) {
            Log.w(TAG, "auto switch throttled: " + reason);
            if (delayedPlayError == null) {
                final int gen = playGeneration.get();
                delayedPlayError = () -> {
                    delayedPlayError = null;
                    if (!playerReleased && gen == playGeneration.get()) onPlayError(reason);
                };
                handler.postDelayed(delayedPlayError, 1500 - (now - lastAutoSwitchAt));
            }
            return;
        }
        lastAutoSwitchAt = now;
        badSources.add(curEp + "|" + curLine);
        lineLatency.put(curLine, -1);
        lineProbeDone.put(curLine, true);
        int next = findLineForEpisode(curEp, curLine);
        if (next >= 0) {
            Toast.makeText(this, lines.get(curLine).label + "播不了（" + reason + "），自动切换到"
                    + lines.get(next).label, Toast.LENGTH_LONG).show();
            long pos = safePos();
            // 刚换上的线路尚未续播成功时保留上一条线路的位置。
            if (pendingSeekMs <= 0 && pos > 0) pendingSeekMs = pos;
            curLine = next;
            playUrlInternal(currentUrl());
        } else {
            prepared = false;
            setLoading(true, "这一集的线路都试过了播不了，请按 ▼ 换源或返回换一部");
            openPanel(false);
        }
    }

    /** 找一条「有这一集且没被标记坏」的线路（从 from 的下一条开始绕一圈） */
    private int findLineForEpisode(int ep, int from) {
        String name = episodes.get(ep);
        for (int i = 1; i <= lines.size(); i++) {
            int idx = (from + i) % lines.size();
            if (lines.get(idx).urls.containsKey(name)
                    && !badSources.contains(ep + "|" + idx)) return idx;
        }
        return -1;
    }

    private void switchEpisode(int ep, boolean keepPos) {
        // 先存旧集续播状态再改 curEp（M1）；saveState 已以 playingEp 为准，
        // 这里的先行保存是第二层保险。切集同时清零自动换线节流。
        if (pendingSeekMs > 0) suppressOldSave = true;
        else saveState();
        lastAutoSwitchAt = 0;
        curEp = ep;
        lineLatency.clear();
        lineProbeDone.clear();
        pendingSeekMs = -1;
        // 优先沿用同一条线路，没有这一集就找一条有的
        if (!lines.get(curLine).urls.containsKey(episodes.get(ep))) {
            int alt = findLineForEpisode(ep, -1);
            if (alt < 0) {
                // 手动选集允许重试此前标坏的线路。
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).urls.containsKey(episodes.get(ep))) { alt = i; break; }
                }
            }
            if (alt >= 0) curLine = alt;
        }
        String url = currentUrl();
        if (url != null) playUrlInternal(url);
    }

    private void switchLine(int line, boolean keepPos, long pos) {
        String url = lines.get(line).urls.get(episodes.get(curEp));
        if (url == null) return;
        lastAutoSwitchAt = 0;   // 手动切线不受自动换线节流限制
        curLine = line;
        if (!keepPos) pendingSeekMs = -1;
        else if (pendingSeekMs <= 0 && pos > 0) pendingSeekMs = pos;
        playUrlInternal(url);
    }

    private long safePos() {
        PositionSnapshot snapshot = positionSnapshot;
        return snapshot.generation == playGeneration.get() && snapshot.positionMs > 0
                ? snapshot.positionMs : 0;
    }

    private void resetPositionSnapshot(int gen) {
        synchronized (positionSnapshotLock) {
            if (gen == playGeneration.get()) {
                PositionSnapshot current = positionSnapshot;
                if (current.generation <= gen) {
                    positionSnapshot = new PositionSnapshot(gen, -1, -1);
                }
            }
        }
    }

    private void updatePositionSnapshot(int gen, long positionMs, long durationMs) {
        synchronized (positionSnapshotLock) {
            if (gen != playGeneration.get()) return;
            PositionSnapshot current = positionSnapshot;
            if (current.generation > gen) return;
            boolean sameGeneration = current.generation == gen;
            long position = positionMs >= 0 ? positionMs
                    : sameGeneration ? current.positionMs : -1;
            long duration = durationMs > 0 ? durationMs
                    : sameGeneration ? current.durationMs : -1;
            positionSnapshot = new PositionSnapshot(gen, position, duration);
        }
    }

    private void applySpeed(final int gen) {
        final int speedIntent = speedIntentGeneration.get();
        final float rate = SPEEDS[speedIdx];
        playerExecutor.execute(() -> {
            if (speedIntent != speedIntentGeneration.get() || !isCurrentPlayerSession(gen)
                    || !foreground || vp == null) return;
            try { vp.setRate(rate); }
            catch (Exception e) { Log.w(TAG, "set playback rate", e); }
        });
    }

    private boolean isCurrentPlayerSession(int gen) {
        return !playerReleased && gen == playGeneration.get() && gen == playingGeneration
                && gen == confirmedGeneration && vp != null;
    }

    /**
     * 退出时释放播放器（H1）：stop/release 同样会阻塞，作为串行执行器的
     * 收尾任务投递，onDestroy 不在主线程等它跑完；投递后关执行器，
     * playerReleased 置位后不再接新播放任务、迟到事件不再碰已释放的播放器。
     * 切集/切线时的 stop 已并入 playUrlInternal 的串行任务，不再有独立的
     * 主线程 stopPlayback 路径。
     */
    private void releasePlayer() {
        playerReleased = true;
        detachVideoViews("destroy");
        final org.videolan.libvlc.MediaPlayer player = vp;
        final LibVLC lc = libVlc;
        playerExecutor.execute(() -> {
            if (player != null) {
                saveStateOnPlayerThread();
                try { player.setEventListener(null); } catch (Exception ignored) { }
                try {
                    Log.i(TAG, "player release stop started");
                    player.stop();
                    Log.i(TAG, "player release stop completed");
                } catch (Exception e) { Log.w(TAG, "player release stop failed", e); }
                try { player.release(); } catch (Exception ignored) { }
            }
            vp = null;
            if (lc != null) {
                try { lc.release(); } catch (Exception ignored) { }
            }
            libVlc = null;
        });
        playerExecutor.shutdown();
    }

    /** 播放统计值为采样快照；缺失轨道用 null/0，缺失差分用 -1。 */
    public static final class PlaybackStatsSnapshot {
        public final int generation, width, height, audioChannels, audioRate;
        public final String videoCodec, audioCodec;
        public final double nominalFps, demuxMbps, inputMbps, displayedFps;
        public final long sampledAtMs, intervalMs;
        public final int displayedDelta, lostDelta;
        private PlaybackStatsSnapshot(int generation, int width, int height, String videoCodec,
                double nominalFps, String audioCodec, int audioChannels, int audioRate,
                double demuxMbps, double inputMbps, long sampledAtMs, long intervalMs,
                int displayedDelta, int lostDelta) {
            this.generation = generation; this.width = width; this.height = height;
            this.videoCodec = videoCodec; this.nominalFps = nominalFps;
            this.audioCodec = audioCodec; this.audioChannels = audioChannels; this.audioRate = audioRate;
            this.demuxMbps = demuxMbps; this.inputMbps = inputMbps; this.sampledAtMs = sampledAtMs;
            this.intervalMs = intervalMs; this.displayedDelta = displayedDelta; this.lostDelta = lostDelta;
            this.displayedFps = intervalMs > 0 && displayedDelta >= 0 ? displayedDelta * 1000.0 / intervalMs : -1;
        }
    }

    // 以下基线只由 playerExecutor 访问。
    private int statsGeneration = -1, statsDisplayed = -1, statsLost = -1;
    private long statsTime = -1;

    /** chrome 隐藏后调用；下次采样建立新基线。 */
    public void resetPlaybackStatsBaseline() {
        if (!playerReleased) playerExecutor.execute(() -> { statsGeneration = -1; statsTime = -1; });
    }

    /** 主线程发起；回调在主线程，切代或释放时丢弃，未就绪返回 null。 */
    public void requestPlaybackStats(java.util.function.Consumer<PlaybackStatsSnapshot> callback) {
        if (playerReleased || callback == null) return;
        final int gen = playGeneration.get();
        playerExecutor.execute(() -> {
            PlaybackStatsSnapshot snapshot = null;
            org.videolan.libvlc.interfaces.IMedia media = null;
            try {
                if (!playerReleased && gen == playGeneration.get() && gen == playingGeneration
                        && gen == confirmedGeneration && vp != null) {
                    media = vp.getMedia();
                    org.videolan.libvlc.interfaces.IMedia.VideoTrack video = vp.getCurrentVideoTrack();
                    org.videolan.libvlc.interfaces.IMedia.AudioTrack audio = null;
                    if (media != null) {
                        int audioId = vp.getAudioTrack();
                        for (int i = 0; i < media.getTrackCount(); i++) {
                            org.videolan.libvlc.interfaces.IMedia.Track t = media.getTrack(i);
                            if (t instanceof org.videolan.libvlc.interfaces.IMedia.AudioTrack && t.id == audioId) {
                                audio = (org.videolan.libvlc.interfaces.IMedia.AudioTrack) t;
                                break;
                            }
                        }
                        org.videolan.libvlc.interfaces.IMedia.Stats s = media.getStats();
                        long now = SystemClock.elapsedRealtime(), interval = -1;
                        int displayed = -1, lost = -1;
                        if (s != null) {
                            if (statsGeneration == gen && statsTime >= 0 && now > statsTime
                                    && s.displayedPictures >= statsDisplayed && s.lostPictures >= statsLost) {
                                interval = now - statsTime;
                                displayed = s.displayedPictures - statsDisplayed;
                                lost = s.lostPictures - statsLost;
                            }
                            statsGeneration = gen; statsTime = now;
                            statsDisplayed = s.displayedPictures; statsLost = s.lostPictures;
                        }
                        snapshot = new PlaybackStatsSnapshot(gen, video == null ? 0 : video.width,
                                video == null ? 0 : video.height, video == null ? null : video.codec,
                                video == null || video.frameRateDen <= 0 ? 0 : (double) video.frameRateNum / video.frameRateDen,
                                audio == null ? null : audio.codec, audio == null ? 0 : audio.channels,
                                audio == null ? 0 : audio.rate, s == null ? -1 : s.demuxBitrate * 8.0,
                                s == null ? -1 : s.inputBitrate * 8.0, now, interval, displayed, lost);
                    }
                }
            } catch (Exception e) { Log.w(TAG, "playback stats", e); }
            finally { if (media != null) media.release(); }
            final PlaybackStatsSnapshot result = snapshot;
            handler.post(() -> { if (!playerReleased && gen == playGeneration.get()) callback.accept(result); });
        });
    }

    // ================= 线路测速 =================

    private void probeAllLines() {
        if (playerReleased) return;
        String epName = episodes.get(curEp);
        for (int i = 0; i < lines.size(); i++) {
            final int idx = i;
            final String url = lines.get(idx).urls.get(epName);
            String probeKey = idx + "|" + epName;
            if (url == null || Boolean.TRUE.equals(lineProbeDone.get(idx))
                    || !lineProbesInFlight.add(probeKey)) continue;
            probePool.execute(() -> {
                long t0 = SystemClock.elapsedRealtime();
                int result = -1;
                String quality = null;
                boolean deferred = false;
                HttpURLConnection c = null;
                boolean acquired = false;
                try {
                    probePermit.acquire();
                    acquired = true;
                    String host = new URL(url).getHost();
                    synchronized (hostCooldown) {
                        deferred = SystemClock.elapsedRealtime() < hostCooldown.getOrDefault(host, 0L);
                    }
                    if (deferred || playerReleased) throw new java.io.IOException("probe deferred");
                    t0 = SystemClock.elapsedRealtime();
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(2500);
                    c.setReadTimeout(3000);
                    c.setRequestProperty("User-Agent", DESKTOP_UA);
                    c.setRequestProperty("Referer", "https://www.ikanbot.com/");
                    int code = c.getResponseCode();
                    if (code >= 200 && code < 300) {
                        try (InputStream in = c.getInputStream()) {
                            byte[] header = new byte[11];
                            int n = 0;
                            while (n < header.length) {
                                int b = in.read();
                                if (b < 0 || b == '\n') break;
                                header[n++] = (byte) b;
                            }
                            String firstLine = new String(header, 0, n,
                                    java.nio.charset.StandardCharsets.UTF_8).replace("\r", "");
                            if (firstLine.startsWith("\uFEFF")) firstLine = firstLine.substring(1);
                            if ("#EXTM3U".equals(firstLine)) {
                                result = (int) (SystemClock.elapsedRealtime() - t0);
                                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                                byte[] buf = new byte[4096];
                                int remaining = 65536 - n - 1, count;
                                while (remaining > 0 && (count = in.read(buf, 0, Math.min(buf.length, remaining))) != -1) {
                                    body.write(buf, 0, count);
                                    remaining -= count;
                                }
                                if (remaining > 0) quality = parseDeclaredQuality(body.toString("UTF-8"));
                            }
                        }
                    } else {
                        if (code == 429) {
                            long delay = retryAfterMillis(c.getHeaderField("Retry-After"));
                            synchronized (hostCooldown) {
                                int failures = Math.min(4, hostFailures.getOrDefault(host, 0));
                                hostFailures.put(host, failures + 1);
                                delay = Math.max(delay, Math.min(600000L, 60000L << failures));
                                hostCooldown.put(host, SystemClock.elapsedRealtime() + delay);
                            }
                            deferred = true;
                        }
                        InputStream error = c.getErrorStream();
                        if (error != null) error.close();
                    }
                } catch (Exception e) {
                    result = -1;
                } finally {
                    if (c != null) c.disconnect();
                    if (acquired) probePermit.release();
                }
                final int r = result;
                final String declared = quality;
                final boolean wasDeferred = deferred;
                handler.post(() -> {
                    if (playerReleased) return;
                    lineProbesInFlight.remove(probeKey);
                    if (declared != null) declaredQuality.put(epName + "|" + url, declared);
                    if (wasDeferred) return;
                    if (!episodes.get(curEp).equals(epName)
                            || badSources.contains(curEp + "|" + idx)) return;
                    lineLatency.put(idx, r);
                    lineProbeDone.put(idx, true);
                    if (panelOpen && !panelEpMode) refreshVisibleLineLabels();
                });
            });
        }
    }

    private static String parseDeclaredQuality(String body) {
        String best = null;
        long bestBandwidth = -1;
        java.util.regex.Pattern resolution = java.util.regex.Pattern.compile("(?:^|,)RESOLUTION=(\\d+x\\d+)");
        java.util.regex.Pattern bandwidth = java.util.regex.Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)");
        for (String line : body.split("\\r?\\n")) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            String attrs = line.substring("#EXT-X-STREAM-INF:".length());
            java.util.regex.Matcher r = resolution.matcher(attrs), b = bandwidth.matcher(attrs);
            String size = r.find() ? r.group(1) : null;
            long rate = -1;
            if (b.find()) try { rate = Long.parseLong(b.group(1)); } catch (NumberFormatException ignored) { }
            if ((size != null || rate > 0) && (best == null || rate > bestBandwidth)) {
                bestBandwidth = rate;
                best = "声明 " + (size == null ? "" : size) + (size != null && rate > 0 ? " · " : "")
                        + (rate > 0 ? String.format(java.util.Locale.US, "%.2f Mbps", rate / 1000000.0) : "");
            }
        }
        return best;
    }

    private static long retryAfterMillis(String header) {
        if (header == null) return 0;
        try { return Math.min(3600000L, Math.max(0L, Long.parseLong(header.trim())) * 1000L); }
        catch (NumberFormatException ignored) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
                return Math.max(0L, Math.min(3600000L, f.parse(header).getTime() - System.currentTimeMillis()));
            } catch (Exception e) { return 0; }
        }
    }

    // ================= UI 辅助 =================

    private void setLoading(boolean show, String text) {
        loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) loadingText.setText(text);
    }

    private void updateSubText() {
        if (episodes.isEmpty() || lines.isEmpty()) return;
        subText.setText(episodes.get(curEp) + " · " + lines.get(curLine).label
                + " · 共" + lines.size() + "条线路");
    }

    private void showChrome() {
        topBar.setVisibility(View.VISIBLE);
        controller.setVisibility(View.VISIBLE);
        playerClock.setVisibility(panelOpen ? View.GONE : View.VISIBLE);
        audioStats.setVisibility(panelOpen ? View.INVISIBLE : View.VISIBLE);
        updateProgress();
        handler.removeCallbacks(hideChrome);
        handler.postDelayed(hideChrome, 4500);
    }

    private void updatePlaybackStats() {
        playerClock.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date()));
        final int epoch = statsVisibilityEpoch;
        requestPlaybackStats(stats -> {
            if (epoch != statsVisibilityEpoch || (topBar.getVisibility() != View.VISIBLE
                    && !(panelOpen && !panelEpMode))) return;
            visibleStats = stats;
            ArrayList<String> video = new ArrayList<>(), audio = new ArrayList<>();
            if (stats != null) {
                if (stats.width > 0 && stats.height > 0) video.add(stats.width + "×" + stats.height);
                if (stats.nominalFps > 0) video.add(String.format(java.util.Locale.US, "%.2f fps", stats.nominalFps));
                if (stats.videoCodec != null && !stats.videoCodec.isEmpty()) video.add(stats.videoCodec);
                if (stats.demuxMbps >= 0) video.add(String.format(java.util.Locale.US, "内容 %.2f Mbps", stats.demuxMbps));
                if (stats.audioCodec != null && !stats.audioCodec.isEmpty()) audio.add(stats.audioCodec);
                if (stats.audioChannels > 0) audio.add(stats.audioChannels + " 声道");
                if (stats.audioRate > 0) audio.add(String.format(java.util.Locale.US, "%.1f kHz", stats.audioRate / 1000.0));
            }
            videoStats.setText(android.text.TextUtils.join(" · ", video));
            audioStats.setText(android.text.TextUtils.join(" · ", audio));
            if (panelOpen && !panelEpMode) refreshVisibleLineLabels();
        });
    }

    private void showIndicator(String s) {
        centerIndicator.setText(s);
        centerIndicator.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideIndicator);
        handler.postDelayed(hideIndicator, 700);
    }

    private void showOsd(String s) {
        osdText.setText(s);
        osdText.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideOsd);
        handler.postDelayed(hideOsd, 2200);
    }

    private void updateProgress() {
        if (!prepared) return;
        int gen = playGeneration.get();
        PositionSnapshot snapshot = positionSnapshot;
        if (snapshot.generation != gen || snapshot.positionMs < 0) return;
        long pos = snapshot.positionMs;
        long dur = snapshot.durationMs;
        if (dur > 0) {
            seekBar.setMax((int) Math.min(Integer.MAX_VALUE, dur));
            seekBar.setProgress((int) Math.min(Integer.MAX_VALUE, pos));
            timeText.setText(fmtTime(pos) + " / " + fmtTime(dur));
        }
    }

    /** Samples VLC position off the main thread; only one sample can queue at a time. */
    private void requestPositionSample() {
        if (playerReleased || !prepared || !positionSamplePending.compareAndSet(false, true)) return;
        final int gen = playGeneration.get();
        playerExecutor.execute(() -> {
            try {
                if (!isCurrentPlayerSession(gen) || vp == null) return;
                long position = vp.getTime();
                long duration = vp.getLength();
                if (!isCurrentPlayerSession(gen)) return;
                updatePositionSnapshot(gen, position, duration);
            } catch (Exception e) {
                Log.w(TAG, "position sample", e);
            } finally {
                positionSamplePending.set(false);
            }
        });
    }

    private static String fmtTime(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec)
                : String.format("%02d:%02d", m, sec);
    }

    // ================= 面板（选集 / 换源） =================

    private void openPanel(boolean epMode) {
        if (!dataReady) return;
        panelEpMode = epMode;
        panelOpen = true;
        panel.setVisibility(View.VISIBLE);
        playerClock.setVisibility(View.GONE);
        audioStats.setVisibility(View.INVISIBLE);
        if (epMode) {
            panelTitle.setText(isMovie
                    ? "选择版本  ·  菜单键切到换源"
                    : "选集（共 " + episodes.size() + " 集）  ·  菜单键切到换源");
            epGrid.setVisibility(View.VISIBLE);
            lineList.setVisibility(View.GONE);
            epGrid.setAdapter(new EpAdapter());
            epGrid.setSelection(curEp);
            epGrid.requestFocus();
        } else {
            panelTitle.setText("换源（共 " + lines.size() + " 条）  ·  菜单键切到选集");
            epGrid.setVisibility(View.GONE);
            lineList.setVisibility(View.VISIBLE);
            lineDisplayOrder = snapshotLineOrder();
            refreshLineList();
            int selected = lineDisplayOrder.indexOf(curLine);
            if (selected >= 0) lineList.setSelection(selected);
            lineList.requestFocus();
            probeAllLines();
        }
        handler.removeCallbacks(hideChrome);
    }

    private void refreshLineList() {
        if (lineAdapter == null) {
            lineAdapter = new LineAdapter();
            lineList.setAdapter(lineAdapter);
        } else {
            lineAdapter.notifyDataSetChanged();
        }
    }

    // 只改现有行的文字，避免异步回调触发列表重建或改变焦点位置。
    private void refreshVisibleLineLabels() {
        if (lineAdapter == null) return;
        int first = lineList.getFirstVisiblePosition();
        for (int i = 0; i < lineList.getChildCount(); i++) {
            int position = first + i;
            if (position < lineAdapter.getCount())
                lineAdapter.getView(position, lineList.getChildAt(i), lineList);
        }
    }

    private void closePanel() {
        panelOpen = false;
        panel.setVisibility(View.GONE);
        surface.requestFocus();
        showChrome();
    }

    private class EpAdapter extends BaseAdapter {
        @Override public int getCount() { return episodes.size(); }
        @Override public Object getItem(int p) { return episodes.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View convert, android.view.ViewGroup parent) {
            TextView tv;
            if (convert instanceof TextView) tv = (TextView) convert;
            else {
                tv = new TextView(PlayerActivity.this);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setTextSize(17);
                tv.setPadding(6, 16, 6, 16);
            }
            tv.setText(episodes.get(p));
            boolean cur = p == curEp;
            tv.setTextColor(cur ? Color.parseColor("#FFB300") : Color.WHITE);
            tv.setBackgroundColor(cur ? Color.parseColor("#33FFB300") : Color.TRANSPARENT);
            return tv;
        }
    }

    private class LineAdapter extends BaseAdapter {
        @Override public int getCount() { return lineDisplayOrder.size(); }
        @Override public Object getItem(int p) { return lines.get(lineDisplayOrder.get(p)); }
        @Override public long getItemId(int p) { return lineDisplayOrder.get(p); }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int p, View convert, android.view.ViewGroup parent) {
            TextView tv;
            if (convert instanceof TextView) tv = (TextView) convert;
            else {
                tv = new TextView(PlayerActivity.this);
                tv.setTextSize(17);
                tv.setPadding(18, 14, 18, 14);
            }
            int line = lineDisplayOrder.get(p);
            LineData ld = lines.get(line);
            boolean has = ld.urls.containsKey(episodes.get(curEp));
            String status;
            int dotColor = Color.GRAY;
            if (!has) status = "无本集";
            else if (badSources.contains(curEp + "|" + line)) {
                status = "播不了";
                dotColor = Color.parseColor("#D32F2F");
            } else {
                Integer lat = lineLatency.get(line);
                if (lat == null) status = "待测";
                else if (lat < 0) {
                    status = latencyGrade(lat);
                    dotColor = Color.parseColor("#D32F2F");
                }
                else {
                    status = latencyGrade(lat) + " · " + lat + "ms";
                    dotColor = Color.parseColor(lat < LATENCY_FASTEST_MS ? "#2E7D32"
                            : lat < LATENCY_FAST_MS ? "#43A047"
                            : lat < LATENCY_NORMAL_MS ? "#FBC02D"
                            : lat < LATENCY_SLOW_MS ? "#EF6C00" : "#D32F2F");
                }
            }
            String quality = getDeclaredQuality(line);
            String actual = null;
            if (line == curLine && line == playingLine && curEp == playingEp
                    && visibleStats != null && visibleStats.generation == playGeneration.get()) {
                ArrayList<String> parts = new ArrayList<>();
                if (visibleStats.width > 0 && visibleStats.height > 0)
                    parts.add(visibleStats.width + "×" + visibleStats.height);
                if (visibleStats.demuxMbps >= 0)
                    parts.add(String.format(java.util.Locale.US, "%.2f Mbps", visibleStats.demuxMbps));
                if (!parts.isEmpty()) actual = "实测 " + android.text.TextUtils.join(" · ", parts);
            }
            String text = ld.label + "  ● " + status
                    + (quality == null ? "" : "\n" + quality)
                    + (actual == null ? "" : "\n" + actual);
            android.text.SpannableString styled = new android.text.SpannableString(text);
            int dot = text.indexOf('●');
            styled.setSpan(new android.text.style.ForegroundColorSpan(dotColor), dot, dot + 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(styled);
            boolean cur = line == curLine;
            tv.setTextColor(!has ? Color.GRAY : (cur ? Color.parseColor("#FFB300") : Color.WHITE));
            tv.setBackgroundColor(cur ? Color.parseColor("#33FFB300") : Color.TRANSPARENT);
            return tv;
        }
    }

    // ================= 按键 =================

    private int seekRepeat = 0;
    private boolean okTracking = false;
    private boolean okLongHandled = false;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (ok && event.getAction() == KeyEvent.ACTION_UP && okTracking) {
            okTracking = false;
            if (!okLongHandled && !event.isCanceled()) togglePlay();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (panelOpen) {
                switch (code) {
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_ESCAPE:
                        closePanel();
                        return true;
                    case KeyEvent.KEYCODE_MENU:
                        // 菜单键在选集/换源两个面板间切换，方向键留给列表自己导航
                        openPanel(!panelEpMode);
                        return true;
                }
                return super.dispatchKeyEvent(event);
            }
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (event.getRepeatCount() == 0) {
                        okTracking = true;
                        okLongHandled = false;
                    } else if (okTracking && !okLongHandled
                            && (event.isLongPress() || event.getRepeatCount() >= 8)) {
                        okLongHandled = true;
                        speedIdx = (speedIdx + 1) % SPEEDS.length;
                        startWithNewSpeed();
                        showOsd("倍速 " + SPEEDS[speedIdx] + "x");
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    togglePlay();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    doSeek(-1, event.getRepeatCount());
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    doSeek(1, event.getRepeatCount());
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    openPanel(true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_MENU:
                    openPanel(false);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (curEp + 1 < episodes.size()) switchEpisode(curEp + 1, false);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    if (curEp > 0) switchEpisode(curEp - 1, false);
                    return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_UP
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            seekRepeat = 0;
        }
        return super.dispatchKeyEvent(event);
    }

    private void togglePlay() {
        if (!prepared || playerReleased || !foreground) return;
        final int gen = playGeneration.get();
        final boolean shouldPlay = userPaused;
        userPaused = !shouldPlay;
        final int intent = playerIntentGeneration.incrementAndGet();
        playerExecutor.execute(() -> {
            if (intent != playerIntentGeneration.get() || !isCurrentPlayerSession(gen)
                    || !foreground || vp == null) return;
            try {
                if (shouldPlay && !userPaused) {
                    vp.play();
                } else if (!shouldPlay && userPaused) {
                    vp.pause();
                    saveStateOnPlayerThread();
                }
            } catch (Exception e) { Log.w(TAG, "toggle playback", e); }
        });
        showIndicator(shouldPlay ? "▶" : "❚❚");
        showChrome();
    }

    private void doSeek(int dir, int repeat) {
        if (!prepared || playerReleased || !foreground) return;
        // A manual seek supersedes a saved resume target; its queued retries must not undo it.
        pendingSeekMs = -1;
        resumeRetryGeneration = -1;
        final int gen = playGeneration.get();
        final int step = 30000 + Math.min(repeat, 10) * 15000;
        playerExecutor.execute(() -> {
            if (!isCurrentPlayerSession(gen) || !foreground || vp == null) return;
            try {
                long position = vp.getTime();
                if (!isCurrentPlayerSession(gen) || !foreground) return;
                long target = Math.max(0, position) + dir * (long) step;
                long duration = vp.getLength();
                if (!isCurrentPlayerSession(gen) || !foreground) return;
                if (target < 0) target = 0;
                if (duration > 0 && target > duration - 1000) target = Math.max(0, duration - 1000);
                vp.setTime(target);
                if (!isCurrentPlayerSession(gen) || !foreground) return;
                updatePositionSnapshot(gen, target, duration);
                final long seekTarget = target;
                handler.post(() -> {
                    if (gen != playGeneration.get() || playerReleased) return;
                    showOsd((dir < 0 ? "快退 " : "快进 ") + (step / 1000)
                            + " 秒 → " + fmtTime(seekTarget));
                    showChrome();
                });
            } catch (Exception e) { Log.w(TAG, "seek", e); }
        });
    }

    private void startWithNewSpeed() {
        final int gen = playGeneration.get();
        final int intent = playerIntentGeneration.incrementAndGet();
        final int speedIntent = speedIntentGeneration.incrementAndGet();
        final float rate = SPEEDS[speedIdx];
        userPaused = false;
        playerExecutor.execute(() -> {
            if (speedIntent != speedIntentGeneration.get() || !isCurrentPlayerSession(gen)
                    || !foreground || vp == null) return;
            try {
                vp.setRate(rate);
                if (intent == playerIntentGeneration.get() && !userPaused && !vp.isPlaying()) {
                    vp.play();
                }
            } catch (Exception e) { Log.w(TAG, "long-press playback", e); }
        });
    }

    @Override
    public void onBackPressed() {
        if (panelOpen) { closePanel(); return; }
        prefs.edit().remove("active_player").remove("active_player_title")
                .remove("active_player_episode_name").remove("active_player_format").apply();
        saveState();
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        attachVideoViews();
        if (prepared && playingGeneration == playGeneration.get()) writeActivePlayer();
        if (pendingStart && dataReady && currentUrl() != null) playUrlInternal(currentUrl());
        else if (prepared) {
            final int gen = playGeneration.get();
            if (pendingSeekMs > 0) {
                resumeRetryGeneration = -1;
                retryResumeSeek(gen, pendingSeekMs, RESUME_SEEK_ATTEMPTS);
            }
            if (userPaused) {
                final int intent = playerIntentGeneration.incrementAndGet();
                playerExecutor.execute(() -> {
                    if (intent == playerIntentGeneration.get() && isCurrentPlayerSession(gen)
                            && foreground && userPaused && vp != null) {
                        try { vp.pause(); }
                        catch (Exception e) { Log.w(TAG, "foreground pause restore", e); }
                    }
                });
            }
        }
    }

    @Override
    protected void onPause() {
        foreground = false;
        okTracking = false;
        userPaused = true;
        final int intent = playerIntentGeneration.incrementAndGet();
        final int gen = playGeneration.get();
        detachVideoViews("pause");
        super.onPause();
        saveState();
        playerExecutor.execute(() -> {
            if (intent == playerIntentGeneration.get() && !foreground && !playerReleased
                    && gen == playGeneration.get() && vp != null) {
                try { vp.pause(); }
                catch (Exception e) { Log.w(TAG, "background pause", e); }
            }
        });
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (harvestView != null) { harvestView.destroy(); harvestView = null; }
        probePool.shutdownNow();
        super.onDestroy();
    }
}
