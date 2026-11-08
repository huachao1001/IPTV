package com.iptv.live.source;

import com.iptv.live.model.Channel;
import com.iptv.live.model.ChannelGroups;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** 频道数据仓库：本地缓存 + 多镜像拉取刷新。 */
public final class ChannelStore {

    /**
     * 默认数据源：best-fan/iptv-sources 的清单。
     *
     * <p><b>换成它的理由是"清晰度"。</b>上一版用的 mzky/checklist 虽然线路多
     * （519 条），但<b>一个清晰度标记都没有</b>，于是 {@link Channel#rank(String)}
     * 全部返回 0，{@link Channel#sortBySpeed()} 退化成一味按 response-time 排，
     * 永远命中最快的那批 720×576 小机房——画面一直是低清，这才是根因。
     *
     * <p>best-fan 的条目 292/314 带 {@code [1080]/[720]/[576]} 标记，
     * 解析后 {@code Channel.Source.quality} 直接可用，选源就能按档位走。
     * 实测标记可信：{@code 222.223.41.27} 的 [1080] 线路解析出 1920×1080。
     */
    public static final String DEFAULT_SOURCE =
            "https://cdn.jsdelivr.net/gh/best-fan/iptv-sources@master/cn_all_status.m3u8";

    /** 按顺序尝试的远程源，前一个失败自动换下一个。 */
    private static final String[] DEFAULT_SOURCES = {
            "https://cdn.jsdelivr.net/gh/best-fan/iptv-sources@master/cn_all_status.m3u8",
            "https://raw.githubusercontent.com/best-fan/iptv-sources/master/cn_all_status.m3u8",
            "https://gh-proxy.com/https://raw.githubusercontent.com/best-fan/iptv-sources/master/cn_all_status.m3u8",
            // 兜底：清晰度标记缺失，但线路更多、更新更勤
            "https://cdn.jsdelivr.net/gh/mzky/checklist@master/itvlist.m3u",
    };

    private static final String CACHE = "channels.m3u8";
    private static final String PREFS = "iptv";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final Context ctx;

    public ChannelStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private File cacheFile() {
        return new File(ctx.getFilesDir(), CACHE);
    }

    /** 缓存写入时间；空文件视为没有缓存，否则会误判"刚更新过"而永不刷新。 */
    public long lastUpdated() {
        File f = cacheFile();
        return f.length() > 0 ? f.lastModified() : 0L;
    }

    public boolean isStale() {
        return System.currentTimeMillis() - lastUpdated() > DAY_MS;
    }

    public String lastSource() {
        return prefs().getString("source", "");
    }

    /** 用户自定义源，空表示走默认镜像列表。 */
    public String customSource() {
        return prefs().getString("custom", "");
    }

    public void setCustomSource(String url) {
        prefs().edit().putString("custom", url == null ? "" : url).apply();
    }

    private String[] sources() {
        String custom = customSource();
        if (custom.isEmpty()) {
            return DEFAULT_SOURCES;
        }
        // 自定义源失败时仍回落到默认镜像，避免配置写错导致完全不可用
        String[] all = new String[DEFAULT_SOURCES.length + 1];
        all[0] = custom;
        System.arraycopy(DEFAULT_SOURCES, 0, all, 1, DEFAULT_SOURCES.length);
        return all;
    }


    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读缓存；缓存不可用时退回安装包内置的完整清单。 */
    public List<Channel> load() throws IOException {
        File f = cacheFile();
        InputStream in = null;
        // 空文件等于没有缓存：早期版本留下过 0 字节文件，必须跳过，
        // 否则会一直用不上内置的多线路清单
        if (f.length() > 0) {
            in = new java.io.FileInputStream(f);
        }
        if (in == null) {
            in = ctx.getAssets().open("seed.m3u8");
        }
        try {
            List<Channel> list = PlaylistParser.parse(in);
            if (list.isEmpty()) {
                throw new IOException("播放列表为空");
            }
            applyRemembered(list);
            // 规则 6：把已被真实播放证伪的线路剔除掉，换台时不再反复踩死源。
            // 必须放在"频道至少留一条"的前提下剔除，否则整台会从列表里消失
            dropDead(list);
            return list;
        } finally {
            in.close();
        }
    }

    /**
     * 剔除 {@link DeadSources} 里记录的坏线路。
     *
     * <p>每条线路都死时**保留第一条**：宁可让用户看到"该频道暂时无法播放"，
     * 也好过频道直接从节目单里蒸发——后者会让分组里少一个台，
     * 用户以为节目单坏了。分批删而不是边遍历边删，避免迭代器失效。
     */
    private void dropDead(List<Channel> list) {
        for (Channel c : list) {
            if (c.sources.size() <= 1) {
                continue;
            }
            List<Channel.Source> alive = new java.util.ArrayList<>(c.sources.size());
            for (Channel.Source s : c.sources) {
                if (!DeadSources.isDead(ctx, s.url)) {
                    alive.add(s);
                }
            }
            if (alive.isEmpty()) {
                continue;
            }
            if (alive.size() != c.sources.size()) {
                android.util.Log.i("IPTVSTORE", "drop-dead ch=" + c.name
                        + " removed=" + (c.sources.size() - alive.size())
                        + " left=" + alive.size());
                c.sources.clear();
                c.sources.addAll(alive);
            }
        }
    }

    /** 用实测分辨率补全节目单里缺失的清晰度。 */
    private void applyRemembered(List<Channel> list) {
        for (Channel c : list) {
            for (Channel.Source s : c.sources) {
                // 只补"实测值"，不动节目单声明的档位：
                // 档位参与清晰度上限判定，写坏了下限就失效
                if (s.measured == null || s.measured.isEmpty()) {
                    String known = QualityMemory.get(ctx, s.url);
                    if (known != null) {
                        s.measured = known;
                    }
                }
            }
        }
    }

    /**
     * 从远程拉取并覆盖缓存。
     *
     * @return 拉取到的频道数；全部源失败时抛异常。
     */
    public int refresh() throws IOException {
        IOException last = null;
        for (String src : sources()) {
            try {
                byte[] body = download(src);                String text = new String(body, "UTF-8");
                if (!text.contains("#EXTINF")) {
                    throw new IOException("响应不含频道数据");
                }
                // 先写临时文件，成功后再替换，避免半截文件把旧数据冲掉
                File tmp = new File(ctx.getFilesDir(), CACHE + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    out.write(body);
                    out.flush();
                    out.getFD().sync();
                } finally {
                    out.close();
                }
                int count = PlaylistParser.parse(new java.io.ByteArrayInputStream(body)).size();
                if (count == 0) {
                    tmp.delete();
                    throw new IOException("解析结果为空");
                }
                if (!tmp.renameTo(cacheFile())) {
                    tmp.delete();
                    throw new IOException("写入缓存失败");
                }
                prefs().edit()
                        .putString("source", src)
                        .putLong("updatedAt", System.currentTimeMillis())
                        .apply();
                return count;
            } catch (IOException e) {
                last = e;
                android.util.Log.w("IPTVSTORE", "refresh " + src + " -> " + e);
            }
        }
        throw last != null ? last : new IOException("没有可用源");
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "IptvLive/1.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) {
                buf.write(b, 0, n);
            }
            in.close();
            return buf.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    /** 频道排序：按 ChannelGroups 的分组顺序，组内保持源文件顺序。 */
    public static void sort(List<Channel> list) {
        final List<String> order = ChannelGroups.order();
        // List.sort(Comparator) 是 API 24 才加的，Android 6(API 23) 上会 NoSuchMethodError，
        // 必须用 Collections.sort 兼容 21+。
        Collections.sort(list, new Comparator<Channel>() {
            @Override
            public int compare(Channel a, Channel b) {
                int ia = indexOf(order, a.group);
                int ib = indexOf(order, b.group);
                if (ia != ib) {
                    return ia - ib;
                }
                // 同组内 CCTV-2 排在 CCTV-10 前面
                return compareChannel(a.group, a.name, b.name);
            }
        });
    }

    /** 同组内按频道号自然排序：CCTV-2 < CCTV-10。 */
    private static int compareChannel(String group, String a, String b) {
        if (group.startsWith("央视")) {
            int na = cctvNumber(a);
            int nb = cctvNumber(b);
            if (na >= 0 && nb >= 0 && na != nb) {
                return na - nb;
            }
        }
        return 0;
    }

    /** 从 CCTV-5+ 之类的名字里取频道号。 */
    private static int cctvNumber(String name) {
        String digits = name.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int indexOf(List<String> order, String group) {
        int i = order.indexOf(group);
        return i < 0 ? order.size() : i;
    }
}
