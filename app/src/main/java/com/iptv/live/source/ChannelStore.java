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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        boolean fromCache = false;
        // 空文件等于没有缓存：早期版本留下过 0 字节文件，必须跳过，
        // 否则会一直用不上内置的多线路清单
        if (f.length() > 0) {
            in = new java.io.FileInputStream(f);
            fromCache = true;
        }
        if (in == null) {
            in = ctx.getAssets().open("seed.m3u8");
        }
        try {
            List<Channel> list = PlaylistParser.parse(in);
            if (list.isEmpty()) {
                throw new IOException("播放列表为空");
            }
            // 远程清单比内置清单薄得多（实测 256 条 vs 753 条，
            // 广东卫视只剩 1 条、湖北卫视只剩 1 条），备胎被削光之后
            // 一旦命中的是错标线路就没有别的可退。先用内置清单把线路补回来。
            if (fromCache) {
                mergeSeed(list);
            }
            // 再给每条线路打分档：见 assignTrust()
            assignTrust(list);
            // 打完分重排一次，让可信线路排到最前
            for (Channel c : list) {
                c.sortBySpeed();
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
     * 用安装包内置的 {@code seed.m3u8} 给远程清单补线路。
     *
     * <p><b>为什么必须补：</b>远程 {@code cn_all_status.m3u8} 只有 256 条线路
     * / 57 个频道，而内置清单有 753 条 / 135 个频道。同一个台远程只给一条线，
     * 内置给十几条。远程那条一旦是错标的（实测广东卫视那条播的是 CCTV-11），
     * 用户就没有第二条可选，"名字与实际内容不一致"会一直卡在那儿。
     *
     * <p>只<b>补充</b>不替换：远程线路保持原顺序在前（它带最新的
     * {@code response-time} 和清晰度标记），内置的按 URL 去重后追加在后。
     */
    private void mergeSeed(List<Channel> list) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open("seed.m3u8");
            List<Channel> seed = PlaylistParser.parse(in);
            Map<String, Channel> byName = new HashMap<>();
            for (Channel c : list) {
                byName.put(c.name, c);
            }
            int added = 0;
            for (Channel s : seed) {
                Channel target = byName.get(s.name);
                if (target == null) {
                    continue;
                }
                Set<String> have = new HashSet<>();
                for (Channel.Source x : target.sources) {
                    have.add(x.url);
                }
                for (Channel.Source x : s.sources) {
                    if (have.add(x.url)) {
                        target.sources.add(x);
                        added++;
                    }
                }
            }
            if (added > 0) {
                android.util.Log.i("IPTVSTORE", "merge-seed added=" + added
                        + " sources=" + countSources(list));
            }
        } catch (IOException e) {
            // 补不上不影响主流程，远程清单本身仍可用
            android.util.Log.w("IPTVSTORE", "merge-seed failed: " + e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 所有频道的线路总数，只用于日志。 */
    private static int countSources(List<Channel> list) {
        int n = 0;
        for (Channel c : list) {
            n += c.sources.size();
        }
        return n;
    }

    /**
     * 给每条线路打可信度分档，见 {@link Channel.Source#trust}。
     *
     * <p><b>这是"名字与实际内容不一致"的解法。</b>实测上游清单
     * {@code best-fan/iptv-sources} 里：选「山东卫视」播英超、
     * 选「广东卫视」播 CCTV-11 戏曲、选「黑龙江卫视」播一部美剧。
     * 本地缓存与远端 md5 完全一致、解析也没错——是<b>清单源头标错了</b>。
     *
     * <p><b>判定只用清单自身携带的第二份信息</b>，不靠速度、不靠外部识别
     * （国内 IPTV 流的 SDT 表只有 {@code Service01}，流里读不出台名）：
     *
     * <p>1. {@code URL 自带台名代号} —— 既是最强的正向证据，也是最强的反向证据。
     * 这类厂商把台名写进了地址：{@code ?id=sdwshd}、{@code /live/hnws.m3u8}、
     * {@code /live/cctv4hd.m3u8}。实测 120 条带代号的线路中 119 条与清单名字
     * 一致、仅 1 条矛盾（{@code sxwshd} 是山西卫视却写在陕西卫视名下）。
     * 一致 ⇒ {@link Channel#TRUST_VERIFIED}，矛盾 ⇒ {@link Channel#TRUST_SUSPECT}。
     *
     * <p>2. {@code 频道号槽位被多个台名共用} —— 弱证据，只用于判可疑。
     * 同一台机器的同一个频道号不可能是两个台，
     * {@code /newlive/live/hls/34} 被写成"山东卫视"和"广东卫视"、
     * {@code /hls/6/index.m3u8} 被写成"CCTV-5+"和"CCTV-6"。
     */
    private void assignTrust(List<Channel> list) {
        // 槽位 -> 引用它的频道名集合
        Map<String, Set<String>> slots = new HashMap<>();
        for (Channel c : list) {
            for (Channel.Source s : c.sources) {
                String slot = slotOf(s.url);
                if (slot == null) {
                    continue;
                }
                Set<String> set = slots.get(slot);
                if (set == null) {
                    set = new HashSet<>();
                    slots.put(slot, set);
                }
                set.add(c.name);
            }
        }

        int verified = 0;
        int unknown = 0;
        int suspect = 0;
        for (Channel c : list) {
            for (Channel.Source s : c.sources) {
                String expect = expectedName(s.url);
                if (expect != null) {
                    if (expect.equals(c.name)) {
                        s.trust = Channel.TRUST_VERIFIED;
                        verified++;
                    } else {
                        s.trust = Channel.TRUST_SUSPECT;
                        suspect++;
                    }
                    continue;
                }
                // 没有代号：能不能从"槽位共用"看出问题
                String slot = slotOf(s.url);
                Set<String> set = slot != null ? slots.get(slot) : null;
                if (set != null && set.size() > 1) {
                    s.trust = Channel.TRUST_SUSPECT;
                    suspect++;
                } else {
                    s.trust = Channel.TRUST_UNKNOWN;
                    unknown++;
                }
            }
        }
        android.util.Log.i("IPTVSTORE", "trust verified=" + verified
                + " unknown=" + unknown + " suspect=" + suspect);
    }

    /**
     * 从 URL 里读出厂商写进去的台名代号，换算成清单里的频道名。
     *
     * <p>只认能<b>无歧义</b>换算的形态，认不出来一律返回 {@code null}
     * ——宁可少判，不能误判。已覆盖两类：
     * <ul>
     *   <li>{@code ?id=cctv2hd} / {@code /live/cctv4.m3u8} / {@code /720p/cctv11.m3u8}</li>
     *   <li>{@code /live/bjwshd.m3u8} / {@code /cdnlive/hnws.m3u8}（省份缩写 + ws）</li>
     * </ul>
     */
    private static String expectedName(String url) {
        if (url == null) {
            return null;
        }
        String slug = null;
        int q = url.indexOf("id=");
        if (q > 0) {
            int end = q + 3;
            while (end < url.length()) {
                char ch = url.charAt(end);
                if (!Character.isLetterOrDigit(ch)) {
                    break;
                }
                end++;
            }
            slug = url.substring(q + 3, end);
        }
        if (slug == null || slug.isEmpty()) {
            // 取最后一段文件名，去掉 .m3u8 和常见画质后缀
            String path = pathOf(url);
            int slash = path.lastIndexOf('/');
            String file = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = file.indexOf('.');
            if (dot > 0) {
                file = file.substring(0, dot);
            }
            slug = file;
        }
        if (slug == null || slug.isEmpty()) {
            return null;
        }
        String s = slug.toLowerCase(java.util.Locale.US);
        // 去掉画质后缀，注意 cctv5plus 不能把 plus 当后缀切掉
        if (s.endsWith("fhd") || s.endsWith("uhd")) {
            s = s.substring(0, s.length() - 3);
        } else if (s.endsWith("hd") || s.endsWith("sd")) {
            s = s.substring(0, s.length() - 2);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^cctv(1[0-9]|[1-9])(plus)?$").matcher(s);
        if (m.matches()) {
            return "CCTV-" + m.group(1) + (m.group(2) != null ? "+" : "");
        }
        m = java.util.regex.Pattern.compile("^cetv([1-9][0-9]?)$").matcher(s);
        if (m.matches()) {
            return "CETV-" + m.group(1);
        }
        m = java.util.regex.Pattern.compile("^([a-z]{2,3})ws$").matcher(s);
        if (m.matches()) {
            String prov = PROVINCE.get(m.group(1));
            return prov == null ? null : prov + "卫视";
        }
        return null;
    }

    /** 卫视拼音缩写 -> 省名。只收无歧义的，拿不准的不收。 */
    private static final Map<String, String> PROVINCE = new HashMap<>();

    static {
        PROVINCE.put("bj", "北京");
        PROVINCE.put("tj", "天津");
        PROVINCE.put("sh", "上海");
        PROVINCE.put("cq", "重庆");
        PROVINCE.put("he", "河北");
        PROVINCE.put("sx", "山西");
        PROVINCE.put("sn", "陕西");
        PROVINCE.put("ln", "辽宁");
        PROVINCE.put("jl", "吉林");
        PROVINCE.put("hlj", "黑龙江");
        PROVINCE.put("js", "江苏");
        PROVINCE.put("zj", "浙江");
        PROVINCE.put("ah", "安徽");
        PROVINCE.put("fj", "福建");
        PROVINCE.put("jx", "江西");
        PROVINCE.put("sd", "山东");
        PROVINCE.put("ha", "河南");
        PROVINCE.put("hb", "湖北");
        PROVINCE.put("hn", "湖南");
        PROVINCE.put("gd", "广东");
        PROVINCE.put("gx", "广西");
        PROVINCE.put("sc", "四川");
        PROVINCE.put("gz", "贵州");
        PROVINCE.put("yn", "云南");
        PROVINCE.put("xz", "西藏");
        PROVINCE.put("gs", "甘肃");
        PROVINCE.put("qh", "青海");
        PROVINCE.put("nx", "宁夏");
        PROVINCE.put("xj", "新疆");
        PROVINCE.put("nm", "内蒙古");
        PROVINCE.put("ss", "三沙");
        PROVINCE.put("bt", "兵团");
        PROVINCE.put("yb", "延边");
        PROVINCE.put("sz", "深圳");
        PROVINCE.put("df", "东方");
    }

    /**
     * 取出 URL 里"频道号槽位"的部分，用于判断两个台名是否指向同一路流。
     *
     * <p>只认厂商把频道编成号的两种形态，它们在同一台机器内一定是唯一的：
     * <ul>
     *   <li>{@code /newlive/live/hls/<n>/live.m3u8}</li>
     *   <li>{@code /hls/<n>/index.m3u8}</li>
     * </ul>
     * <b>刻意不包含 {@code /tsfile/live/<id>_<n>}</b>：那套编号在不同机房
     * 完全不对应（同一 {@code 1018_1} 在四个机房分别是 CCTV-2/4/8/天津卫视），
     * 拿它判共用会大面积误判。
     */
    private static String slotOf(String url) {
        String p = pathOf(url);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/newlive/live/hls/(\\d+)/").matcher(p);
        if (m.find()) {
            return "newlive:" + m.group(1);
        }
        m = java.util.regex.Pattern.compile("^/hls/(\\d+)/index\\.m3u8").matcher(p);
        if (m.find()) {
            return "hlsidx:" + m.group(1);
        }
        return null;
    }

    /** 去掉协议和 host/端口，只留路径。用来识别同一路流被挂了多个台名。 */
    private static String pathOf(String url) {
        if (url == null) {
            return "";
        }
        int i = url.indexOf("://");
        if (i > 0) {
            int slash = url.indexOf('/', i + 3);
            if (slash > 0) {
                return url.substring(slash);
            }
        }
        return url;
    }

    /**
     * 剔除"确实无效"的线路，两类证据：
     *
     * <p>1. {@link DeadSources}：真播过、但明确播不了（起播超时、反复卡顿、分片跳号）。
     * <p>2. {@link SourceHealth}：长期没有任何存活证据——既不在最近下载的清单里，
     * 也很久没真的播出画面。这是"滚雪球"策略唯一的减法：
     * 旧线路默认保留，只有时间和播放证据都说明它没了才淘汰。
     *
     * <p>每条线路都死时**保留第一条**：宁可让用户看到"该频道暂时无法播放"，
     * 也好过频道直接从节目单里蒸发——后者会让分组里少一个台，
     * 用户以为节目单坏了。分批删而不是边遍历边删，避免迭代器失效。
     */
    private void dropDead(List<Channel> list) {
        // 只有"最近确实成功拉到过新清单"时，才允许用 SourceHealth 淘汰旧线路。
        //
        // 理由：seen 时间戳只在下载成功时更新。电视连不上 GitHub（实测
        // jsDelivr/raw/gh-proxy 全部失败）时 seen 永远不动，
        // 若照样按 14 天淘汰，会把一整个月没看的台悄悄删掉——
        // 那不是"剔除无效"，是"没联网就清空"。拿不到新证据时一律保留。
        final boolean fresh = System.currentTimeMillis()
                - prefs().getLong("updatedAt", 0L) < SourceHealth.retireWindowMs();
        int dead = 0;
        int stale = 0;
        for (Channel c : list) {
            if (c.sources.size() <= 1) {
                continue;
            }
            List<Channel.Source> alive = new java.util.ArrayList<>(c.sources.size());
            int d = 0;
            int t = 0;
            for (Channel.Source s : c.sources) {
                if (DeadSources.isDead(ctx, s.url)) {
                    d++;
                    SourceHealth.forget(ctx, s.url);
                    continue;
                }
                if (fresh && SourceHealth.retired(ctx, s.url)) {
                    t++;
                    SourceHealth.forget(ctx, s.url);
                    continue;
                }
                alive.add(s);
            }
            if (alive.isEmpty()) {
                continue;
            }
            if (alive.size() != c.sources.size()) {
                dead += d;
                stale += t;
                c.sources.clear();
                c.sources.addAll(alive);
            }
        }
        if (dead + stale > 0) {
            android.util.Log.i("IPTVSTORE", "drop dead=" + dead
                    + " stale=" + stale + " left=" + countSources(list));
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
     * 从远程拉取清单，<b>与本地旧缓存、内置清单合并后</b>再写回缓存。
     *
     * <p><b>为什么不能直接覆盖。</b>上游清单每次刷新都会掉一批线路——
     * 某个机房这会儿抽风、某台机器临时下线，下一次刷新它们又可能回来。
     * 直接覆盖等于把"以前验证过、只是这次没被列出来"的好线路一起扔掉，
     * 频道和线路只会越刷越少，最后剩几个坏源。用户要求的是相反的方向：
     * <b>旧的要留着，只有确实无效的才剔除，让频道像滚雪球一样越滚越多</b>。
     *
     * <p>合并顺序即优先级：<b>远程 &gt; 旧缓存 &gt; 内置清单</b>。
     * 远程的线路排在最前（带最新的 {@code response-time} 与清晰度标记），
     * 旧缓存补上远程这次没给的，内置清单补上台名对得上但两边都缺的。
     *
     * <p>淘汰只发生在 {@link #load()}：那里按 {@link SourceHealth} 的记录，
     * 把"既不在新清单里、又很久没播成功过"的线路剔除。
     * 也就是说，本次没被列出来的线路<b>不会立刻消失</b>，
     * 还要再等等看它会不会回来——判死必须有时间证据，不能凭一次缺席。
     *
     * @return 合并后的频道数；全部源失败时抛异常（此时旧缓存原样保留）。
     */
    public int refresh() throws IOException {
        IOException last = null;
        for (String src : sources()) {
            try {
                byte[] body = download(src);
                String text = new String(body, "UTF-8");
                if (!text.contains("#EXTINF")) {
                    throw new IOException("响应不含频道数据");
                }
                List<Channel> remote = PlaylistParser.parse(
                        new java.io.ByteArrayInputStream(body));
                if (remote.isEmpty()) {
                    throw new IOException("解析结果为空");
                }
                // 新清单里出现过的线路记一笔：它还在上游挂着，说明大概率还活着
                List<String> seen = new ArrayList<>();
                for (Channel c : remote) {
                    for (Channel.Source s : c.sources) {
                        seen.add(s.url);
                    }
                }
                SourceHealth.markSeenAll(ctx, seen);
                List<Channel> merged = mergeWithHistory(remote);
                writeCache(merged);
                prefs().edit()
                        .putString("source", src)
                        .putLong("updatedAt", System.currentTimeMillis())
                        .apply();
                // 老线路逐条验证：把"新清单这次没给、但我们保留下来"的线路
                // 挑出来做一次连通性体检，取不到的交 DeadSources 判死。
                // 这就是用户要的"每次都验证旧的、去掉旧的无效线路"——
                // 保留不是无条件的保留，而是每条都在被持续验证。
                SourceVerifier.verifyAsync(ctx, staleSources(merged, seen));
                return merged.size();
            } catch (IOException e) {
                last = e;
                android.util.Log.w("IPTVSTORE", "refresh " + src + " -> " + e);
            }
        }
        throw last != null ? last : new IOException("没有可用源");
    }

    /**
     * 把新清单与旧缓存、内置清单按 URL 合并成一个"只增不减"的完整列表。
     *
     * <p>频道的归属以<b>先出现的那个</b>为准（远程最新，所以远程说了算）；
     * 只在旧缓存里有的频道也保留下来——上游删了某个台不代表用户不再需要它。
     * 频道元信息（分组、台标）缺了才从后面补，已有的不覆盖，
     * 免得一次上游写错分组就把整台搬走。
     */
    private List<Channel> mergeWithHistory(List<Channel> remote) {
        java.util.LinkedHashMap<String, Channel> merged = new java.util.LinkedHashMap<>();
        int before = countSources(remote);
        absorb(merged, remote);
        // 旧缓存：远程这次没给的线路全靠它留住
        try {
            File f = cacheFile();
            if (f.length() > 0) {
                absorb(merged, PlaylistParser.parse(new java.io.FileInputStream(f)));
            }
        } catch (IOException e) {
            android.util.Log.w("IPTVSTORE", "merge-cache failed: " + e);
        }
        // 内置清单：台名对得上、但远程和旧缓存都没有的线路
        InputStream in = null;
        try {
            in = ctx.getAssets().open("seed.m3u8");
            absorb(merged, PlaylistParser.parse(in));
        } catch (IOException e) {
            android.util.Log.w("IPTVSTORE", "merge-seed failed: " + e);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
        List<Channel> list = new ArrayList<>(merged.values());
        int after = countSources(list);
        if (after != before) {
            android.util.Log.i("IPTVSTORE", "snowball remote=" + before
                    + " merged=" + after + " kept=" + (after - before)
                    + " channels=" + list.size());
        }
        return list;
    }

    /**
     * 把 {@code from} 里的频道和线路并入 {@code into}，同名频道合流、同 URL 去重。
     *
     * <p>只在目标频道缺该字段时才补分组/台标/清晰度，不覆盖已有的：
     * 先并入的是新清单，它的值最新也最准。
     */
    private static void absorb(java.util.LinkedHashMap<String, Channel> into,
                               List<Channel> from) {
        if (from == null) {
            return;
        }
        for (Channel c : from) {
            if (c.name == null || c.name.isEmpty()) {
                continue;
            }
            Channel target = into.get(c.name);
            if (target == null) {
                target = new Channel(c.name, c.group, c.logo);
                into.put(c.name, target);
            } else {
                if (target.logo == null || target.logo.isEmpty()) {
                    target.logo = c.logo;
                }
            }
            Set<String> have = new HashSet<>();
            for (Channel.Source x : target.sources) {
                have.add(x.url);
            }
            for (Channel.Source x : c.sources) {
                if (!have.add(x.url)) {
                    continue;
                }
                Channel.Source copy = new Channel.Source(x.url);
                copy.quality = x.quality;
                copy.measured = x.measured;
                copy.responseMs = x.responseMs;
                target.sources.add(copy);
            }
        }
    }

    /**
     * 挑出"这次新清单没给、但是我们保留下来了"的老线路，交给后台体检。
     *
     * <p>这是"滚雪球"的验证环节：保留旧线路不等于不管它，
     * 每条老线路都会被持续重新确认。上游还给的（{@code seen}）
     * 不重复测——新清单本身就是上游的存活证明，没必要再多发几百个请求。
     */
    private static List<String> staleSources(List<Channel> merged, List<String> fresh) {
        Set<String> inRemote = new HashSet<>(fresh);
        List<String> out = new ArrayList<>();
        for (Channel c : merged) {
            for (Channel.Source s : c.sources) {
                if (!inRemote.contains(s.url)) {
                    out.add(s.url);
                }
            }
        }
        return out;
    }

    /** 把合并结果写成 M3U 落到缓存；先写临时文件再原子替换，避免半截文件。 */
    private void writeCache(List<Channel> list) throws IOException {
        File tmp = new File(ctx.getFilesDir(), CACHE + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            PlaylistWriter.write(list, out);
            out.flush();
            // fsync 只是"尽量把页缓存刷到闪存"，失败不该让整次刷新作废。
            //
            // 实测模拟器（API 23，ext4 over 虚拟磁盘）上 getFD().sync() 会抛
            // SyncFailedException，而文件内容其实已经完整写进去了：
            // 日志里 snowball 合并成功、紧跟着 sync failed，于是结果被丢掉、
            // 继续试下一个镜像，最后全部镜像轮完报"刷新失败"——
            // 一次成功的合并被一个可选的持久化动作废掉。所以降级为仅记录。
            try {
                out.getFD().sync();
            } catch (java.io.SyncFailedException e) {
                android.util.Log.w("IPTVSTORE", "cache fsync skipped: " + e);
            }
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
        if (!tmp.renameTo(cacheFile())) {
            // 部分文件系统（含模拟器的 FUSE 层）上 renameTo 到已存在的目标会失败，
            // 删掉旧文件再试一次，仍不行就退化为直接拷贝内容覆盖。
            File target = cacheFile();
            if (target.exists() && !target.delete()) {
                android.util.Log.w("IPTVSTORE", "cache replace: cannot delete old");
            }
            if (!tmp.renameTo(target)) {
                java.io.FileInputStream in = new java.io.FileInputStream(tmp);
                try {
                    FileOutputStream copy = new FileOutputStream(target);
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            copy.write(buf, 0, n);
                        }
                        copy.flush();
                    } finally {
                        copy.close();
                    }
                } finally {
                    in.close();
                    tmp.delete();
                }
                if (target.length() == 0) {
                    throw new IOException("写入缓存失败");
                }
            }
        }
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
