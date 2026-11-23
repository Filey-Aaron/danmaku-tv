package tv.yaohui.danmu;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 VidHub 播放器标题，例如：
 *   黑帮领地 第1季 第5集 - 朋友的葬礼
 *   毒液2
 *   某剧 第12集
 */
public final class TitleParser {

    /*
     * 副标题不一定有分隔符：爆米花把标题写成「无赦之仇 第 1 集 基锡离奇身亡」，
     * 集号后面直接空格接集名。旧正则要求 [-—:] 才肯吃副标题，结果整串被当成片名
     * 拿去搜，一个都搜不到。这里把分隔符改成可选，剩下的都当副标题。
     */
    private static final Pattern P_SEASON_EP =
            Pattern.compile("^(.*?)\\s*第\\s*(\\d+)\\s*季\\s*第\\s*(\\d+)\\s*[集话]\\s*(?:[-—:、·]\\s*)?(.*)$");
    private static final Pattern P_EP =
            Pattern.compile("^(.*?)\\s*第\\s*(\\d+)\\s*[集话]\\s*(?:[-—:、·]\\s*)?(.*)$");
    private static final Pattern P_SXXEYY =
            Pattern.compile("^(.*?)\\s*[Ss](\\d{1,2})[Ee](\\d{1,3})\\s*(?:[-—:、·]\\s*)?(.*)$");
    /** 整串只有季/集号 */
    private static final Pattern ONLY_EP =
            Pattern.compile("^\\s*第?\\s*\\d{1,4}\\s*[季集期话篇]\\s*$");

    private TitleParser() { }

    public static MediaInfo parse(String raw) {
        MediaInfo info = parseRaw(raw);
        splitCnSeason(info);
        return info;
    }

    private static MediaInfo parseRaw(String raw) {
        MediaInfo info = new MediaInfo();
        if (raw == null) return info;
        String s = raw.trim();
        if (s.isEmpty()) return info;
        // 整串只有季/集号（选集面板里的文字），不是片名，宁可空着也别拿去搜
        if (ONLY_EP.matcher(s).matches()) return info;

        Matcher m = P_SEASON_EP.matcher(s);
        if (m.find() && !clean(m.group(1)).isEmpty()) {
            info.title = clean(m.group(1));
            info.season = parseInt(m.group(2));
            info.episode = parseInt(m.group(3));
            info.subtitle = m.group(4) == null ? "" : m.group(4).trim();
            return info;
        }

        m = P_EP.matcher(s);
        if (m.find() && !clean(m.group(1)).isEmpty()) {
            info.title = clean(m.group(1));
            info.episode = parseInt(m.group(2));
            info.subtitle = m.group(3) == null ? "" : m.group(3).trim();
            return info;
        }

        m = P_SXXEYY.matcher(s);
        if (m.find() && !clean(m.group(1)).isEmpty()) {
            info.title = clean(m.group(1));
            info.season = parseInt(m.group(2));
            info.episode = parseInt(m.group(3));
            info.subtitle = m.group(4) == null ? "" : m.group(4).trim();
            return info;
        }

        // 纯片名，可能带 "- 副标题"
        int dash = s.indexOf(" - ");
        if (dash > 0) {
            info.title = clean(s.substring(0, dash));
            info.subtitle = s.substring(dash + 3).trim();
        } else {
            info.title = clean(s);
        }
        return info;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\-_—.·]+$", "").trim();
    }

    /** 中文数字季号："庆余年 第二季" → season=2 */
    private static final java.util.regex.Pattern CN_SEASON =
            java.util.regex.Pattern.compile("^(.*?)\\s*第([一二三四五六七八九十]+)季\\s*$");
    private static final String CN_NUM = "一二三四五六七八九十";

    /** 片名末尾还挂着「第二季」这类中文季号时，剥掉并回填 season */
    private static void splitCnSeason(MediaInfo info) {
        if (info.title == null || info.title.isEmpty()) return;
        java.util.regex.Matcher m = CN_SEASON.matcher(info.title);
        if (!m.find()) return;
        String name = clean(m.group(1));
        if (name.isEmpty()) return;
        info.title = name;
        if (info.season <= 0) {
            int idx = CN_NUM.indexOf(m.group(2));
            info.season = idx >= 0 ? idx + 1 : 0;
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
