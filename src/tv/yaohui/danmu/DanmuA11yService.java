package tv.yaohui.danmu;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * 核心服务：
 *  - 盯 VidHub 播放器窗口，从界面节点里读片名/季集/进度
 *  - 用 Timeline 估算当前播放位置
 *  - 把一个透明弹幕层盖在视频上
 * 全程不注入播放器进程，不修改播放器任何数据。
 */
public class DanmuA11yService extends AccessibilityService {

    private static final String TAG = "DanmuSvc";

    private static final String PKG_VIDHUB = "com.oumi.utility.media.hub";
    private static final String ID_TITLE = PKG_VIDHUB + ":id/play_item_title_top";
    private static final String ID_LEFT = PKG_VIDHUB + ":id/text_left_time";
    private static final String ID_RIGHT = PKG_VIDHUB + ":id/text_right_time";

    private static final long POLL_MS = 300L;
    /** 通用播放器要全树扫描，代价比按 id 找高，放宽到 500ms 一次 */
    private static final long POLL_MS_GENERIC = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 无障碍事件报来的当前界面类型："play"=播放页，"other"=VidHub 其它页，null=还不知道 */
    private volatile String curActivity;
    /** 连续几轮发现"窗口在、但不是播放页"（列表页/详情页/桌面）→ 直接收摊 */
    private int pageMiss = 0;
    /** 自报"播放页"却一直读不到播放控件的连续轮数（详情页预览播放器会这样） */
    private int quietTick = 0;
    /** 本次会话有没有真的读到过播放控件（片名/进度）；没读到就不该发按键、不该弹提示 */
    private boolean sessionSawUi = false;
    /** 在这个时间点之前不要自动开弹幕层（防止详情页预览播放器反复触发开关） */
    private long suppressStartUntil = 0L;
    /** 最近一次确认为播放页的墙钟时间（0 = 还没确认过，此时信任 root==null 的情形） */
    private long lastPlayerSeenWall = 0L;

    /** 读无障碍节点是跨进程 IPC（每次十几毫秒），放后台线程，别在主线程上读 */
    private android.os.HandlerThread pollThread;
    private Handler pollHandler;
    private final Timeline timeline = new Timeline();
    private DanmuConfig config = new DanmuConfig();
    private DanmakuSource source = new MockSource();
    private OverlayView overlay;
    private WindowManager wm;
    private android.media.AudioManager audio;

    /** 用系统音频状态判断"在播还是暂停"，比等进度条变化可靠得多 */
    private boolean audioPlaying = false;
    private long audioChangedAt = 0L;
    private boolean audioKnown = false;

    private boolean sessionActive = false;
    private long lastGuardLog = 0L;
    private String currentKey = "";
    private MediaInfo currentInfo = new MediaInfo();
    private long lastPos = -1L;
    /** 通用播放器这次会话已经用"从 0 开始按墙钟走"兜过底了吗 */
    private boolean genericSeeded = false;
    /** 通用模式下连续多久没读到进度条（用来排除 HDMI 输入源这类根本不是视频的界面） */
    private long genericNoProgressMs = 0L;
    /** 通用模式标题防抖：同一个标题连续读到两轮才采用 */
    private String pendingGenericTitle = null;
    private int pendingGenericCount = 0;
    /** 通用模式下扫描结果的日志节流 */
    private long lastProbeLogAt = 0L;
    /** 这一轮轮询是不是走的"通用播放器"路径（tickNoAnchor 要靠它决定敢不敢发按键） */
    private boolean curGeneric = false;
    /**
     * 判定为"不是视频"的包名 → 解禁时间。
     * 电视切到 HDMI 输入源、或者某个 App 只是全屏放着不出声的视频，只靠"全屏+有声"会
     * 每 30 秒重开一次弹幕层，闪一下又收，很烦。这里让它在几分钟内安分点；
     * 但一旦真的读到进度条（hasVideoUi），立刻放行，不会耽误看片。
     */
    private final java.util.HashMap<String, Long> nonVideoUntil = new java.util.HashMap<String, Long>();
    /** 当前盯着的播放器包名（后台线程写、主线程读） */
    private volatile String curPkg = "";

    /** 只要手上没有"读到过进度条"这个硬凭据，就先别为这个包名重开会话 */
    private boolean nonVideoSuppressed(String pkg, boolean hasVideoUi) {
        if (hasVideoUi) return false;
        Long until = nonVideoUntil.get(pkg);
        return until != null && SystemClock.uptimeMillis() < until;
    }

    /** 手上这集的弹幕，切换界面/暂停回来时直接复用，不必再问服务器 */
    private List<DanmakuItem> lastItems = null;
    private String lastItemsKey = "";
    /** 最近一次识别到的作品（片名只有控制栏弹出时可读，所以要记住） */
    private MediaInfo lastInfo = null;
    /** 连续几次判定"不在 VidHub"，避免控制栏弹出等瞬间抖动把弹幕层反复拆了重建 */
    private int offlineCount = 0;
    /** 断线时的播放位置与墙钟，用于恢复（位置必须绑定作品，换片不能沿用） */
    private long lastKnownPos = -1L;
    private long lastKnownWall = 0L;
    private String lastKnownKey = "";
    /** 多久没读到真实进度 / 是否已经提示过 */
    private long noAnchorMs = 0L;
    private boolean hintShown = false;
    private long posLogAt = 0L;
    private boolean audioProbeLogged = false;
    /** 自动让播放器弹控制栏（发"上"键）的节流与失败熔断 */
    private long lastNudgeAt = 0L;
    private int nudgeCount = 0;
    private boolean nudgeBroken = false;

    /** 本会话是否已经读到过真实片名。false 时显示的只是上次缓存，换片了就是张冠李戴 */
    private boolean titleConfirmed = false;
    /** nudge 的滑动窗口限流：1 分钟内最多 3 次，避免会话反复重启时狂弹控制栏 */
    private long nudgeWindowStart = 0L;
    private int nudgeInWindow = 0;
    /** 已经把"未确认片名"的旧弹幕清掉了吗（每个会话只清一次） */
    private boolean staleCleared = false;

    /** 设置页在前台：把弹幕层临时撤掉，别盖在自己的界面上 */
    private boolean settingsOpen = false;

    /**
     * 读不到进度时，用 root 发一个"上"键，让播放器把控制栏弹出来。
     * 上键只显示控制栏，不会暂停（确定键才会）。没 root 就静默放弃，退回到画面提示。
     */
    private void requestProgressNudge() {
        long now = SystemClock.uptimeMillis();
        if (now - nudgeWindowStart > 60000L) {
            nudgeWindowStart = now;
            nudgeInWindow = 0;
        }
        nudgeInWindow++;
        lastNudgeAt = now;
        nudgeCount++;
        final int n = nudgeCount;
        log("nudge #" + n + (titleConfirmed ? " (no anchor)" : " (need title)"));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{
                            "/system/bin/sh", "-c", "su -c '/system/bin/input keyevent 19'"});
                    final boolean ok = p.waitFor() == 0;
                    Log.i(TAG, "nudge #" + n + " exit ok=" + ok);
                    if (!ok) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (nudgeCount >= 2) nudgeBroken = true;  // 没授权就别再弹窗烦人
                            }
                        });
                    }
                } catch (Throwable t) {
                    log("nudge failed: " + t);
                    nudgeBroken = true;
                }
            }
        }, "nudge").start();
    }

    /** 每 1.5 秒确认一次"当前是不是播放器界面"，不依赖事件回调，避免漏判 */
    private final Runnable guardTask = new Runnable() {
        @Override
        public void run() {
            // 读包名也是跨进程 IPC，挪到后台线程读，读完再回主线程判断
            if (pollHandler != null) {
                pollHandler.post(readPkgTask);
            } else {
                guardWith("", false, false, false);
            }
            handler.postDelayed(this, 1500L);
        }
    };

    private final Runnable readPkgTask = new Runnable() {
        @Override
        public void run() {
            String pkg = "";
            boolean ui = false;
            boolean full = false;
            int sw = screenW(), sh = screenH();
            Win w = null;
            AccessibilityNodeInfo fallback = null;
            try {
                w = pickWindow();
                if (w != null) {
                    pkg = w.pkg;
                    full = isFullscreen(w.rect, sw, sh);
                    if (PKG_VIDHUB.equals(pkg)) {
                        ui = text(w.root, ID_TITLE) != null
                                || text(w.root, ID_LEFT) != null
                                || text(w.root, ID_RIGHT) != null;
                    } else {
                        ui = PlayerProbe.probe(w.root, sw, sh).videoLike;
                    }
                } else {
                    // 没有被允许的窗口（桌面/别的应用/播放器窗口根节点拿不到）
                    fallback = getRootInActiveWindow();
                    pkg = pkgOf(fallback);
                }
            } catch (Throwable t) {
                pkg = "";
            } finally {
                if (w != null) recycleQuiet(w.root);
                recycleQuiet(fallback);
            }
            final String fPkg = pkg;
            final boolean fUi = ui, fFull = full;
            final boolean fAudio = isAudioActive();
            handler.post(new Runnable() {
                @Override
                public void run() { guardWith(fPkg, fAudio, fUi, fFull); }
            });
        }
    };

    private void guardWith(String pkg, boolean audioActive, boolean playerUi, boolean fullscreen) {
        {
            try {
                boolean isVid = PKG_VIDHUB.equals(pkg);
                boolean supported = isSupportedPkg(pkg);
                // VidHub 的列表页/详情页跟播放页是同一个包名，光看包名会在详情页
                // 反复开/关弹幕层（还会弹"读不到片名"）。这里优先信无障碍事件报的界面类型。
                boolean knownOther = isVid && "other".equals(curActivity);
                // VidHub 靠准确 id 判定；通用播放器没有 id，只能"读到进度条文本"或
                // "全屏 + 正在出声"来判定在播。单凭有声音会把音乐 App 也当成播放器，
                // 所以额外要求窗口几乎铺满屏幕。
                boolean looksPlaying = isVid
                        ? (playerUi || "play".equals(curActivity) || audioActive)
                        : (playerUi || (audioActive && fullscreen));

                if (knownOther) {
                    // 明确在 VidHub 的列表/详情页：收摊，别在这儿开层、别发按键
                    if (sessionActive) stopSession();
                } else if (supported && !sessionActive && looksPlaying
                        && !nonVideoSuppressed(pkg, playerUi)
                        && SystemClock.uptimeMillis() >= suppressStartUntil) {
                    offlineCount = 0;
                    startSession();
                } else if (supported && sessionActive) {
                    offlineCount = 0;
                    // 自愈：播放器里但弹幕层不在了（被系统回收/被自己误删）就补一块
                    // 注意必须在会话活跃时才补，否则详情页也会凭空多一块窗
                    if (overlay == null) {
                        showOverlay();
                        if (overlay != null && lastItems != null && lastItemsKey.equals(currentKey)) {
                            overlay.setItems(lastItems);
                        }
                    }
                } else if (sessionActive && !pkg.isEmpty()) {
                    // 连续几轮都不在播放器界面才收摊。
                    // 有声音说明还在放（界面节点偶尔会报错包名），多给几次机会
                    offlineCount++;
                    int limit = audioActive ? 12 : 5;
                    if (offlineCount >= limit) stopSession();
                }
            } catch (Throwable t) {
                Log.w(TAG, "guard error", t);
            }
        }
    }

    /** 一个候选播放器窗口：根节点 + 包名 + 屏幕矩形（root 由调用方 recycle） */
    private static final class Win {
        AccessibilityNodeInfo root;
        String pkg = "";
        android.graphics.Rect rect = new android.graphics.Rect();
        CharSequence title;
    }

    /**
     * 从窗口列表里挑当前面积最大的、允许挂弹幕的窗口。
     * VidHub 播放页控制栏收起时 getRootInActiveWindow() 会返回 null，只有这里能拿到；
     * 换成别的播放器（Kodi / Plex / Emby / MX / 当贝 / 系统播放器）也是同一条路。
     */
    private Win pickWindow() {
        return pickWindow(false);
    }

    /**
     * @param any true = 不做"允许列表"过滤，只排除自己（诊断用）
     */
    private Win pickWindow(boolean any) {
        List<AccessibilityWindowInfo> wins;
        try {
            wins = getWindows();
        } catch (Throwable t) {
            return null;
        }
        if (wins == null) return null;
        Win best = null;
        int bestPrio = -1;
        int bestLayer = -1;
        long bestArea = 0L;
        for (int i = 0; i < wins.size(); i++) {
            AccessibilityWindowInfo w = wins.get(i);
            if (w == null) continue;
            AccessibilityNodeInfo r = null;
            try {
                r = w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                String pkg = p == null ? "" : p.toString();
                if (any) {
                    if (pkg.isEmpty() || pkg.equals(getPackageName())) continue;
                } else if (!isSupportedPkg(pkg)) {
                    continue;
                }
                android.graphics.Rect rc = new android.graphics.Rect();
                w.getBoundsInScreen(rc);
                long area = (long) rc.width() * (long) rc.height();
                // 不能只挑"面积最大"：后台播放器窗口常常也是全屏，会抢在当前画面前面。
                // 先看是不是当前活动/聚焦窗口，再看窗口层级，最后才用面积兜底。
                int prio = w.isActive() ? 2 : (w.isFocused() ? 1 : 0);
                int layer = w.getLayer();
                boolean better = prio > bestPrio
                        || (prio == bestPrio && layer > bestLayer)
                        || (prio == bestPrio && layer == bestLayer && area > bestArea);
                if (better) {
                    if (best != null) recycleQuiet(best.root);
                    best = new Win();
                    best.root = r;
                    best.pkg = pkg;
                    best.rect = rc;
                    best.title = w.getTitle();
                    bestPrio = prio;
                    bestLayer = layer;
                    bestArea = area;
                    r = null;                  // 所有权交给 best
                }
            } catch (Throwable ignored) {
            } finally {
                recycleQuiet(r);
                try { w.recycle(); } catch (Throwable ignored) { }
            }
        }
        return best;
    }

    /** VidHub 永远支持；其它应用看"通用播放器模式"开关和黑名单 */
    private boolean isSupportedPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(getPackageName())) return false;
        if (isSystemish(pkg)) return false;
        if (inBlacklist(pkg)) return false;
        return PKG_VIDHUB.equals(pkg) || config.allPlayers;
    }

    /** 桌面/设置/输入法/systemui 这些地方绝不挂弹幕，否则一开电视就满屏刷 */
    private boolean isSystemish(String pkg) {
        return pkg.equals("android")
                || pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.android.settings")
                || pkg.startsWith("com.android.providers")
                || pkg.contains("launcher")
                || pkg.contains("tvhome")
                || pkg.contains("inputmethod")
                // 音乐/广播类全屏也有进度条和声音，别给它们挂弹幕
                || pkg.contains("music")
                || pkg.contains("radio")
                || pkg.contains("podcast")
                // 电视自带的输入源/播放器界面（HDMI、相册预览）：全屏也有声，但永远没有片名，
                // 一挂着就是一层空弹幕，还在后台反复搜"显示器外部源"
                || pkg.startsWith("com.xiaomi.mitv.tvplayer")
                || pkg.startsWith("com.mitv.tvplayer")
                || pkg.startsWith("com.mitv.gallery")
                || pkg.startsWith("com.google.android.gms");
    }

    private boolean inBlacklist(String pkg) {
        String bl = config.blacklist;
        if (bl == null || bl.isEmpty()) return false;
        String[] parts = bl.split("[,，;；\\s]+");
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            if (!s.isEmpty() && pkg.equals(s)) return true;
        }
        return false;
    }

    /** 窗口算不算"全屏"：通用模式下只有全屏才当视频，避免把音乐 App 也当成播放器 */
    private boolean isFullscreen(android.graphics.Rect rc, int sw, int sh) {
        if (rc == null || sw <= 0 || sh <= 0) return false;
        return rc.width() >= sw * 0.75f && rc.height() >= sh * 0.65f;
    }

    private int screenW() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels > 0 ? dm.widthPixels : 1920;
    }

    private int screenH() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.heightPixels > 0 ? dm.heightPixels : 1080;
    }

    private static void recycleQuiet(AccessibilityNodeInfo n) {
        if (n == null) return;
        try { n.recycle(); } catch (Throwable ignored) { }
    }

    private static String pkgOf(AccessibilityNodeInfo root) {
        if (root == null) return "";
        try {
            CharSequence p = root.getPackageName();
            return p == null ? "" : p.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 应用自己的名字（用来把"窗口标题 = 应用名"这种没用的标题过滤掉） */
    private final java.util.HashMap<String, String> labelCache = new java.util.HashMap<String, String>();

    private String appLabel(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String c = labelCache.get(pkg);
        if (c != null) return c;
        String v = "";
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence l = getPackageManager().getApplicationLabel(ai);
            v = l == null ? "" : l.toString();
        } catch (Throwable ignored) { }
        labelCache.put(pkg, v);
        return v;
    }

    /** 这个"片名"其实是不是应用名（播放器把窗口标题当片名时报上来的那种） */
    private boolean looksLikeAppName(String title) {
        if (title == null) return false;
        String t = title.trim();
        if (t.isEmpty()) return false;
        String l1 = appLabel(curPkg).trim();
        if (!l1.isEmpty() && (t.equals(l1) || t.contains(l1) || l1.contains(t))) return true;
        String l2 = appLabel(PKG_VIDHUB).trim();
        if (!l2.isEmpty() && (t.equals(l2) || t.contains(l2))) return true;
        return false;
    }

    /** 窗口标题里的词，出现在这里基本就是播放器/客户端的名字，不是片名 */
    private static final String[] CLIENT_WORDS = {
            "播放器", "影院", "影视", "视频", "爆米花", "电视", "投屏",
            "filmly", "vidhub", "kodi", "vlc", "mx player", "player", "tv"
    };

    /**
     * 窗口标题靠不靠谱。窗口标题十有八九就是应用名（"网易爆米花"），
     * 而 PackageManager 里的 label 未必跟界面上那个中文名一致，光比 label 拦不住。
     * 这里按长相判断：带季集号的直接信；否则要够长、且不含"播放器/影视/TV"这类词。
     */
    private boolean plausibleWindowTitle(String s) {
        if (s == null) return false;
        if (PlayerProbe.hasEpisode(s)) return true;         // "某剧 第 3 集"
        if (s.length() < 6) return false;                   // "网易爆米花" 5 个字：大概率是应用名
        String low = s.toLowerCase();
        for (int i = 0; i < CLIENT_WORDS.length; i++) {
            if (low.contains(CLIENT_WORDS[i])) return false;
        }
        return true;
    }

    private String activePackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        try {
            CharSequence p = root.getPackageName();
            return p == null ? "" : p.toString();
        } finally {
            try { root.recycle(); } catch (Throwable ignored) { }
        }
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!sessionActive) return;
            String title = null, left = null, right = null;
            boolean noRoot, inPlayer, hasPlayerUi = false, generic = false;
            long delay = POLL_MS;
            final boolean audioActive = isAudioActive();
            final int sw = screenW(), sh = screenH();
            Win w = null;
            try {
                w = pickWindow();
                if (w == null) {
                    // 控制栏完全收起 / 窗口根节点拿不到
                    noRoot = true;
                    inPlayer = true;
                    hasPlayerUi = false;
                } else {
                    noRoot = false;
                    curPkg = w.pkg;
                    boolean isVid = PKG_VIDHUB.equals(w.pkg);
                    boolean full = isFullscreen(w.rect, sw, sh);
                    if (isVid) {
                        title = text(w.root, ID_TITLE);
                        left = text(w.root, ID_LEFT);
                        right = text(w.root, ID_RIGHT);
                        hasPlayerUi = title != null || left != null || right != null;
                        // 同一个 VidHub 里还分列表页/详情页。只要无障碍事件说在播放页
                        // （控制栏收起时读不到控件，这很常见），或者确实读到了播放控件，就算播放页
                        inPlayer = !"other".equals(curActivity)
                                && ("play".equals(curActivity) || hasPlayerUi || audioActive);
                    } else {
                        generic = true;
                        delay = POLL_MS_GENERIC;
                        PlayerProbe.Result pr = PlayerProbe.probe(w.root, sw, sh);
                        left = PlayerProbe.format(pr.posMs);
                        right = PlayerProbe.format(pr.durMs);
                        hasPlayerUi = pr.hasTimes;
                        title = pr.title;
                        if (title == null && w.title != null) {
                            String wt = w.title.toString().trim();
                            if (wt.length() >= 2 && !PlayerProbe.looksLikeJunk(wt)
                                    && !looksLikeAppName(wt) && plausibleWindowTitle(wt)) {
                                title = wt;
                            }
                        }
                        // 防抖：连着两轮读到同一个标题才认。窗口标题会闪（显示进度、暂停图标之类），
                        // 每变一次就去换片会把弹幕拉来拉去。
                        if (title != null && title.equals(pendingGenericTitle)) {
                            if (pendingGenericCount < 5) pendingGenericCount++;
                        } else {
                            pendingGenericTitle = title;
                            pendingGenericCount = title == null ? 0 : 1;
                        }
                        if (title != null && pendingGenericCount < 2) title = null;
                        // 通用播放器没有可靠的"页面类型"，只能靠"读到进度条"或"全屏出声"
                        inPlayer = pr.videoLike || (audioActive && full);
                        long now2 = SystemClock.uptimeMillis();
                        if (now2 - lastProbeLogAt > 20000L) {
                            lastProbeLogAt = now2;
                            String rep = "包名 " + w.pkg
                                    + " · 片名 " + (title == null ? "（没认出来）" : title)
                                    + " · 进度 " + (left == null ? "（没读到）" : left + " / " + right)
                                    + " · 全屏 " + (full ? "是" : "否");
                            log("probe " + rep + " · " + pr.sample);
                            // 存一份，用户按菜单键进设置页就能看到刚才在播放器里认出了什么
                            getSharedPreferences("danmu", MODE_PRIVATE).edit()
                                    .putString("lastProbe", rep).apply();
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "poll read error", t);
                recycleQuiet(w == null ? null : w.root);
                if (pollHandler != null) pollHandler.postDelayed(this, POLL_MS);
                return;
            }
            recycleQuiet(w == null ? null : w.root);

            final String fTitle = title, fLeft = left, fRight = right;
            final boolean fNoRoot = noRoot, fInPlayer = inPlayer;
            final boolean fHasUi = hasPlayerUi, fGeneric = generic;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!sessionActive) return;
                    applyPoll(fTitle, fLeft, fRight, fNoRoot, fInPlayer, fHasUi, audioActive, fGeneric);
                }
            });
            if (pollHandler != null) pollHandler.postDelayed(this, delay);
        }
    };

    private final BroadcastReceiver reloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && "tv.yaohui.danmu.PROBE".equals(intent.getAction())) {
                runProbe();
                return;
            }
            config = DanmuConfig.load(DanmuA11yService.this);
            applyKeyFilter();
            // 「隐藏弹幕」= 只把弹幕层撤掉，服务/会话/按键手势全都照旧活着。
            // 别在这里 stopSession：一停，用户再点「显示弹幕」就得等重新进播放页，
            // 体验上就像整个应用被关掉了。
            if (!config.enabled) {
                hideOverlay();
                log("danmu hidden, service still alive");
                return;
            }
            // 数据源可能被换了（测试源 <-> 弹幕API），重建后对当前这集重新拉一次
            source = config.newSource();
            currentKey = "";
            lastItems = null;
            lastItemsKey = "";
            lastPos = -1L;
            // 重新显示：先把层挂回来，再把手上这集铺回去，不用重进播放页
            if (!settingsOpen) showOverlay();
            if (overlay != null) {
                overlay.setConfig(config);
                overlay.clearDanmaku();
            }
            log("config reload, source=" + source.name());
            // 不等控制栏，直接按记下来的作品重拉
            restoreMedia();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        config = DanmuConfig.load(this);
        source = config.newSource();
        IntentFilter f = new IntentFilter("tv.yaohui.danmu.RELOAD");
        f.addAction("tv.yaohui.danmu.PROBE");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(reloadReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(reloadReceiver, f);
        }
        applyKeyFilter();
        log("service connected, vidhub=" + isVidHubInstalled()
                + " menuGesture=" + config.menuGesture);
        pollThread = new android.os.HandlerThread("danmu-poll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        handler.removeCallbacks(guardTask);
        handler.post(guardTask);
    }

    @Override
    public void onDestroy() {
        stopSession();
        if (pollHandler != null) pollHandler.removeCallbacksAndMessages(null);
        if (pollThread != null) pollThread.quitSafely();
        try { unregisterReceiver(reloadReceiver); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    private boolean isVidHubInstalled() {
        try {
            getPackageManager().getPackageInfo(PKG_VIDHUB, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;

        CharSequence cls = event.getClassName();
        CharSequence pkg = event.getPackageName();
        String c = cls == null ? "" : cls.toString();
        String p = pkg == null ? "" : pkg.toString();

        // 自己的界面：设置页开着的时候绝不能有弹幕盖在上面
        // （弹幕是系统悬浮窗，不处理的话会一直压在设置页上方，看着就像界面坏了）
        if (getPackageName().equals(p)) {
            if (c.contains("SettingsActivity") && !settingsOpen) {
                settingsOpen = true;
                hideOverlay();
                log("settings open -> overlay hidden");
            }
            return;
        }

        // 界面从设置页切走了（回到播放器/桌面都算）：把弹幕层挂回来
        if (settingsOpen && !p.isEmpty()) {
            settingsOpen = false;
            if (config.enabled && sessionActive) {
                showOverlay();
                if (overlay != null && lastItems != null && lastItemsKey.equals(currentKey)) {
                    overlay.setItems(lastItems);
                }
                log("settings closed -> overlay restored");
            }
        }

        if (PKG_VIDHUB.equals(p) && c.contains("TVVideoPlayActivity")) {
            curActivity = "play";
            // VidHub 详情页里的小窗预览也报同一个播放 Activity，别被它带着反复开层
            if (!sessionActive && SystemClock.uptimeMillis() >= suppressStartUntil) startSession();
            return;
        }
        if (PKG_VIDHUB.equals(p)) {
            // VidHub 自己的其它界面才停；播放器弹出的小窗口/对话框忽略
            if (c.contains("TVMainActivity") || c.contains("TVMediaItemDetailActivity")
                    || c.contains("TVSplashActivity")) {
                curActivity = "other";
                if (sessionActive) stopSession();
            }
            return;
        }
        // 其它应用的窗口（系统弹窗、语音助手、画中画…）不在这里立刻停：
        // 小米电视这类窗口一闪即过，一停就会马上又 start，会话反复横跳，
        // 每次都拿上次缓存的片名顶上 → 换剧后一直显示上一部的弹幕。
        // 统一交给 guardTask，它用"包名 + 有没有声音 + 连续次数"判定，可靠得多。
    }

    /** 申请接收遥控器按键，用于"连按两次菜单键打开设置"这个隐藏入口 */
    /** 按配置决定要不要申请按键过滤（用于"连按两次菜单键打开设置"） */
    private void applyKeyFilter() {
        try {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return;
            int flag = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            boolean want = config.menuGesture;
            boolean has = (info.flags & flag) != 0;
            if (want == has) return;
            if (want) info.flags |= flag;
            else info.flags &= ~flag;
            setServiceInfo(info);
            log("key filter -> " + want);
        } catch (Throwable t) {
            Log.w(TAG, "applyKeyFilter failed", t);
        }
    }

    private long lastMenuDown = 0L;

    @Override
    protected boolean onKeyEvent(android.view.KeyEvent event) {
        try {
            // 诊断：先看看按键到底有没有发到无障碍层（调试完可去掉）
            int code = event.getKeyCode();
            if (code == android.view.KeyEvent.KEYCODE_MENU
                    || code == android.view.KeyEvent.KEYCODE_SETTINGS) {
                log("key " + code + " action=" + event.getAction()
                        + " repeat=" + event.getRepeatCount()
                        + " src=0x" + Integer.toHexString(event.getSource()));
            }
            if (code != android.view.KeyEvent.KEYCODE_MENU
                    && code != android.view.KeyEvent.KEYCODE_SETTINGS) return false;
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (event.getRepeatCount() > 0) return false;
            long now = SystemClock.uptimeMillis();
            // 900ms 内连按两次才算（窗口太宽容易误触，播放器本身也吃菜单键）。
            // 连按三下时第二下就会命中，所以"三下"同样有效。
            if (now - lastMenuDown < 900L) {
                lastMenuDown = 0L;
                log("menu x2 -> open settings");
                openSettings();
                return true;
            }
            lastMenuDown = now;
        } catch (Throwable t) {
            Log.w(TAG, "onKeyEvent error", t);
        }
        return false;
    }

    private void openSettings() {
        try {
            Intent i = new Intent(this, SettingsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "openSettings failed", t);
        }
    }

    @Override
    public void onInterrupt() {
        stopSession();
    }

    private void startSession() {
        sessionActive = true;
        offlineCount = 0;
        pageMiss = 0;
        quietTick = 0;
        sessionSawUi = false;
        noAnchorMs = 0L;
        hintShown = false;
        nudgeCount = 0;
        titleConfirmed = false;      // 还没读到真实片名，现在挂的只是上次缓存
        staleCleared = false;
        genericSeeded = false;
        genericNoProgressMs = 0L;
        pendingGenericTitle = null;
        pendingGenericCount = 0;
        timeline.reset();
        lastPos = -1L;
        showOverlay();
        restoreMedia();               // 先确定这一集
        seedTimeline();               // 再把播放位置接上
        // 弹幕层刚重建是空的：把手上这集的数据铺回去
        if (overlay != null && lastItems != null && lastItemsKey.equals(currentKey)) {
            overlay.setItems(lastItems);
        }
        if (pollHandler != null) {
            pollHandler.removeCallbacks(pollTask);
            pollHandler.post(pollTask);
        }
        log("session start" + (lastInfo == null ? "" : " · " + currentInfo.key()));
    }

    /**
     * 接上播放位置。读不到真实进度（控制栏没弹出来）时先用上次的值 + 墙钟继续推，
     * 等控制栏出现读到真值会自动重锚校正。
     */
    private void seedTimeline() {
        // 位置只在同一部作品内有效，换片了就当不知道
        long posMs = lastKnownKey.equals(currentKey) ? lastKnownPos : -1L;
        long gap = -1L;
        if (posMs < 0) {
            // 进程重启过，从磁盘找上次的位置
            SharedPreferences sp = getSharedPreferences("danmu", MODE_PRIVATE);
            String k = sp.getString("posKey", "");
            long p = sp.getLong("posMs", -1L);
            long w = sp.getLong("posWallMs", 0L);
            // 片名读不到（爆米花控制栏收起时整段都没了）时 currentKey 是空的，
            // 这时也把上次的位置接上 —— 大不了换片时错几秒，读到真进度会立刻重锚，
            // 总比从 0 开始把片头弹幕糊到中段强
            if (p >= 0 && w > 0 && (k.equals(currentKey) || currentKey.isEmpty())) {
                posMs = p;
                gap = System.currentTimeMillis() - w;
            }
        } else {
            gap = SystemClock.uptimeMillis() - lastKnownWall;
        }
        if (posMs < 0) return;
        long now = SystemClock.uptimeMillis();
        boolean playing = isAudioActive();
        if (playing && gap >= 0 && gap < 120000L) {
            // 在播且断线不久：位置接着往前推，误差在 1 秒内
            timeline.seed(posMs + gap, now, true);
        } else {
            // 暂停中（位置本来就不动），或断线太久说不准 → 停在原位等真实进度
            timeline.seed(posMs, now, false);
        }
        audioKnown = true;
        audioPlaying = playing;
        audioChangedAt = now;
    }

    /**
     * 用记下来的作品信息恢复弹幕。
     * 关键：片名只有控制栏出现时才能从节点读到，所以不能"等下一次读到片名"再加载，
     * 否则用户随手按一下（暂停/弹控制栏/进设置）回来，弹幕就永远不回来了。
     */
    private void restoreMedia() {
        if (lastInfo != null) {
            ensureMedia(lastInfo);
            return;
        }
        SharedPreferences sp = getSharedPreferences("danmu", MODE_PRIVATE);
        String t = sp.getString("lastTitle", "");
        // 存过 "显示器外部源" / "HDMI1" 这种，说明上次是误识别，别再用它去搜
        if (t == null || t.isEmpty() || PlayerProbe.looksLikeJunk(t)) return;
        if (looksLikeAppName(t)) return;
        MediaInfo mi = new MediaInfo();
        mi.title = t;
        mi.season = sp.getInt("lastSeason", 0);
        mi.episode = sp.getInt("lastEp", 0);
        lastInfo = mi;
        ensureMedia(mi);
    }

    private void stopSession() {
        if (!sessionActive) return;
        sessionActive = false;
        pageMiss = 0;
        quietTick = 0;
        // 记住断线时的位置，回来时能接着推
        if (timeline.hasAnchor()) {
            lastKnownWall = SystemClock.uptimeMillis();
            lastKnownPos = timeline.positionAt(lastKnownWall);
            lastKnownKey = currentKey;
            getSharedPreferences("danmu", MODE_PRIVATE).edit()
                    .putString("posKey", currentKey)
                    .putLong("posMs", lastKnownPos)
                    .putLong("posWallMs", System.currentTimeMillis())
                    .apply();
        }
        if (pollHandler != null) pollHandler.removeCallbacks(pollTask);
        hideOverlay();
        log("session stop @" + (lastKnownPos / 1000L) + "s");
    }

    /** 主线程：把后台读到的节点文本应用到时间轴/会话（不碰节点，快） */
    private void applyPoll(String title, String left, String right, boolean noRoot,
                           boolean inPlayer, boolean hasPlayerUi, boolean audioActive,
                           boolean generic) {
        curGeneric = generic;
        if (noRoot) {
            // 控制栏完全收起时连根节点都拿不到，但"自动补一次进度"不能因此停摆。
            // 不过要是已经离开播放页很久（比如切到别的应用全屏），就别再瞎发按键和提示了。
            long now = SystemClock.uptimeMillis();
            boolean trusted = lastPlayerSeenWall == 0L || now - lastPlayerSeenWall < 30000L;
            if (!trusted) {
                noAnchorMs = 0L;
                return;
            }
            tickNoAnchor(now);
            return;
        }

        if (!inPlayer) {
            // 不在播放页（VidHub 列表/详情页、导航到别的应用）：不累加"没有进度"的时长，
            // 因此不会发按键、不会弹"读不到片名"。连续几轮还不在就收摊。
            pageMiss++;
            noAnchorMs = 0L;
            if (pageMiss >= 5) {
                pageMiss = 0;
                suppressStartUntil = SystemClock.uptimeMillis() + 6000L;
                log("not on player page, stop session");
                stopSession();
            }
            return;
        }
        pageMiss = 0;
        lastPlayerSeenWall = SystemClock.uptimeMillis();

        // VidHub 详情页会挂一个小的预览播放器，它也会自报播放 Activity，
        // 但那一屏读不到片名/进度。本次会话一直没读到播放控件，就认定不是播放页。
        if (hasPlayerUi) {
            quietTick = 0;
            sessionSawUi = true;
            suppressStartUntil = 0L;
        } else if (!sessionSawUi) {
            // 通用播放器：控制栏收起时本来就一个文本都读不到，只要还在出声就继续。
            // 位置靠墙钟从 0 推算（读到真实进度会立刻重锚），不能因为读不到控件就收摊。
            if (generic && audioActive) {
                quietTick = 0;
            } else if (++quietTick >= 8) {          // 约 2.4 秒
                quietTick = 0;
                suppressStartUntil = SystemClock.uptimeMillis() + 6000L;
                log("page has no player ui, stop & suppress");
                stopSession();
                return;
            }
        } else {
            quietTick = 0;
        }

        // 通用播放器只有"读到过进度条"才算真视频。电视切到 HDMI 输入源、相册、音乐
        // 这些界面也全屏也有声，但永远读不到进度，20 秒还读不到就收摊，
        // 免得一直挂着一层空弹幕还反复去搜一个不存在的片名。
        if (generic) {
            if (hasPlayerUi) {
                genericNoProgressMs = 0L;
            } else if (audioActive) {
                // 有声音就别急着收摊：爆米花这类播放器控制栏一收起，
                // 节点里那行时间就整段消失/冻住，读不到进度不等于不是视频。
                genericNoProgressMs = 0L;
            } else {
                genericNoProgressMs += POLL_MS_GENERIC;
                if (genericNoProgressMs >= 20000L) {
                    genericNoProgressMs = 0L;
                    suppressStartUntil = SystemClock.uptimeMillis() + 30000L;
                    if (!curPkg.isEmpty()) {                 // 5 分钟内别再为它开层
                        nonVideoUntil.put(curPkg, SystemClock.uptimeMillis() + 5L * 60L * 1000L);
                    }
                    log("generic: 20s without progress, not a video page, stop");
                    stopSession();
                    return;
                }
            }
        }

        {
            long now = SystemClock.uptimeMillis();
            syncAudioState(now, audioActive);

            // 通用播放器读不到进度文本（控制栏收起了）：从 0 开始按墙钟走。
            // 看剧基本都是从头放，这样弹幕也能对上；一旦读到真实进度会马上重锚纠正。
            if (generic && audioActive && !genericSeeded && !timeline.hasAnchor()) {
                genericSeeded = true;
                timeline.seed(0L, now, true);
                log("generic: no progress text, tick from 0");
            }

            if (right != null) {
                long dur = parseTime(right);
                if (dur > 0) timeline.setDuration(dur);
            }
            if (left != null) {
                long posMs = parseTime(left);
                if (posMs >= 0) {
                    /*
                     * 有些播放器（比如爆米花）控制栏收起后，节点里那行时间就冻在旧值上，
                     * 每 300ms 读到同一个数。如果照旧喂给时间轴，推算值越走越远、
                     * 两轮后又被拽回原处 —— 弹幕就永远停在原地不动。
                     * 所以只在进度真的变了的时候才重锚；没变的时候看声音：
                     *   有声音 = 播放器只是不刷新文本，让墙钟继续走；
                     *   没声音 = 真暂停，把时间轴停住。
                     */
                    boolean moved = lastPos < 0 || Math.abs(posMs - lastPos) >= 500L;
                    if (moved) {
                        timeline.onSample(posMs, now);
                        lastPos = posMs;
                    } else if (audioActive) {
                        timeline.setPlaying(true, now);
                    } else if (generic) {
                        /*
                         * 通用播放器不轻易判暂停。爆米花这类应用控制栏里那行时间是快照，
                         * 停住不刷新；而这台电视又报不出真实的音频状态 —— 两个不可靠的信号
                         * 一叠加就会把正在播的片子判成暂停，弹幕整层冻住（用户看到的就是
                         * "弹幕卡在这里不动"）。宁可暂停时多飘几条，也不能播放时不动。
                         */
                        timeline.setPlaying(true, now);
                    } else {
                        timeline.setPlaying(false, now);
                    }
                    if (now - posLogAt > 30000L) {
                        posLogAt = now;
                        log("pos " + (posMs / 1000L) + "s playing=" + timeline.isPlaying()
                                + " anchor=" + timeline.hasAnchor()
                                + " moved=" + moved + " audio=" + audioActive);
                    }
                }
            }
            if (title != null && !title.isEmpty()) {
                if (!titleConfirmed) {
                    titleConfirmed = true;
                    log("title confirmed: " + title);
                }
                ensureMedia(title);
            }

            // 一直没有真实进度 = 控制栏没弹出来。播放器只在控制栏可见时才暴露进度，
            // 所以这里主动让它弹一次（发一个"上"键，不会暂停），拿到进度就不再打扰。
            tickNoAnchor(now);
        }
    }

    /** 没有进度锚点 / 还没确认片名时的兜底：自动补一次控制栏，实在拿不到就在角落提示 */
    private void tickNoAnchor(long now) {
        // 通用播放器还一次都没读到过进度条：很可能这一屏根本不是视频
        // （电视切到 HDMI 输入源、相册、音乐播放页…）。这种情况绝不发"上"键、
        // 不弹"读不到片名"，否则等于在人家正常看电视时乱按键、乱刷字。
        // 20 秒还读不到进度，上面的超时判定会把会话收掉。
        if (curGeneric && !sessionSawUi) {
            noAnchorMs = 0L;
            return;
        }
        boolean needTitle = !titleConfirmed;
        if (!needTitle && timeline.hasAnchor()) {
            noAnchorMs = 0L;
            return;
        }
        noAnchorMs += POLL_MS;

        // 片名没确认时更急：2 秒就动手，因为再等下去显示的就是上一部的弹幕
        long waitMs = needTitle ? 2000L : 5000L;
        boolean windowExpired = now - nudgeWindowStart > 60000L;
        if (windowExpired) {
            nudgeWindowStart = now;
            nudgeInWindow = 0;
        }
        if (config.autoSync && !nudgeBroken && noAnchorMs > waitMs
                && nudgeInWindow < 3 && now - lastNudgeAt > 8000L) {
            requestProgressNudge();
        }

        // 一直读不到片名、nudge 也发满一轮了 → 说明手上这份弹幕很可能不是当前这部，宁可清空
        if (needTitle && nudgeInWindow >= 3 && !staleCleared) {
            staleCleared = true;
            log("title unconfirmed, drop stale items of " + currentKey);
            // 顺手把它标记成"失败"，免得 300ms 后轮询又把上一部重新拉回来
            failKey = currentKey;
            failAt = now;
            failCount = 3;
            currentKey = "";
            if (overlay != null) {
                overlay.clearDanmaku();
                overlay.showHint("读不到片名 · 按一下遥控器");
            }
            lastItems = null;
            lastItemsKey = "";
        }

        if (!hintShown && noAnchorMs > 12000L && overlay != null) {
            hintShown = true;
            overlay.showHint("弹幕待同步 · 按一下遥控器");
        }
    }

    private void ensureMedia(String rawTitle) {
        ensureMedia(TitleParser.parse(rawTitle));
    }

    /**
     * 用系统音频状态判断播放/暂停。控制栏不显示时这是唯一能拿到的"在不在播"信号，
     * 暂停时把推算冻结住，弹幕才不会越跑越偏。
     */
    private boolean isAudioActive() {
        if (audio == null) return false;
        /*
         * 不直接用 isMusicActive()：这台 MTK 电视走 HDMI/ARC 输出时它会误报 false，
         * 结果视频明明在放，应用却判成「暂停」，弹幕就整层冻住不动。
         * 改成遍历活跃播放配置，只看真正 state=STARTED 的媒体流。
         */
        try {
            java.util.List<android.media.AudioPlaybackConfiguration> list =
                    audio.getActivePlaybackConfigurations();
            int size = list == null ? -1 : list.size();
            int started = 0;
            boolean hit = false;
            if (list != null && !list.isEmpty()) {
                for (int i = 0; i < list.size(); i++) {
                    android.media.AudioPlaybackConfiguration c = list.get(i);
                    if (c == null) continue;
                    // 编译用的 android.jar 被裁过，没有 getPlayerState()，只能反射拿
                    int state = -1;
                    try {
                        Object v = c.getClass().getMethod("getPlayerState").invoke(c);
                        if (v instanceof Integer) state = ((Integer) v).intValue();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (state != 2) {                  // 2 = PLAYER_STATE_STARTED
                        continue;
                    }
                    started++;
                    android.media.AudioAttributes at = c.getAudioAttributes();
                    if (at == null) continue;
                    int usage = at.getUsage();
                    if (usage == android.media.AudioAttributes.USAGE_MEDIA
                            || usage == android.media.AudioAttributes.USAGE_UNKNOWN) {
                        hit = true;
                        break;
                    }
                }
                if (!audioProbeLogged) {
                    audioProbeLogged = true;
                    log("audio: list=" + size + " started=" + started + " hit=" + hit);
                }
                return hit;        // 列表能拿到且没有媒体流在放，就是真没声
            }
        } catch (Throwable ignored) {
        }
        // 2) AudioSystem.isStreamActive(STREAM_MUSIC)：@hide，但 Android 9 上反射还能调。
        //    电视上这个最准 —— 上面那个列表经常只返回一条无关的提示音，看不到正在放的正片。
        try {
            Class<?> as = Class.forName("android.media.AudioSystem");
            java.lang.reflect.Method m = as.getMethod("isStreamActive", int.class, int.class);
            Object r = m.invoke(null, 3, 0);           // 3 = STREAM_MUSIC
            if (r instanceof Boolean) {
                boolean b = ((Boolean) r).booleanValue();
                if (b) {
                    if (!audioProbeLogged) {
                        audioProbeLogged = true;
                        log("audio: streamActive=true (AudioSystem)");
                    }
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            boolean b = audio.isMusicActive();
            if (!audioProbeLogged) {
                audioProbeLogged = true;
                log("audio: fallback isMusicActive=" + b);
            }
            return b;
        } catch (Throwable t) {
            return false;
        }
    }

    private void syncAudioState(long now, boolean active) {
        if (audio == null) return;
        if (!audioKnown || active != audioPlaying) {
            audioPlaying = active;
            audioKnown = true;
            audioChangedAt = now;
            return;
        }
        if (now - audioChangedAt >= 1000L) {
            timeline.setPlaying(active, now);
        }
    }

    private int loadSeq = 0;
    /** 正在请求中的作品：轮询 300ms 一次，不拦的话同一个 key 会被反复重发，自己把自己打成超时 */
    private String pendingKey = "";
    private long pendingAt = 0L;
    /** 上一次失败的作品与失败次数，用于退避 */
    private String failKey = "";
    private long failAt = 0L;
    private int failCount = 0;

    private long failBackoff() {
        long s = 6000L * (1L << Math.min(failCount, 3));   // 6s → 12s → 24s → 48s
        return Math.min(s, 48000L);
    }

    private void ensureMedia(MediaInfo info) {
        if (info == null || info.title == null || info.title.isEmpty()) return;
        // 片名退化成应用名（"网易爆米花"/"VidHub"）时宁可什么都不做，
        // 等真正标题或上次记住的标题顶上，别拿应用名去搜一遍再把缓存弄脏
        if (looksLikeAppName(info.title)) return;
        info.durationMs = timeline.getDuration();
        String key = info.key();
        boolean hasData = lastItems != null && lastItemsKey.equals(key);
        // 同一集并且手上已经有数据 → 什么都不用做
        if (key.equals(currentKey) && hasData) {
            currentInfo.durationMs = info.durationMs;
            return;
        }
        long now = SystemClock.uptimeMillis();
        // 同一个作品还在请求中，等它回来再说
        if (key.equals(pendingKey) && now - pendingAt < 30000L) return;
        // 刚失败过，先别急着再打一次
        if (key.equals(failKey) && now - failAt < failBackoff()) return;

        boolean switched = !key.equals(currentKey);
        currentKey = key;
        currentInfo = info;
        lastInfo = info;
        log("media -> " + info.toString() + " key=" + key);
        // 记下最近播的作品，重启/回来时用它恢复
        getSharedPreferences("danmu", MODE_PRIVATE).edit()
                .putString("lastTitle", info.title)
                .putInt("lastEp", info.episode)
                .putInt("lastSeason", info.season)
                .apply();
        if (switched) {
            // 换片了：上一部的进度绝对不能沿用，否则新片一上来就跑到别的时长上
            timeline.reset();
            genericSeeded = false;
            lastKnownPos = -1L;
            lastKnownKey = "";
            noAnchorMs = 0L;
            hintShown = false;
            if (overlay != null) overlay.clearDanmaku();
            // reset 会把锚点一起清掉。如果这一轮刚好读到过进度，立刻接回来 ——
            // 否则控制栏一收起又要靠发"上"键去唤，用户会看到控制栏莫名反复弹出
            if (lastPos >= 0) {
                timeline.seed(lastPos, SystemClock.uptimeMillis(), true);
            }
        }

        final int seq = ++loadSeq;
        final String reqKey = key;
        pendingKey = reqKey;
        pendingAt = now;
        source.load(info, new DanmakuSource.Callback() {
            @Override
            public void onLoaded(final List<DanmakuItem> items, final String note) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != loadSeq) return;   // 已经换集，丢掉旧结果
                        pendingKey = "";
                        failKey = "";
                        failCount = 0;
                        lastItems = items;
                        lastItemsKey = reqKey;
                        log("danmaku loaded: " + note);
                        if (overlay != null) overlay.setItems(items);
                        else log("overlay gone, hold · " + note);
                    }
                });
            }

            @Override
            public void onError(final String message) {
                log("danmaku error: " + message);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != loadSeq) return;
                        pendingKey = "";
                        failKey = reqKey;
                        failAt = SystemClock.uptimeMillis();
                        failCount++;
                        // "搜不到"是数据源没收录，重试也没用 → 只在画面上轻提示一下
                        if (overlay != null) {
                            if (message != null && (message.contains("没收录") || message.contains("没有")
                                    || message.contains("没搜到"))) {
                                overlay.showHint("这部还没有弹幕源");
                            }
                        }
                        if (failCount > 3) return;      // 连续失败几次就别再打了
                        final long delay = failBackoff();
                        log("danmaku retry in " + (delay / 1000L) + "s");
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (sessionActive && !currentKey.isEmpty()) ensureMedia(currentInfo);
                            }
                        }, delay);
                    }
                });
            }
        });
    }

    private String text(AccessibilityNodeInfo root, String viewId) {
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId);
            if (list == null || list.isEmpty()) return null;
            AccessibilityNodeInfo n = list.get(0);
            try {
                CharSequence t = n.getText();
                return t == null ? null : t.toString();
            } finally {
                for (int i = 1; i < list.size(); i++) {
                    try { list.get(i).recycle(); } catch (Throwable ignored) { }
                }
                try { n.recycle(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** "02:07" / "1:02:07" → 毫秒 */
    static long parseTime(String s) {
        if (s == null) return -1;
        String t = s.trim();
        if (t.isEmpty()) return -1;
        String[] parts = t.split(":");
        try {
            if (parts.length == 2) {
                return (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * 1000L;
            } else if (parts.length == 3) {
                return ((Long.parseLong(parts[0]) * 3600)
                        + Long.parseLong(parts[1]) * 60
                        + Long.parseLong(parts[2])) * 1000L;
            }
        } catch (NumberFormatException ignored) { }
        return -1;
    }

    private void showOverlay() {
        // 只要不是真的挂在窗口上，就当它不存在。系统会悄悄移除悬浮窗，
        // 光看引用非空会误判"已经加过了"，结果后面一直不出弹幕也不报错。
        if (overlay != null && !overlay.isAttachedToWindow()) {
            log("overlay lost, rebuild");
            overlay = null;
        }
        if (overlay != null) return;
        if (settingsOpen) return;      // 设置页在前台，别往它上面盖弹幕
        if (!config.enabled) return;   // 用户选了「隐藏弹幕」
        final OverlayView v = new OverlayView(this);
        v.setTimeline(timeline);
        v.setConfig(config);
        v.setDetachListener(new OverlayView.DetachListener() {
            @Override
            public void onOverlayDetached(OverlayView which) {
                if (overlay != which) return;
                overlay = null;
                log("overlay detached by system");
                // 还在播放就补一块回去，别让画面一直空着
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!sessionActive) return;
                        showOverlay();
                        if (overlay != null && lastItems != null && lastItemsKey.equals(currentKey)) {
                            overlay.setItems(lastItems);
                            log("overlay restored with " + lastItems.size() + " items");
                        }
                    }
                }, 800L);
            }
        });
        overlay = v;

        // 只覆盖顶部弹幕区：合成面积小了 60%，SurfaceFlinger 每帧的混合开销跟着降
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels > 0 ? dm.widthPixels : 1920;
        int screenH = dm.heightPixels > 0 ? dm.heightPixels : 1080;
        float frac = Math.max(0.2f, Math.min(1f, config.areaFraction));
        int areaH = (int) (screenH * frac);
        int winH = Math.min(screenH, areaH + (int) (config.textSizePx * 1.6f) + 40);
        v.setAreaHeightPx(areaH);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                screenW,
                winH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        // Service 里 addView 的窗口默认走软件渲染，1080p 每帧 CPU 描字会掉帧
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(v, lp);
            log("overlay added");
        } catch (Throwable t) {
            log("overlay add failed: " + t);
            overlay = null;
        }
    }

    private void hideOverlay() {
        if (overlay == null) return;
        try { wm.removeViewImmediate(overlay); } catch (Throwable ignored) { }
        overlay = null;
        log("overlay removed");
    }

    /** 诊断：把当前界面上"能看到的文本"整理成报告发回设置页，用来确认某个播放器认不认得出 */
    private void runProbe() {
        if (pollHandler == null) {
            doProbe();
        } else {
            pollHandler.post(new Runnable() {
                @Override
                public void run() { doProbe(); }
            });
        }
    }

    private void doProbe() {
        int sw = screenW(), sh = screenH();
        Win w = null;
        String out;
        try {
            w = pickWindow(true);
            if (w == null) {
                out = "读不到窗口文本。\n播放器可能用了 SurfaceView / 系统限制，或者弹幕服务没连上。";
            } else {
                PlayerProbe.Result pr = PlayerProbe.probe(w.root, sw, sh);
                out = "包名：" + w.pkg
                        + "\n窗口标题：" + (w.title == null ? "（空）" : w.title.toString())
                        + "\n识别片名：" + (pr.title == null ? "（没认出来）" : pr.title)
                        + "\n识别进度：" + (pr.hasTimes
                        ? PlayerProbe.format(pr.posMs) + " / " + PlayerProbe.format(pr.durMs)
                        : "（没读到）")
                        + "\n窗口全屏：" + (isFullscreen(w.rect, sw, sh) ? "是" : "否")
                        + "\n允许挂弹幕：" + (isSupportedPkg(w.pkg) ? "是" : "否（被排除）")
                        + "\n界面文本：" + (pr.sample.isEmpty() ? "（无）" : pr.sample);
            }
        } catch (Throwable t) {
            out = "识别出错：" + t;
        } finally {
            if (w != null) recycleQuiet(w.root);
        }
        log("probe report\n" + out);
        try {
            Intent b = new Intent("tv.yaohui.danmu.PROBE_RESULT");
            b.putExtra("text", out);
            sendBroadcast(b);
        } catch (Throwable ignored) { }
    }

    private void log(String msg) {
        Log.println(android.util.Log.ERROR, TAG, msg);
    }
}
