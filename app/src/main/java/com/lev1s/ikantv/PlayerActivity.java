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
    private final List<LineData> lines = new ArrayList<>();
    private int curEp = 0;
    private int curLine = 0;
    private final Set<String> badSources = new HashSet<>();       // "ep|line"
    private final Map<Integer, Integer> lineLatency = new HashMap<>(); // line -> ms, -1=失败
    private final Map<Integer, Boolean> lineProbeDone = new HashMap<>();

    // ---------- 播放器（libVLC 现成播放器内核：HTTPS + AES-128 HLS + MediaCodec 硬解） ----------
    private volatile LibVLC libVlc;
    private volatile org.videolan.libvlc.MediaPlayer vp;
    private org.videolan.libvlc.util.VLCVideoLayout surface;
    private boolean dataReady = false;
    private boolean prepared = false;
    private boolean userPaused = false;
    private long pendingSeekMs = -1;
    private int speedIdx = 0;
    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f, 0.5f};

    // ---------- UI ----------
    private View loadingOverlay;
    private TextView loadingText;
    private View topBar;
    private TextView titleText, subText;
    private View controller;
    private SeekBar seekBar;
    private TextView timeText;
    private TextView centerIndicator;
    private TextView osdText;
    private View panel;
    private TextView panelTitle;
    private GridView epGrid;
    private ListView lineList;
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
    // 播放代际号（H2）：playUrlInternal 入口自增；libVLC 事件在原生回调线程
    // 捕获当时的代值、随 runnable 带回主线程比对，过期事件直接丢弃。
    private final AtomicInteger playGeneration = new AtomicInteger(0);
    private volatile boolean playerReleased = false;
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
    };
    private final Runnable hideIndicator = () -> centerIndicator.setVisibility(View.GONE);
    private final Runnable hideOsd = () -> osdText.setVisibility(View.GONE);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateProgress();
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
            closePanel();
            if (position != curLine) switchLine(position, true, safePos());
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
                handler.post(() -> { if (!harvestDone) startWebViewHarvest(); });
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
        // token：ID 末 4 位逐位 d → 在 e_token 上跳过 (d%3+1) 个字符再取 8 个，4 段拼接
        String last4 = cid.substring(Math.max(0, cid.length() - 4));
        StringBuilder tk = new StringBuilder();
        int pos = 0;
        for (int i = 0; i < last4.length(); i++) {
            char ch = last4.charAt(i);
            if (!Character.isDigit(ch)) return null;
            pos += (ch - '0') % 3 + 1;
            if (pos + 8 > et.length()) return null;
            tk.append(et, pos, pos + 8);
            pos += 8;
        }
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
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            JSONObject urls = new JSONObject();
            try {
                JSONArray res = new JSONArray(item.optString("resData", "[]"));
                for (int k = 0; k < res.length(); k++) {
                    String u = res.getJSONObject(k).optString("url", "");
                    for (String part : u.split("#")) {
                        int d = part.indexOf('$');
                        if (d <= 0) continue;
                        String name = part.substring(0, d).replaceAll("\\s+", " ").trim();
                        String url = part.substring(d + 1).trim();
                        if (!name.isEmpty() && url.matches("(?i).*\\.m3u8(\\?.*)?$")) {
                            urls.put(name, url);
                        }
                    }
                }
            } catch (Exception ignored) { }
            JSONObject lo = new JSONObject();
            lo.put("id", item.optString("id", ""));
            lo.put("urls", urls);
            linesArr.put(lo);
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
                    bos.write(buf, 0, n);
                    total += n;
                    if (total > 600_000) break;
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
                    if (harvestDone || harvestView == null) return;
                    harvestView.evaluateJavascript("document.title", value -> {
                        if (value != null && value.contains("Just a moment")) {
                            if (cfRetries < 3) {
                                cfRetries++;
                                setLoading(true, "网站正在校验，自动重试（" + cfRetries + "/3）…");
                                handler.postDelayed(() -> {
                                    if (harvestView != null) harvestView.loadUrl(playUrl);
                                }, 4000);
                            } else {
                                setLoading(true, "网站校验暂时过不去，请返回后稍再再试");
                            }
                            return;
                        }
                        harvestView.evaluateJavascript(HARVEST_JS, null);
                        // 兜底超时
                        handler.postDelayed(() -> {
                            if (!harvestDone) setLoading(true, "线路获取较慢，仍在等待…（可返回重试）");
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
        if (harvestDone) return;
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
            lines.clear();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject lo = arr.getJSONObject(i);
                    LineData ld = new LineData();
                    ld.label = "线路" + (i + 1);
                    JSONObject urls = lo.optJSONObject("urls");
                    if (urls != null) {
                        java.util.Iterator<String> it = urls.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            ld.urls.put(k, urls.optString(k));
                        }
                    }
                    if (!ld.urls.isEmpty()) lines.add(ld);
                }
            }
            if (lines.isEmpty()) {
                setLoading(true, "这部片子暂时没有可用线路，请返回换一部");
                return;
            }
            // 集数 = 条目最多的那条线路的条目顺序
            LineData richest = lines.get(0);
            for (LineData ld : lines) if (ld.urls.size() > richest.urls.size()) richest = ld;
            episodes.clear();
            episodes.addAll(richest.urls.keySet());
            // urls 是 HashMap（无序）：集数必须显式排序——第N集按数字序，
            // 否则剧集面板里集数会乱序；无数字的（电影版本）把「正片」排最前。
            java.util.Collections.sort(episodes, (a, b) -> {
                int na = epNum(a), nb = epNum(b);
                if (na != nb) return Integer.compare(na, nb);
                boolean za = a.contains("正片"), zb = b.contains("正片");
                if (za != zb) return za ? -1 : 1;
                return a.compareTo(b);
            });
            // 恢复上次进度（同片同站）
            restoreState();
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

    /** 集名里的第一个数字（第12集 → 12）；没有数字返回 MAX（排最后） */
    private static int epNum(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
        if (m.find()) {
            try { return Integer.parseInt(m.group()); } catch (Exception ignored) { }
        }
        return Integer.MAX_VALUE;
    }

    private void restoreState() {
        if (videoId.isEmpty()) return;
        String saved = prefs.getString("resume_" + videoId, null);
        if (saved == null) return;
        try {
            String[] p = saved.split("\\|");
            int ep = Integer.parseInt(p[0]);
            int ln = Integer.parseInt(p[1]);
            long pos = Long.parseLong(p[2]);
            if (ep >= 0 && ep < episodes.size()) curEp = ep;
            if (ln >= 0 && ln < lines.size()
                    && lines.get(ln).urls.containsKey(episodes.get(curEp))) curLine = ln;
            if (pos > 10000) pendingSeekMs = pos;
        } catch (Exception ignored) { }
    }

    private void saveState() {
        // 集/线读 playingEp/playingLine（实际在播的），不读 curEp/curLine：
        // 切集会先改 curEp，读了就会把旧集位置记到新集名下（M1）。
        // 曾在此写的 lastline_ 键全仓无读取方，已随迭代二删除。
        if (videoId.isEmpty() || vp == null) return;
        try {
            long pos = Math.max(0, vp.getTime());
            int ep = playingEp >= 0 ? playingEp : curEp;
            int ln = playingLine >= 0 ? playingLine : curLine;
            prefs.edit().putString("resume_" + videoId,
                    ep + "|" + ln + "|" + pos).apply();
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
            vp.setEventListener(vlcEventListener);
            // 必须走带 VLCVideoLayout 的 attachViews：它会创建 VideoHelper 接管
            // 画面几何（按 DAR 等比摆放）；裸 SurfaceView 无参 attach 没有这层，
            // 画面会被底层填充口径裁掉边缘（2026-11-03 缩放研究实锤）。
            vp.attachViews(surface, null, false, false);
            vp.setVideoScale(org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_BEST_FIT);
        }
    }

    private final org.videolan.libvlc.MediaPlayer.EventListener vlcEventListener = event -> {
        // VLC 事件在原生线程回调：在这里先捕获当前代际号，随 runnable 带回
        // 主线程比对——旧 Media 的迟到事件在代际变更后一律丢弃（H2）。
        // UI 操作统一切回主线程。
        final int gen = playGeneration.get();
        handler.post(() -> {
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
                    prepared = true;
                    userPaused = false;
                    setLoading(false, "");
                    applySpeed();
                    if (pendingSeekMs > 0) {
                        long dur = vp.getLength();
                        if (dur <= 0 || pendingSeekMs < dur - 5000) {
                            vp.setTime(pendingSeekMs);
                            showOsd("已续播到 " + fmtTime(pendingSeekMs));
                        }
                        pendingSeekMs = -1;
                    }
                    showChrome();
                    break;
                }
                case org.videolan.libvlc.MediaPlayer.Event.EndReached: {
                    if (curEp + 1 < episodes.size()) {
                        showOsd("本集播完，自动播放下一集");
                        // 延迟期内若已切集/切线（代际已变），过期动作作废
                        handler.postDelayed(() -> {
                            if (gen != playGeneration.get() || playerReleased) return;
                            switchEpisode(curEp + 1, false);
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
    };

    private void playUrlInternal(String url) {
        if (playerReleased) return;
        final int gen = playGeneration.incrementAndGet();
        final int targetEp = curEp;
        final int targetLine = curLine;
        prepared = false;
        setLoading(true, "正在缓冲：" + episodes.get(curEp) + " · " + lines.get(curLine).label + "…");
        updateSubText();
        // 播放器创建与视图挂载留在主线程（VLCVideoLayout 属 View 体系）；
        // 会阻塞的 stop/setMedia/play 整段投串行执行器（H1）。
        ensureVlc();
        final org.videolan.libvlc.MediaPlayer player = vp;
        final LibVLC lc = libVlc;
        if (player == null || lc == null) return;
        playerExecutor.execute(() -> {
            // 已有更新一代在排队时本任务直接作废：不 stop、不换 Media，
            // 后一代任务完成真正的切换（串行队列保证最终态是最新一代）。
            if (gen != playGeneration.get() || playerReleased) return;
            try {
                // saveState 读 getTime：须在 stop 之前、与 stop 同任务。
                // 仅在确有旧媒体在播时才存（playingEp>=0）——首次播放时存量
                // 续播位置还没被消费，不能拿新播放器的 0 位置把它覆掉。
                if (playingEp >= 0) {
                    try { saveState(); } catch (Exception ignored) { }
                }
                try { player.stop(); } catch (Exception ignored) { }
                Media media = new Media(lc, android.net.Uri.parse(url));
                // 硬解优先：MediaCodec（NDK→JNI），不行自动回退软解
                media.addOption(":codec=mediacodec_ndk,mediacodec_jni,any");
                media.addOption(":network-caching=2000");
                player.setMedia(media);
                media.release();
                playingEp = targetEp;
                playingLine = targetLine;
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
        // 自动换线节流（H2 / AGENTS 口径 1.5s）：窗口内不重复自动换线，
        // 防迟到事件或连环错误短时间内把线路表扫穿；手动切换清零、不受限。
        long now = SystemClock.uptimeMillis();
        if (now - lastAutoSwitchAt < 1500) {
            Log.w(TAG, "auto switch throttled: " + reason);
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
        saveState();
        lastAutoSwitchAt = 0;
        curEp = ep;
        pendingSeekMs = -1;
        // 优先沿用同一条线路，没有这一集就找一条有的
        if (!lines.get(curLine).urls.containsKey(episodes.get(ep))) {
            int alt = findLineForEpisode(ep, -1);
            if (alt >= 0) curLine = alt;
        }
        playUrlInternal(currentUrl());
    }

    private void switchLine(int line, boolean keepPos, long pos) {
        lastAutoSwitchAt = 0;   // 手动切线不受自动换线节流限制
        curLine = line;
        pendingSeekMs = keepPos && pos > 0 ? pos : -1;
        String url = currentUrl();
        if (url != null) playUrlInternal(url);
    }

    private long safePos() {
        try { return vp != null ? Math.max(0, vp.getTime()) : 0; }
        catch (Exception e) { return 0; }
    }

    private void applySpeed() {
        if (vp != null) {
            try { vp.setRate(SPEEDS[speedIdx]); }
            catch (Exception ignored) { }
        }
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
        final org.videolan.libvlc.MediaPlayer player = vp;
        final LibVLC lc = libVlc;
        playerExecutor.execute(() -> {
            if (player != null) {
                try { saveState(); } catch (Exception ignored) { }
                try { player.stop(); } catch (Exception ignored) { }
                try { player.detachViews(); } catch (Exception ignored) { }
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

    // ================= 线路测速 =================

    private void probeAllLines() {
        String epName = episodes.get(curEp);
        for (int i = 0; i < lines.size(); i++) {
            final int idx = i;
            final String url = lines.get(idx).urls.get(epName);
            if (url == null || Boolean.TRUE.equals(lineProbeDone.get(idx))) continue;
            probePool.execute(() -> {
                long t0 = System.currentTimeMillis();
                int result = -1;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(2500);
                    c.setReadTimeout(3000);
                    c.setRequestProperty("User-Agent", DESKTOP_UA);
                    c.connect();
                    int code = c.getResponseCode();
                    if (code >= 200 && code < 400) {
                        InputStream in = c.getInputStream();
                        byte[] buf = new byte[256];
                        int n = in.read(buf);
                        if (n > 0 && new String(buf, 0, n).contains("#EXTM3U")) {
                            result = (int) (System.currentTimeMillis() - t0);
                        } else if (n > 0) {
                            result = (int) (System.currentTimeMillis() - t0);
                        }
                        in.close();
                    }
                    // 流已关闭，连接由平台连接池回收；不再 disconnect 销毁它
                } catch (Exception e) {
                    result = -1;
                }
                final int r = result;
                handler.post(() -> {
                    lineLatency.put(idx, r);
                    lineProbeDone.put(idx, true);
                    if (panelOpen && !panelEpMode) refreshLineList();
                });
            });
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
        updateProgress();
        handler.removeCallbacks(hideChrome);
        handler.postDelayed(hideChrome, 4500);
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
        if (vp == null || !prepared) return;
        try {
            long pos = vp.getTime();
            long dur = vp.getLength();
            if (dur > 0) {
                seekBar.setMax((int) dur);
                seekBar.setProgress((int) pos);
                timeText.setText(fmtTime(pos) + " / " + fmtTime(dur));
            }
        } catch (Exception ignored) { }
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
            refreshLineList();
            lineList.setSelection(curLine);
            lineList.requestFocus();
            probeAllLines();
        }
        handler.removeCallbacks(hideChrome);
    }

    private void refreshLineList() {
        lineList.setAdapter(new LineAdapter());
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
        @Override public int getCount() { return lines.size(); }
        @Override public Object getItem(int p) { return lines.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View convert, android.view.ViewGroup parent) {
            TextView tv;
            if (convert instanceof TextView) tv = (TextView) convert;
            else {
                tv = new TextView(PlayerActivity.this);
                tv.setTextSize(17);
                tv.setPadding(18, 14, 18, 14);
            }
            LineData ld = lines.get(p);
            boolean has = ld.urls.containsKey(episodes.get(curEp));
            String status;
            if (!has) status = "（无本集）";
            else if (badSources.contains(curEp + "|" + p)) status = " ● 播不了";
            else {
                Integer lat = lineLatency.get(p);
                if (lat == null) status = " ● 未测";
                else if (lat < 0) status = " ● 连不上";
                else status = " ● 可用 " + lat + "ms";
            }
            tv.setText(ld.label + status);
            boolean cur = p == curLine;
            tv.setTextColor(!has ? Color.GRAY : (cur ? Color.parseColor("#FFB300") : Color.WHITE));
            tv.setBackgroundColor(cur ? Color.parseColor("#33FFB300") : Color.TRANSPARENT);
            return tv;
        }
    }

    // ================= 按键 =================

    private int seekRepeat = 0;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
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
                    if (event.isLongPress() || event.getRepeatCount() >= 8) {
                        speedIdx = (speedIdx + 1) % SPEEDS.length;
                        applySpeed();
                        if (vp != null && !vp.isPlaying() && prepared) { vp.play(); }
                        showOsd("倍速 " + SPEEDS[speedIdx] + "x");
                        return true;
                    }
                    togglePlay();
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
        if (vp == null || !prepared) return;
        if (vp.isPlaying()) {
            vp.pause();
            userPaused = true;
            showIndicator("❚❚");
            saveState();
        } else {
            vp.play();
            userPaused = false;
            showIndicator("▶");
        }
        showChrome();
    }

    private void doSeek(int dir, int repeat) {
        if (vp == null || !prepared) return;
        int step = 30000 + Math.min(repeat, 10) * 15000;
        long target = vp.getTime() + dir * (long) step;
        if (target < 0) target = 0;
        long dur = vp.getLength();
        if (dur > 0 && target > dur - 1000) target = Math.max(0, dur - 1000);
        vp.setTime(target);
        showOsd((dir < 0 ? "快退 " : "快进 ") + (step / 1000) + " 秒 → " + fmtTime(target));
        showChrome();
    }

    @Override
    public void onBackPressed() {
        if (panelOpen) { closePanel(); return; }
        saveState();
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveState();
        if (vp != null && vp.isPlaying()) { vp.pause(); userPaused = true; }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        releasePlayer();
        if (harvestView != null) { harvestView.destroy(); harvestView = null; }
        probePool.shutdownNow();
        super.onDestroy();
    }
}
