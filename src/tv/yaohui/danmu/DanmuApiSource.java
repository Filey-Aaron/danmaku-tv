package tv.yaohui.danmu;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 真实弹幕源：对接自建 danmu_api（兼容弹弹play v2 规范）。
 *
 * 流程：片名搜索剧集 → 选中对应第几集 → 拉该集弹幕。
 * 接口可能需要 token，放在路径前缀里：http://host:9321/{TOKEN}/api/v2/...
 */
public class DanmuApiSource implements DanmakuSource {

    private static final String TAG = "DanmuApi";
    /** 首次抓某一集时服务端要真去上游拉，8 秒根本不够，超时等于自己制造失败 */
    private static final int TIMEOUT = 25000;
    /** 候选条目标题得分低于这个值就当没搜到，免得拿同 IP 的另一部电影糊上去 */
    private static final int MIN_SCORE = 20;

    /** 同一集反复开（暂停、退出去看一眼、抖动重建）不必每次都去问服务器，公共实例有限流 */
    private static final java.util.LinkedHashMap<String, List<DanmakuItem>> CACHE =
            new java.util.LinkedHashMap<String, List<DanmakuItem>>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, List<DanmakuItem>> e) {
                    return size() > 12;
                }
            };

    /** 搜不到的也记一下，10 分钟内不再白跑一趟（这类请求最慢） */
    private static final java.util.LinkedHashMap<String, Long> MISS =
            new java.util.LinkedHashMap<String, Long>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> e) {
                    return size() > 16;
                }
            };
    private static final long MISS_TTL = 600000L;

    private final String base;      // 不带尾部斜杠
    private final String token;

    public DanmuApiSource(String base, String token) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://" + b;
        this.base = b;
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public String name() {
        return "danmu_api";
    }

    /** base + [/{token}] + path */
    private String url(String path) {
        StringBuilder sb = new StringBuilder(base);
        if (token.length() > 0 && !"87654321".equals(token)) {
            sb.append('/').append(token);
        }
        sb.append(path);
        return sb.toString();
    }

    @Override
    public void load(final MediaInfo info, final Callback cb) {
        final String cacheKey = info.key();
        synchronized (CACHE) {
            List<DanmakuItem> hit = CACHE.get(cacheKey);
            if (hit != null && !hit.isEmpty()) {
                cb.onLoaded(hit, "缓存 · " + hit.size() + " 条");
                return;
            }
        }
        synchronized (MISS) {
            Long t = MISS.get(cacheKey);
            if (t != null && System.currentTimeMillis() - t < MISS_TTL) {
                cb.onError("数据源里还没有「" + info.title + "」");
                return;
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long epId = findEpisode(info);
                    if (epId <= 0) {
                        synchronized (MISS) { MISS.put(cacheKey, System.currentTimeMillis()); }
                        cb.onError("数据源里还没收录「" + info.title + "」");
                        return;
                    }
                    List<DanmakuItem> items = fetchComments(epId);
                    if (items.isEmpty()) {
                        cb.onError("该集没有弹幕（episodeId=" + epId + "）");
                        return;
                    }
                    synchronized (CACHE) {
                        CACHE.put(cacheKey, items);
                    }
                    cb.onLoaded(items, "danmu_api · " + items.size() + " 条");
                } catch (Throwable t) {
                    Log.w(TAG, "load failed", t);
                    cb.onError(shortErr(t));
                }
            }
        }, "danmu-api").start();
    }

    /** 搜索并挑出最匹配的一集，返回 episodeId（失败返回 -1）。 */
    private long findEpisode(MediaInfo info) throws Exception {
        String kw = info.title == null ? "" : info.title.trim();
        if (kw.isEmpty()) return -1L;
        // 先按原名搜；搜不到再去掉"剧场版/电影版"这类修饰词试一次
        for (String q : searchKeys(kw)) {
            long id = findEpisodeBy(q, info);
            if (id > 0) return id;
        }
        return -1L;
    }

    /** 片名可能带平台自己的修饰词，去掉后再搜一次，命中率会高一些 */
    private static String[] searchKeys(String title) {
        String alt = title.replaceAll("[（(【\\[][^）)】\\]]*[）)】\\]]", " ")
                .replaceAll("(剧场版|电影版|TV版|OVA|特别篇|剧场|国语版|粤语版|国语|粤语|完整版|未删减|蓝光|高清|4K|HD)", " ")
                .replaceAll("\\s+", " ").trim();
        if (alt.isEmpty() || alt.equals(title)) return new String[]{title};
        return new String[]{title, alt};
    }

    private long findEpisodeBy(String kw, MediaInfo info) throws Exception {
        String u = url("/api/v2/search/episodes?anime=" + URLEncoder.encode(kw, "UTF-8"));
        JSONObject root = new JSONObject(get(u));
        JSONArray animes = root.optJSONArray("animes");
        if (animes == null) {
            JSONObject data = root.optJSONObject("data");
            if (data != null) animes = data.optJSONArray("animes");
        }
        if (animes == null || animes.length() == 0) return -1L;

        long best = -1L;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < animes.length(); i++) {
            JSONObject a = animes.optJSONObject(i);
            if (a == null) continue;
            String animeTitle = a.optString("animeTitle", "");
            int titleScore = looseScore(kw, animeTitle);
            // 片子对不上就整条跳过。否则会拿"斗破苍穹3：除恶"的弹幕盖到"双帝之战"上
            if (titleScore < MIN_SCORE) continue;
            int animeSeason = parseSeason(animeTitle);
            if (info.season > 0 && animeSeason > 0 && animeSeason != info.season) {
                titleScore -= 45;      // 我们要第 1 季，别拿第 2 季的弹幕来糊
            }
            JSONArray eps = a.optJSONArray("episodes");
            if (eps == null && a.optLong("animeId", 0) > 0) {
                // 少数实现把剧集列表放在详情接口里
                eps = bangumiEpisodes(a.optLong("animeId"));
            }
            if (eps == null) continue;
            for (int j = 0; j < eps.length(); j++) {
                JSONObject e = eps.optJSONObject(j);
                if (e == null) continue;
                long id = e.optLong("episodeId", 0);
                if (id <= 0) continue;
                int num = e.optInt("episodeNumber", -1);
                if (num < 0) num = parseNum(e.optString("episodeTitle", ""));
                int score = titleScore;
                if (info.episode > 0 && num == info.episode) score += 100;
                else if (info.episode > 0 && num > 0) score -= Math.min(50, Math.abs(num - info.episode));
                if (score > bestScore) {
                    bestScore = score;
                    best = id;
                }
            }
        }
        return best;
    }

    private JSONArray bangumiEpisodes(long animeId) {
        try {
            JSONObject o = new JSONObject(get(url("/api/v2/bangumi/" + animeId)));
            JSONObject b = o.optJSONObject("bangumi");
            if (b == null) b = o;
            JSONArray eps = b.optJSONArray("episodes");
            if (eps != null) return eps;
            JSONObject data = b.optJSONObject("data");
            return data == null ? null : data.optJSONArray("episodes");
        } catch (Throwable t) {
            return null;
        }
    }

    private List<DanmakuItem> fetchComments(long episodeId) throws Exception {
        String u = url("/api/v2/comment/" + episodeId + "?format=json&duration=true&withRelated=true");
        JSONObject root = new JSONObject(get(u));
        JSONArray arr = root.optJSONArray("comments");
        if (arr == null) {
            JSONObject data = root.optJSONObject("data");
            if (data != null) arr = data.optJSONArray("comments");
        }
        List<DanmakuItem> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            String text = clean(c.optString("m", c.optString("text", "")));
            if (text.length() == 0) continue;
            double sec = -1;
            int mode = 1;
            int color = 0xFFFFFF;
            String p = c.optString("p", "");
            if (p.length() > 0) {
                String[] f = p.split(",");
                try { sec = Double.parseDouble(f[0]); } catch (Throwable ignored) { }
                if (f.length > 1) try { mode = Integer.parseInt(f[1].trim()); } catch (Throwable ignored) { }
                if (f.length > 3 && isInt(f[3])) {
                    try { color = Integer.parseInt(f[3].trim()); } catch (Throwable ignored) { }
                }
            } else {
                sec = c.optDouble("time", -1);
            }
            if (sec < 0) continue;
            int type = (mode == 5) ? DanmakuItem.TYPE_TOP : DanmakuItem.TYPE_SCROLL;
            out.add(new DanmakuItem((long) (sec * 1000), text, 0xFF000000 | (color & 0xFFFFFF), type));
        }
        if (out.size() > 20000) {
            out = new ArrayList<>(out.subList(0, 20000));
        }
        Collections.sort(out, new Comparator<DanmakuItem>() {
            @Override
            public int compare(DanmakuItem a, DanmakuItem b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });
        return out;
    }

    private String get(String u) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "DanmuCompanionTV/0.2");
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(in);
            if (code >= 400) throw new IllegalStateException("HTTP " + code + " " + trim(body, 80));
            return body;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String shortErr(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        if (t instanceof java.net.ConnectException) m = "连不上服务器（地址/端口对吗）";
        else if (t instanceof java.net.SocketTimeoutException) m = "请求超时";
        else if (t instanceof java.net.UnknownHostException) m = "域名解析失败";
        else if (t instanceof org.json.JSONException) m = "返回的不是预期 JSON：" + trim(m, 60);
        return m;
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /** 片名相似度：忽略季/集、空格、标点，包含关系给高分 */
    private static int looseScore(String a, String b) {
        String x = norm(a), y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        if (x.equals(y)) return 60;
        if (y.contains(x) || x.contains(y)) return 40;
        int common = 0;
        for (int i = 0; i < x.length(); i++) if (y.indexOf(x.charAt(i)) >= 0) common++;
        return common * 10 / Math.max(x.length(), 1);
    }

    private static String norm(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static boolean isInt(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    private static final String[] CN_NUM = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    /** 从标题里抽季号：第2季 / 第二季 / Season 2 / S02 */
    private static int parseSeason(String title) {
        if (title == null) return 0;
        int i = title.indexOf("第");
        while (i >= 0) {
            int j = i + 1;
            StringBuilder num = new StringBuilder();
            while (j < title.length() && (Character.isDigit(title.charAt(j)) || "一二三四五六七八九十".indexOf(title.charAt(j)) >= 0)) {
                num.append(title.charAt(j));
                j++;
            }
            if (j < title.length() && title.charAt(j) == '季' && num.length() > 0) {
                return cnToInt(num.toString());
            }
            i = title.indexOf("第", i + 1);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)(?:season\\s*|s)(\\d{1,2})(?:\\s*e\\d+)?\\b").matcher(title);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) { }
        }
        return 0;
    }

    private static int cnToInt(String s) {
        if (isInt(s)) {
            try { return Integer.parseInt(s); } catch (Throwable ignored) { return 0; }
        }
        int ten = s.indexOf('十');
        if (ten >= 0) {
            int hi = ten == 0 ? 1 : cnDigit(s.charAt(0));
            int lo = ten == s.length() - 1 ? 0 : cnDigit(s.charAt(ten + 1));
            return hi * 10 + lo;
        }
        return s.length() == 1 ? cnDigit(s.charAt(0)) : 0;
    }

    private static int cnDigit(char c) {
        for (int i = 0; i < CN_NUM.length; i++) if (CN_NUM[i].charAt(0) == c) return i;
        return 0;
    }

    /** 有的源正文带零宽空格、heights 空格、尾巴上的 ♡12 计数，清掉免得渲染成怪符号 */
    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u200a' || c == '\u200b' || c == '\u200c' || c == '\u200d'
                    || c == '\uFEFF' || c == '\u00a0' || c < 0x20) continue;
            sb.append(c);
        }
        String out = sb.toString().trim();
        // 结尾的 ♡数字 是优酷的点赞数，不是弹幕内容
        out = out.replaceAll("[\\u2661\\u2665]\\s*\\d+$", "").trim();
        return out;
    }

    private static int parseNum(String s) {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) d.append(c);
            else if (d.length() > 0) break;
        }
        try { return d.length() == 0 ? -1 : Integer.parseInt(d.toString()); }
        catch (Throwable t) { return -1; }
    }
}
