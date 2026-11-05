package com.lev1s.ikantv.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.lev1s.ikantv.data.HttpFetcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量海报加载（按可行性报告选型自研，不引 Glide/Picasso 以适配手工构建链）：
 * 内存 LRU + 磁盘缓存两级，按显示尺寸降采样解码（目标宽 264px，
 * 海报实际显示约 130dp×1.33–2.0 倍），候选 URL 链逐个回退。
 */
public class PosterLoader {

    private static final int REQ_WIDTH = 264;
    private static PosterLoader instance;

    public static synchronized PosterLoader get(Context ctx) {
        if (instance == null) instance = new PosterLoader(ctx.getApplicationContext());
        return instance;
    }

    private final LruCache<String, Bitmap> memCache;
    private final File diskDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PosterLoader(Context ctx) {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        memCache = new LruCache<String, Bitmap>(Math.max(8 * 1024, maxKb / 8)) {
            @Override protected int sizeOf(String key, Bitmap bmp) {
                return bmp.getByteCount() / 1024;
            }
        };
        // 目录名带版本号：v1 缓存里可能存了截断海报（代理回源不全时解码出
        // 半截图还被当好图缓存），换目录一次性作废（2026-11-04 真机抓到）
        diskDir = new File(ctx.getCacheDir(), "posters2");
        //noinspection ResultOfMethodCallIgnored
        diskDir.mkdirs();
    }

    /** 把候选链的第一条当缓存键；加载失败（全链皆挂）时保持占位底色，不报错。 */
    public void load(ImageView view, List<String> candidates) {
        load(view, candidates, null);
    }

    /**
     * 带失败回调的加载：候选链全部失败时在主线程跑 onExhausted（卡片据此
     * 亮出首字占位，别留一块黑框——死链海报真机出现过）。
     */
    public void load(ImageView view, List<String> candidates, Runnable onExhausted) {
        if (candidates == null || candidates.isEmpty()) {
            view.setTag(null);
            view.setImageDrawable(null);
            return;
        }
        final String key = candidates.get(0);
        view.setTag(key);
        Bitmap hit = memCache.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        final List<String> chain = candidates;
        pool.execute(() -> {
            Bitmap bmp = loadBitmap(key, chain);
            if (bmp != null) memCache.put(key, bmp);
            final Bitmap result = bmp;
            mainHandler.post(() -> {
                if (!key.equals(view.getTag())) return;
                if (result != null) {
                    view.setImageBitmap(result);
                } else if (onExhausted != null) {
                    onExhausted.run();
                }
            });
        });
    }

    private Bitmap loadBitmap(String key, List<String> chain) {
        File f = diskFile(key);
        if (f.isFile() && f.length() > 0) {
            Bitmap b = decodeFile(f);
            if (b != null) return b;
        }
        for (String url : chain) {
            // 同址最多两次：第一次拿到截断数据（代理回源不全）时重试一次，
            // 截断是瞬时故障，重试常能拿全；网络/HTTP 失败直接换下一条候选
            for (int attempt = 0; attempt < 2; attempt++) {
                byte[] data = download(url);
                if (data == null) break;
                if (!looksComplete(data)) continue;
                Bitmap b = decodeBytes(data);
                if (b != null) {
                    writeQuietly(f, data);
                    return b;
                }
                break;
            }
        }
        return null;
    }

    /**
     * 图片完整性粗验：BitmapFactory 对截断的 JPEG/PNG 不报错、只解出上半截
     * （下半截发黑/噪点），所以必须在字节层先判完整再解码、再进缓存。
     * JPEG 看结尾的 EOI（FFD9），PNG 看 IEND；其他格式不判、直接放行。
     */
    private static boolean looksComplete(byte[] d) {
        if (d.length < 4) return false;
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return tailContains(d, new byte[]{(byte) 0xFF, (byte) 0xD9}, 2048);
        }
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return tailContains(d, new byte[]{0x49, 0x45, 0x4E, 0x44,
                    (byte) 0xAE, 0x42, 0x60, (byte) 0x82}, 256);
        }
        return true;
    }

    private static boolean tailContains(byte[] d, byte[] sig, int window) {
        int from = Math.max(0, d.length - window);
        outer:
        for (int i = d.length - sig.length; i >= from; i--) {
            for (int j = 0; j < sig.length; j++) {
                if (d[i + j] != sig[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private File diskFile(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b & 0xFF));
            return new File(diskDir, sb.toString() + ".img");
        } catch (Exception e) {
            return new File(diskDir, Integer.toHexString(key.hashCode()) + ".img");
        }
    }

    private byte[] download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(9000);
            c.setRequestProperty("User-Agent", HttpFetcher.DESKTOP_UA);
            c.setRequestProperty("Referer", "https://www.ikanbot.com/");
            if (c.getResponseCode() != 200) return null;
            int declared = c.getContentLength();
            try (InputStream is = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    total += n;
                    if (total > 1_500_000) return null;
                }
                // 服务器报了长度却没读满 = 截断，当失败处理（别把半截图喂给解码器）
                if (declared > 0 && total != declared) return null;
                return bos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private Bitmap decodeBytes(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sampleSize(bounds.outWidth);
        return BitmapFactory.decodeByteArray(data, 0, data.length, opt);
    }

    private Bitmap decodeFile(File f) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sampleSize(bounds.outWidth);
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
    }

    private static int sampleSize(int width) {
        int s = 1;
        while (width / (s * 2) >= REQ_WIDTH) s *= 2;
        return s;
    }

    private void writeQuietly(File f, byte[] data) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        } catch (Exception ignored) { }
    }
}
