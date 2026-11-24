package tv.yaohui.danmu;

import android.content.Context;
import android.content.SharedPreferences;

/** 显示配置 + 数据源配置，存在自己应用的 SharedPreferences 里。 */
public class DanmuConfig {

    private static final String PREF = "danmu";

    /** 内置测试源（假弹幕，用于验证渲染） */
    public static final String SRC_MOCK = "mock";
    /** 自建 danmu_api / 兼容弹弹play 规范的接口 */
    public static final String SRC_API = "api";

    public boolean enabled = true;
    public int textSizePx = 44;
    public int speedPx = 190;      // 像素/秒
    public int alphaPct = 100;     // 0-100
    public float areaFraction = 0.4f;  // 1.0 = 全屏，0.4 = 上 40%（默认只用上部，合成更轻）
    /** 读不到播放进度时，自动让播放器弹一次控制栏（需要 Root） */
    public boolean autoSync = true;
    /**
     * 通用播放器模式：不只认 VidHub，任何"全屏 + 有声音 + 有进度条"的播放器都挂弹幕。
     * 片名/进度靠猜（节点树里的文本形状），所以偶尔会认错，可在 blacklist 里排掉某些应用。
     */
    public boolean allPlayers = true;
    /** 通用模式下不要挂弹幕的包名，逗号或换行分隔 */
    public String blacklist = "";
    /**
     * 播放器里连按两次菜单键打开设置页。
     * 代价：开启后所有按键都要经过无障碍服务转发一次，遥控器长按（比如音量连发）会变卡，
     * 只能一下一下按。嫌烦就关掉，改用电脑脚本或 adb 打开设置页。
     */
    public boolean menuGesture = true;

    // ---- 数据源 ----
    public String sourceType = SRC_MOCK;
    /** 形如 http://192.168.1.100:9321 ，末尾不用带斜杠 */
    public String apiBase = "";
    /** danmu_api 的 TOKEN，留空表示用默认 87654321（免 token） */
    public String apiToken = "";

    /**
     * 影响"弹幕长什么样 / 从哪来"的那部分配置的指纹。
     * 服务每 1.5 秒拿它和自己手上的比一次，变了就重载 ——
     * 比广播可靠：设置页和服务在同一个进程，改完立刻就能被看见。
     */
    public String signature() {
        return enabled + "|" + textSizePx + "|" + speedPx + "|" + alphaPct + "|" + areaFraction
                + "|" + autoSync + "|" + allPlayers + "|" + blacklist + "|" + menuGesture
                + "|" + sourceType + "|" + apiBase + "|" + apiToken;
    }

    public static DanmuConfig load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        DanmuConfig c = new DanmuConfig();
        c.enabled = sp.getBoolean("enabled", true);
        c.textSizePx = sp.getInt("textSize", 44);
        c.speedPx = sp.getInt("speed", 190);
        c.alphaPct = sp.getInt("alpha", 100);
        c.areaFraction = sp.getFloat("area", 0.4f);
        c.autoSync = sp.getBoolean("autoSync", true);
        c.allPlayers = sp.getBoolean("allPlayers", true);
        c.blacklist = sp.getString("blacklist", "");
        c.menuGesture = sp.getBoolean("menuGesture", true);
        c.sourceType = sp.getString("sourceType", SRC_MOCK);
        c.apiBase = sp.getString("apiBase", "");
        c.apiToken = sp.getString("apiToken", "");
        return c;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled)
                .putInt("textSize", textSizePx)
                .putInt("speed", speedPx)
                .putInt("alpha", alphaPct)
                .putFloat("area", areaFraction)
                .putBoolean("autoSync", autoSync)
                .putBoolean("allPlayers", allPlayers)
                .putString("blacklist", blacklist)
                .putBoolean("menuGesture", menuGesture)
                .putString("sourceType", sourceType)
                .putString("apiBase", apiBase)
                .putString("apiToken", apiToken)
                .apply();
    }

    /** 按当前配置造一个数据源；地址没填就退回测试源，避免静默无弹幕 */
    public DanmakuSource newSource() {
        if (SRC_API.equals(sourceType) && apiBase != null && apiBase.trim().length() > 0) {
            return new DanmuApiSource(apiBase.trim(), apiToken == null ? "" : apiToken.trim());
        }
        return new MockSource();
    }

    public String sourceLabel() {
        if (SRC_API.equals(sourceType)) {
            return apiBase != null && apiBase.trim().length() > 0
                    ? "弹幕API " + apiBase.trim() : "弹幕API（地址未填，已退回测试源）";
        }
        return "内置测试源";
    }
}
