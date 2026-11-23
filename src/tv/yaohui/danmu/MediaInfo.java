package tv.yaohui.danmu;

/** 当前播放的作品信息，供弹幕源做匹配。 */
public class MediaInfo {
    public String title = "";
    public int season = 0;     // 0 = 未知
    public int episode = 0;    // 0 = 未知（电影/单集）
    public String subtitle = "";
    public long durationMs = 0;

    public String key() {
        return title + "|" + season + "|" + episode;
    }

    @Override
    public String toString() {
        return title + (season > 0 ? " 第" + season + "季" : "")
                + (episode > 0 ? " 第" + episode + "集" : "")
                + (subtitle.isEmpty() ? "" : " - " + subtitle);
    }
}
