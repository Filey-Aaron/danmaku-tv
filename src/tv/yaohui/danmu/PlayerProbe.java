package tv.yaohui.danmu;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用播放器识别。
 *
 * VidHub 可以写死 viewId（play_item_title_top / text_left_time / text_right_time），
 * 别的播放器没有这些 id，就只能看节点树里"文本长什么样"：
 *  - 进度：形如 12:34 / 1:02:03，同一个文本里用 / | · 连起来的优先
 *  - 片名：顶部区域、不像按钮、不像时间、不带标点数字的那些文本
 *
 * 这套是猜的，所以只做"够用"的猜测：猜不到就返回 null，让上层用窗口标题或缓存顶上，
 * 绝不乱锚时间轴（错误的锚点会让整集弹幕错位，比没有更糟）。
 */
public class PlayerProbe {

    public static class Result {
        public String title;        // 猜到的片名，null = 没猜到
        public long posMs = -1L;    // 当前进度
        public long durMs = -1L;    // 总时长
        public boolean hasTimes;    // 是否读到可用的进度文本（当作"控制栏弹出来了"）
        /**
         * 像不像在放视频。音乐 App 也有进度条和总时长，靠"总时长至少 5 分钟"把它们挡掉：
         * 电视剧/电影/番剧基本都超过这个数，单曲很少超过。
         */
        public boolean videoLike;
        public String sample = "";  // 采样到的文本，出问题时看日志
    }

    /** 单个时间：12:34 或 1:02:03（小时位允许 1-3 位，有人会看到 100:00） */
    private static final Pattern TIME = Pattern.compile("(\\d{1,3}:\\d{2}(?::\\d{2})?)");
    /** 进度条那种成对写法：12:34 / 45:00 */
    private static final Pattern PAIR = Pattern.compile(
            "(\\d{1,3}:\\d{2}(?::\\d{2})?)\\s*[/|·]\\s*(\\d{1,3}:\\d{2}(?::\\d{2})?)");
    /** S01E02 / 第 3 集 */
    private static final Pattern EP = Pattern.compile(
            "(?i)([se]\\d{1,2}[ex]?\\d{0,3}|第\\s*\\d{1,4}\\s*[集话期季篇])");
    private static final Pattern NUM_ONLY = Pattern.compile("^[\\d\\s:.,%/×xX+\\-]+$");
    private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.|\\.(com|cn|net|tv)\\b)");
    /** 只有季/集号的文本（"第 1 季""第 12 集"）—— 选集面板里满地都是，绝不是片名 */
    private static final Pattern ONLY_EP = Pattern.compile(
            "^\\s*第?\\s*\\d{1,4}\\s*[季集期话篇]\\s*$|^\\s*[Ss]\\d{1,2}\\s*$");
    /**
     * 输入源/技术参数，长得像片名但根本不是：
     * 电视播 HDMI 时窗口里就写着 "HDMI1 / 3840x2160@160Hz"，拿去搜弹幕只会白跑。
     */
    private static final Pattern TECH = Pattern.compile(
            "(?i)(hdmi|vga|分量|av\\s*\\d|\\d{3,4}\\s*[x×*]\\s*\\d{3,4}|\\d{2,3}\\s*hz"
                    + "|分辨率|输入源|外部源|信号源|无信号|待机|电视直播|atv|dtv|usb\\s*\\d?)");

    /** 控制栏按钮/选项的文字，绝不能当片名 */
    private static final String[] JUNK = {
            "播放", "暂停", "继续", "下一集", "上一集", "下一首", "上一首", "字幕", "字幕设置",
            "音轨", "配音", "语言", "倍速", "速度", "清晰度", "画质", "分辨率", "设置", "更多",
            "投屏", "全屏", "退出", "返回", "选集", "剧集", "列表", "跳过", "片头", "片尾",
            "收藏", "分享", "下载", "搜索", "登录", "注册", "会员", "弹幕", "特效", "画面",
            "声音", "音量", "设备", "网络", "帮助", "关于", "详情", "简介", "演员", "相关",
            "play", "pause", "resume", "next", "prev", "previous", "subtitle", "subtitles",
            "audio", "track", "tracks", "speed", "quality", "resolution", "settings",
            "setting", "more", "fullscreen", "exit", "back", "skip", "episode", "season",
            "cast", "info", "share", "download", "search", "login", "menu", "close"
    };

    /**
     * @param screenW/H 屏幕像素，用来判断"这个文本在顶部标题区"
     */
    public static Result probe(AccessibilityNodeInfo root, int screenW, int screenH) {
        Result r = new Result();
        if (root == null) return r;
        if (screenW <= 0) screenW = 1920;
        if (screenH <= 0) screenH = 1080;

        List<Cand> cands = new ArrayList<Cand>();
        walk(root, 0, cands, new int[]{420});
        if (cands.isEmpty()) return r;

        // ---------- 1. 进度 ----------
        // 先找"一个文本里带两个时间"的，这种最可靠（几乎所有播放器控制栏都这么写）
        for (int i = 0; i < cands.size(); i++) {
            Cand c = cands.get(i);
            if (c.text.length() == 0) continue;
            Matcher m = PAIR.matcher(c.text);
            if (!m.find()) continue;
            long a = parse(m.group(1));
            long b = parse(m.group(2));
            long lo = Math.min(a, b), hi = Math.max(a, b);
            if (sane(lo, hi)) {
                r.posMs = lo;
                r.durMs = hi;
                r.hasTimes = true;
                break;
            }
        }

        // 退一步：两个独立的时间文本（很多播放器左边当前时间、右边总时长分开成两个控件）
        if (!r.hasTimes) {
            List<Long> vals = new ArrayList<Long>();
            for (int i = 0; i < cands.size(); i++) {
                Cand c = cands.get(i);
                if (c.text.length() == 0 || c.text.length() > 24) continue;
                Matcher m = TIME.matcher(c.text);
                int hits = 0;
                long v = -1L;
                while (m.find()) {
                    hits++;
                    v = parse(m.group(1));
                }
                if (hits != 1 || v < 0) continue;
                // 整段只有时间（允许 "已播 12:34" 这种前缀，但不能是长句子）
                if (c.text.length() > 16) continue;
                vals.add(v);
            }
            vals = dedup(vals);
            if (vals.size() >= 2) {
                long hi = 0L;
                for (int i = 0; i < vals.size(); i++) hi = Math.max(hi, vals.get(i));
                long lo = -1L;
                for (int i = 0; i < vals.size(); i++) {
                    long v = vals.get(i);
                    if (v < hi && v > lo) lo = v;
                }
                if (lo >= 0 && sane(lo, hi)) {
                    r.posMs = lo;
                    r.durMs = hi;
                    r.hasTimes = true;
                }
            }
        }

        // ---------- 2. 片名 ----------
        String best = null;
        int bestScore = 0;
        for (int i = 0; i < cands.size(); i++) {
            Cand c = cands.get(i);
            String s = c.text.length() > 0 ? c.text : c.desc;
            if (s.length() < 2 || s.length() > 60) continue;
            if (URL.matcher(s).find()) continue;
            if (NUM_ONLY.matcher(s).matches()) continue;
            if (TECH.matcher(s).find()) continue;           // HDMI1 / 3840x2160@160Hz 这类
            if (TIME.matcher(s).find()) continue;           // 带时间的多半是进度/时长
            if (ONLY_EP.matcher(s).matches()) continue;     // "第 1 季" / "第 12 集"
            String low = s.toLowerCase();
            boolean junk = false;
            for (int j = 0; j < JUNK.length; j++) {
                if (low.contains(JUNK[j])) { junk = true; break; }
            }
            if (junk) continue;

            int score = 0;
            if (c.rect.top >= 0 && c.rect.top < screenH * 0.26) score += 26;  // 顶部标题条
            if (c.rect.width() > screenW * 0.12) score += 8;
            if (EP.matcher(s).find()) score += 22;                            // 带季集号
            if (s.length() >= 4 && s.length() <= 32) score += 12;
            if (c.clickable) score -= 18;                                     // 标题一般不给你点
            if (s.contains(":") || s.contains("：")) score -= 6;
            if (c.text.length() == 0 && c.desc.length() > 0) score += 4;      // 只有描述
            if (score > bestScore) { bestScore = score; best = s; }
            else if (score == bestScore && best != null && s.length() > best.length()) best = s;
        }
        if (bestScore >= 30) r.title = best;

        // ---------- 3. 采样，方便排查"为什么读不到" ----------
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cands.size() && sb.length() < 220; i++) {
            Cand c = cands.get(i);
            String s = c.text.length() > 0 ? c.text : c.desc;
            if (s.length() == 0) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(s.length() > 32 ? s.substring(0, 32) + "…" : s);
        }
        r.sample = sb.toString();
        r.videoLike = r.hasTimes && r.durMs >= 60L * 1000L;
        return r;
    }

    /** 标题里有没有季/集号（"第 3 集" / "S01E02"）：有就基本能确定是片子，而不是应用名 */
    public static boolean hasEpisode(String s) {
        return s != null && EP.matcher(s).find();
    }

    /** 明显不是片名的文本（HDMI/分辨率/输入源…），缓存里存过这种就丢掉 */
    public static boolean looksLikeJunk(String s) {
        if (s == null) return true;
        String t = s.trim();
        if (t.isEmpty()) return true;
        return TECH.matcher(t).find();
    }

    /** 进度要"像那么回事"才敢用：至少 1 分钟、最长 8 小时、当前位置不超过总长 */
    private static boolean sane(long pos, long dur) {
        if (dur < 60000L || dur > 8L * 3600L * 1000L) return false;
        if (pos < 0L || pos > dur + 5000L) return false;
        return true;
    }

    private static List<Long> dedup(List<Long> in) {
        List<Long> out = new ArrayList<Long>();
        for (int i = 0; i < in.size(); i++) {
            if (!out.contains(in.get(i))) out.add(in.get(i));
        }
        return out;
    }

    /** 02:07 / 1:02:07 → 毫秒 */
    public static long parse(String s) {
        if (s == null) return -1L;
        String[] p = s.trim().split(":");
        try {
            if (p.length == 2) return (Long.parseLong(p[0]) * 60L + Long.parseLong(p[1])) * 1000L;
            if (p.length == 3) return ((Long.parseLong(p[0]) * 3600L)
                    + Long.parseLong(p[1]) * 60L + Long.parseLong(p[2])) * 1000L;
        } catch (NumberFormatException ignored) {
        }
        return -1L;
    }

    /** 毫秒 → 播放器那种 "12:34" / "1:02:03" 文本，交给统一的时间轴处理 */
    public static String format(long ms) {
        if (ms < 0L) return null;
        long total = ms / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    private static void walk(AccessibilityNodeInfo n, int depth, List<Cand> out, int[] budget) {
        if (n == null || depth > 18 || budget[0] <= 0) return;
        budget[0]--;
        String t = str(n.getText());
        String d = str(n.getContentDescription());
        if (t.length() > 0 || d.length() > 0) {
            Rect rc = new Rect();
            try { n.getBoundsInScreen(rc); } catch (Throwable ignored) { }
            out.add(new Cand(t, d, rc, n.isClickable()));
        }
        int count;
        try { count = n.getChildCount(); } catch (Throwable t2) { return; }
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo ch = null;
            try { ch = n.getChild(i); } catch (Throwable ignored) { }
            if (ch == null) continue;
            walk(ch, depth + 1, out, budget);
            try { ch.recycle(); } catch (Throwable ignored) { }
        }
    }

    private static final class Cand {
        final String text;
        final String desc;
        final Rect rect;
        final boolean clickable;

        Cand(String text, String desc, Rect rect, boolean clickable) {
            this.text = text;
            this.desc = desc;
            this.rect = rect;
            this.clickable = clickable;
        }
    }
}
