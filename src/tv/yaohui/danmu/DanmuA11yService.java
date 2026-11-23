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
                guardWith("", false, false);
            }
            handler.postDelayed(this, 1500L);
        }
    };

    private final Runnable readPkgTask = new Runnable() {
        @Override
        public void run() {
            final String pkg;
            final boolean audioActive = isAudioActive();
            boolean ui = false;
            String pk = "";
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) {
                    // 播放页控制栏收起时活动窗口只剩一层 surface，拿不到根节点。
                    // 这时从窗口列表里找 VidHub 的窗口，否则服务重启后会一直认不出在播放。
                    root = vidHubWindowRoot();
                }
                if (root != null) {
                    try {
                        CharSequence p = root.getPackageName();
                        pk = p == null ? "" : p.toString();
                        ui = text(root, ID_TITLE) != null
                                || text(root, ID_LEFT) != null
                                || text(root, ID_RIGHT) != null;
                    } finally {
                        try { root.recycle(); } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable t) {
                return;
            }
            pkg = pk;
            final boolean playerUi = ui;
            handler.post(new Runnable() {
                @Override
                public void run() { guardWith(pkg, audioActive, playerUi); }
            });
        }
    };

    private void guardWith(String pkg, boolean audioActive, boolean playerUi) {
        {
            try {
                boolean inVidHub = PKG_VIDHUB.equals(pkg);
                // VidHub 的列表页/详情页跟播放页是同一个包名，光看包名会在详情页
                // 反复开/关弹幕层（还会弹"读不到片名"）。这里优先信无障碍事件报的界面类型。
                boolean knownOther = "other".equals(curActivity);
                // 声音只作辅助判据：详情页的预告片也有声，靠上面的 knownOther 优先排除；
                // 万一事件没报界面类型，poll 那边 2.4 秒读不到播放控件也会停掉并冷却
                boolean looksPlaying = playerUi || "play".equals(curActivity) || audioActive;

                if (inVidHub && knownOther) {
                    // 明确在 VidHub 的列表/详情页：收摊，别在这儿开层、别发按键
                    if (sessionActive) stopSession();
                } else if (inVidHub && !sessionActive && looksPlaying
                        && SystemClock.uptimeMillis() >= suppressStartUntil) {
                    offlineCount = 0;
                    startSession();
                } else if (inVidHub && sessionActive) {
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
                    // 有声音说明还在放 VidHub（界面节点偶尔会报错包名），多给几次机会
                    offlineCount++;
                    int limit = audioActive ? 12 : 5;
                    if (offlineCount >= limit) stopSession();
                }
            } catch (Throwable t) {
                Log.w(TAG, "guard error", t);
            }
        }
    }

    /** 从窗口列表里找 VidHub 的窗口根节点（调用方负责 recycle），找不到返回 null */
    private AccessibilityNodeInfo vidHubWindowRoot() {
        java.util.List<AccessibilityWindowInfo> wins;
        try {
            wins = getWindows();
        } catch (Throwable t) {
            return null;
        }
        if (wins == null) return null;
        for (int i = 0; i < wins.size(); i++) {
            AccessibilityWindowInfo w = wins.get(i);
            if (w == null) continue;
            try {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                CharSequence p = r.getPackageName();
                if (p != null && PKG_VIDHUB.contentEquals(p)) {
                    try { w.recycle(); } catch (Throwable ignored) { }
                    return r;          // 交给调用方 recycle
                }
                try { r.recycle(); } catch (Throwable ignored) { }
            } catch (Throwable ignored) {
            } finally {
                try { w.recycle(); } catch (Throwable ignored) { }
            }
        }
        return null;
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
            final String title;
            final String left;
            final String right;
            final boolean noRoot;
            final boolean inPlayer;
            final boolean hasPlayerUi;
            final boolean audioActive = isAudioActive();
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) {
                    title = null; left = null; right = null; noRoot = true;
                    inPlayer = true; hasPlayerUi = false;
                } else {
                    noRoot = false;
                    try {
                        title = text(root, ID_TITLE);
                        left = text(root, ID_LEFT);
                        right = text(root, ID_RIGHT);
                        CharSequence pn = root.getPackageName();
                        String pkg = pn == null ? "" : pn.toString();
                        // 同一个 VidHub 里还分列表页/详情页。只要无障碍事件说在播放页
                        // （控制栏收起时读不到控件，这很常见），或者确实读到了播放控件，就算播放页
                        hasPlayerUi = title != null || left != null || right != null;
                        // 播放页控制栏收起时读不到控件，但声音还在放 —— 这时必须算播放页，
                        // 否则会被判成"离开播放页"而把会话停掉（重启后最容易踩这个坑）
                        inPlayer = PKG_VIDHUB.equals(pkg)
                                && !"other".equals(curActivity)
                                && ("play".equals(curActivity) || hasPlayerUi || audioActive);
                    } finally {
                        try { root.recycle(); } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "poll read error", t);
                if (pollHandler != null) pollHandler.postDelayed(this, POLL_MS);
                return;
            }
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!sessionActive) return;
                    applyPoll(title, left, right, noRoot, inPlayer, hasPlayerUi, audioActive);
                }
            });
            if (pollHandler != null) pollHandler.postDelayed(this, POLL_MS);
        }
    };

    private final BroadcastReceiver reloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            config = DanmuConfig.load(DanmuA11yService.this);
            applyKeyFilter();
            // 数据源可能被换了（测试源 <-> 弹幕API），重建后对当前这集重新拉一次
            source = config.newSource();
            currentKey = "";
            lastItems = null;
            lastItemsKey = "";
            lastPos = -1L;
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

        if (c.contains("TVVideoPlayActivity")) {
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
            if (p >= 0 && w > 0 && k.equals(currentKey)) {
                posMs = p;
                gap = System.currentTimeMillis() - w;
            }
        } else {
            gap = SystemClock.uptimeMillis() - lastKnownWall;
        }
        if (posMs < 0) return;
        long now = SystemClock.uptimeMillis();
        boolean playing = false;
        try {
            if (audio != null) playing = audio.isMusicActive();
        } catch (Throwable ignored) { }
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
        if (t == null || t.isEmpty()) return;
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
                           boolean inPlayer, boolean hasPlayerUi, boolean audioActive) {
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
            if (++quietTick >= 8) {          // 约 2.4 秒
                quietTick = 0;
                suppressStartUntil = SystemClock.uptimeMillis() + 6000L;
                log("page has no player ui, stop & suppress");
                stopSession();
                return;
            }
        } else {
            quietTick = 0;
        }

        {
            long now = SystemClock.uptimeMillis();
            syncAudioState(now, audioActive);

            if (right != null) {
                long dur = parseTime(right);
                if (dur > 0) timeline.setDuration(dur);
            }
            if (left != null) {
                long posMs = parseTime(left);
                if (posMs >= 0) {
                    timeline.onSample(posMs, now);
                    lastPos = posMs;
                    if (now - posLogAt > 30000L) {
                        posLogAt = now;
                        log("pos " + (posMs / 1000L) + "s playing=" + timeline.isPlaying()
                                + " anchor=" + timeline.hasAnchor());
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
        try {
            return audio != null && audio.isMusicActive();
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
            lastKnownPos = -1L;
            lastKnownKey = "";
            noAnchorMs = 0L;
            hintShown = false;
            if (overlay != null) overlay.clearDanmaku();
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
        if (!config.enabled) return;
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

    private void log(String msg) {
        Log.i(TAG, msg);
    }
}
