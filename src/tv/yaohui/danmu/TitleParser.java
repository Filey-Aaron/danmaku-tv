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

    private static final Pattern P_SEASON_EP =
            Pattern.compile("^(.*?)\\s*第\\s*(\\d+)\\s*季\\s*第\\s*(\\d+)\\s*集\\s*(?:[-—:]\\s*(.*))?$");
    private static final Pattern P_EP =
            Pattern.compile("^(.*?)\\s*第\\s*(\\d+)\\s*集\\s*(?:[-—:]\\s*(.*))?$");
    private static final Pattern P_SXXEYY =
            Pattern.compile("^(.*?)\\s*[Ss](\\d{1,2})[Ee](\\d{1,3})\\s*(?:[-—:]\\s*(.*))?$");

    private TitleParser() { }

    public static MediaInfo parse(String raw) {
        MediaInfo info = new MediaInfo();
        if (raw == null) return info;
        String s = raw.trim();
        if (s.isEmpty()) return info;

        Matcher m = P_SEASON_EP.matcher(s);
        if (m.find()) {
            info.title = clean(m.group(1));
            info.season = parseInt(m.group(2));
            info.episode = parseInt(m.group(3));
            info.subtitle = m.group(4) == null ? "" : m.group(4).trim();
            return info;
        }

        m = P_EP.matcher(s);
        if (m.find()) {
            info.title = clean(m.group(1));
            info.episode = parseInt(m.group(2));
            info.subtitle = m.group(3) == null ? "" : m.group(3).trim();
            return info;
        }

        m = P_SXXEYY.matcher(s);
        if (m.find()) {
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
        return s.replaceAll("[\\s\\-_—]+$", "").trim();
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
