package tv.yaohui.danmu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;

/**
 * 弹幕渲染层。贴在视频上方的透明 View，自己按时间轴驱动，不依赖播放器。
 */
public class OverlayView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 贴字形缓存用（alpha 从这里走，避免每条弹幕重设 Paint） */
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** 直画文字用：只填色 + 一圈阴影描黑边（硬件渲染下比 STROKE 描边便宜得多） */
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * 字形缓存：一条弹幕的文字+描边只渲染一次成小位图，之后每帧只是贴图。
     * TV 的 CPU 软描字（drawText + 描边 + 抗锯齿）是全屏 30fps 的主因。
     */
    private final LinkedHashMap<String, Bitmap> glyphCache =
            new LinkedHashMap<String, Bitmap>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Bitmap> e) {
                    return size() > GLYPH_CACHE_MAX;   // LRU 淘汰，不整体清空
                }
            };
    /** true=贴字形位图；false=直接硬件画字（A/B 用） */
    static final boolean USE_GLYPH_BITMAP = true;
    /** 临时对照实验：只走帧循环、不画内容 */
    static final boolean NO_DRAW_TEST = false;
    private static final int GLYPH_PAD = 6;
    private static final int GLYPH_CACHE_MAX = 400;

    /** 预渲染：主线程只入队，后台线程负责把文字画成位图，避免在渲染帧里建位图（单帧会飙到几十毫秒） */
    private final java.util.concurrent.ConcurrentLinkedQueue<DanmakuItem> glyphJobs =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Bitmap> staged =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile float glyphTextSize = 44f;
    private Thread glyphWorker;
    private volatile boolean workerStop = false;

    /** 窗口只覆盖弹幕区域时，用它表示可用的弹幕高度（0 = 按整屏 × areaFraction 算） */
    private int areaHeightOverride = 0;

    private List<DanmakuItem> items = new ArrayList<>();
    private final List<DanmakuItem> active = new ArrayList<>();
    private int nextIndex = 0;

    private Timeline timeline;
    private DanmuConfig cfg = new DanmuConfig();

    private int laneCount = 1;
    private int laneHeight = 60;
    private long[] laneFreeAt = new long[1];
    private long[] topFreeAt = new long[3];
    private int topLaneCount = 3;

    private static final int TOP_DURATION_MS = 4200;
    private static final int LANE_MARGIN_PX = 70;

    private long lastPos = -1L;

    /** 一次性角标提示（例如"弹幕待同步"） */
    private String hint = null;
    private long hintUntil = 0L;
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 被 WindowManager 摘掉时通知外面（系统会在某些时机直接移除悬浮窗） */
    public interface DetachListener { void onOverlayDetached(OverlayView v); }

    private DetachListener detachListener;

    public void setDetachListener(DetachListener l) {
        this.detachListener = l;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopGlyphWorker();
        if (detachListener != null) detachListener.onOverlayDetached(this);
    }

    private void startGlyphWorker() {
        if (glyphWorker != null) return;
        glyphWorker = new Thread(new Runnable() {
            @Override
            public void run() { glyphWorkerLoop(); }
        }, "danmu-glyph");
        glyphWorker.setDaemon(true);
        glyphWorker.start();
    }

    private void stopGlyphWorker() {
        workerStop = true;
        glyphJobs.clear();
        glyphWorker = null;
    }

    private void glyphWorkerLoop() {
        Paint wf = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint ws = new Paint(Paint.ANTI_ALIAS_FLAG);
        wf.setStyle(Paint.Style.FILL);
        ws.setStyle(Paint.Style.STROKE);
        ws.setColor(0xCC000000);
        while (!workerStop) {
            DanmakuItem it = glyphJobs.poll();
            if (it == null) {
                try { Thread.sleep(20L); } catch (InterruptedException e) { return; }
                continue;
            }
            String key = it.color + "|" + it.text;
            if (staged.containsKey(key)) continue;
            float ts = glyphTextSize;
            Bitmap bm = renderGlyph(wf, ws, it.text, it.color, ts);
            if (bm != null) {
                if (staged.size() > 260) staged.clear();   // 正常不会到，防手滑
                staged.put(key, bm);
            }
        }
    }

    private static Bitmap renderGlyph(Paint f, Paint s, String text, int color, float ts) {
        try {
            float tw = f.measureText(text);
            int w = Math.max(1, (int) Math.ceil(tw) + GLYPH_PAD * 2);
            int h = (int) (ts * 1.5f) + GLYPH_PAD * 2;
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            f.setTextSize(ts);
            s.setTextSize(ts);
            s.setStrokeWidth(Math.max(2f, ts * 0.075f));
            f.setColor(color);
            f.setAlpha(255);
            s.setAlpha(255);
            float baseY = GLYPH_PAD + ts;
            c.drawText(text, GLYPH_PAD, baseY, s);
            c.drawText(text, GLYPH_PAD, baseY, f);
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    public OverlayView(Context c) {
        super(c);
        init();
    }

    public OverlayView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setBackgroundColor(Color.TRANSPARENT);
        fill.setStyle(Paint.Style.FILL);
        fill.setFakeBoldText(false);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f);
        stroke.setColor(0xCC000000);
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setShadowLayer(3.5f, 0f, 1.5f, 0xF0000000);
        startGlyphWorker();
        hintPaint.setColor(0xE6FFFFFF);
    }

    /** 屏幕右下角飘一条小字，几秒后自动消失 */
    public void showHint(String text) {
        hint = text;
        hintUntil = SystemClock.uptimeMillis() + 6000L;
        invalidate();
    }

    public void setTimeline(Timeline t) {
        this.timeline = t;
    }

    public void setConfig(DanmuConfig c) {
        this.cfg = c;
        relayout();
        invalidate();
    }

    public void setItems(List<DanmakuItem> list) {
        items = list == null ? new ArrayList<DanmakuItem>() : list;
        resetState();
        invalidate();
    }

    public void clearDanmaku() {
        items = new ArrayList<>();
        resetState();
        invalidate();
    }

    private void resetState() {
        active.clear();
        nextIndex = 0;
        lastPos = -1L;
        for (int i = 0; i < laneFreeAt.length; i++) laneFreeAt[i] = 0L;
        for (int i = 0; i < topFreeAt.length; i++) topFreeAt[i] = 0L;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        relayout();
    }

    /** 窗口只覆盖顶部弹幕区时由 Service 告知真实可用高度 */
    public void setAreaHeightPx(int px) {
        areaHeightOverride = Math.max(0, px);
        relayout();
        invalidate();
    }

    private void relayout() {
        int h = getHeight() > 0 ? getHeight() : 1080;
        laneHeight = (int) (cfg.textSizePx * 1.55f);
        int area = areaHeightOverride > 0 ? areaHeightOverride : (int) (h * cfg.areaFraction);
        laneCount = Math.max(1, area / Math.max(1, laneHeight));
        if (laneFreeAt.length < laneCount) laneFreeAt = new long[laneCount];
        for (int i = 0; i < laneFreeAt.length; i++) laneFreeAt[i] = 0L;
        if (topFreeAt.length < topLaneCount) topFreeAt = new long[topLaneCount];
        for (int i = 0; i < topFreeAt.length; i++) topFreeAt[i] = 0L;

        fill.setTextSize(cfg.textSizePx);
        stroke.setTextSize(cfg.textSizePx);
        stroke.setStrokeWidth(Math.max(2f, cfg.textSizePx * 0.075f));
        glyphTextSize = cfg.textSizePx;
        clearGlyphCache();
    }

    private void clearGlyphCache() {
        // 不 recycle：正在滚的弹幕还引用着这些位图，强行回收会炸"recycled bitmap"
        glyphCache.clear();
        staged.clear();
        for (int i = 0; i < active.size(); i++) active.get(i).glyph = null;
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                items.get(i).prefetchQueued = false;
            }
        }
    }

    /** 取（或首次生成）一条弹幕的位图：缓存 → 后台预渲染结果 → 兜底同步渲染 */
    private Bitmap glyphFor(DanmakuItem it) {
        String key = it.color + "|" + it.text;
        Bitmap b = glyphCache.get(key);
        if (b != null && !b.isRecycled()) return b;
        b = staged.remove(key);
        if (b != null && !b.isRecycled()) {
            glyphCache.put(key, b);
            return b;
        }

        float tw = fill.measureText(it.text);
        int w = Math.max(1, (int) Math.ceil(tw) + GLYPH_PAD * 2);
        int h = (int) (cfg.textSizePx * 1.5f) + GLYPH_PAD * 2;
        Bitmap bm;
        try {
            bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
        Canvas c = new Canvas(bm);
        fill.setColor(it.color);
        fill.setAlpha(255);
        stroke.setAlpha(255);
        float baseY = GLYPH_PAD + cfg.textSizePx;
        c.drawText(it.text, GLYPH_PAD, baseY, stroke);
        c.drawText(it.text, GLYPH_PAD, baseY, fill);
        glyphCache.put(key, bm);
        return bm;
    }

    // —— 自测：每 5 秒报一次平均绘制耗时，用来判断掉帧是不是画字造成的 ——
    private long statFrames = 0L;
    private long statNanos = 0L;
    private long statSince = 0L;
    private int statActive = 0;
    private long statMaxDraw = 0L;
    private long statLastFrameAt = 0L;
    private long statMaxGap = 0L;
    private long statPos = 0L;
    private int statGaps = 0;

    @Override
    protected void onDraw(Canvas canvas) {
        long t0 = System.nanoTime();
        super.onDraw(canvas);
        drawHint(canvas);
        if (timeline == null || !cfg.enabled || items.isEmpty()) {
            postInvalidateOnAnimation();
            return;
        }

        long now = SystemClock.uptimeMillis();
        long pos = timeline.positionAt(now);

        // 首次拿到锚点（可能已经播了一半）：跳过之前的弹幕，不要一次性倒出来
        if (lastPos < 0) {
            nextIndex = lowerBound(pos);
        }

        // 检测拖动/跳转：位置大幅回退或跳跃就重建当前窗口
        if (lastPos >= 0 && (pos < lastPos - 1500 || pos > lastPos + 4000)) {
            active.clear();
            nextIndex = lowerBound(pos - 300);
            for (int i = 0; i < laneFreeAt.length; i++) laneFreeAt[i] = pos;
            for (int i = 0; i < topFreeAt.length; i++) topFreeAt[i] = pos;
        }
        lastPos = pos;

        while (nextIndex < items.size() && items.get(nextIndex).timeMs <= pos) {
            DanmakuItem it = items.get(nextIndex);
            nextIndex++;
            spawn(it, pos);
        }

        // 提前 2.5 秒把要出现的弹幕排进后台渲染队列，这样轮到它上场时位图已经好了。
        // （在渲染帧里现建位图会单帧卡 20~100ms，就是"隔几帧顿一下"的来源）
        int ahead = nextIndex;
        int queuedNow = 0;
        while (ahead < items.size() && queuedNow < 24
                && items.get(ahead).timeMs <= pos + 2500L) {
            DanmakuItem nx = items.get(ahead);
            if (!nx.prefetchQueued && nx.glyph == null) {
                nx.prefetchQueued = true;
                if (!glyphCache.containsKey(nx.color + "|" + nx.text)) glyphJobs.add(nx);
                queuedNow++;
            }
            ahead++;
        }

        int w = getWidth();
        int alpha = (int) (255f * Math.max(0, Math.min(100, cfg.alphaPct)) / 100f);

        Iterator<DanmakuItem> iter = active.iterator();
        while (!NO_DRAW_TEST && iter.hasNext()) {
            DanmakuItem it = iter.next();
            float x;
            float y;
            long age = pos - it.timeMs;
            if (it.type == DanmakuItem.TYPE_TOP) {
                if (age > TOP_DURATION_MS) { iter.remove(); continue; }
                x = (w - it.width) / 2f;
                y = laneY(it.lane);
            } else {
                x = w - age * it.speedPx / 1000f;
                if (x + it.width < 0) { iter.remove(); continue; }
                y = laneY(it.lane);
            }
            if (USE_GLYPH_BITMAP) {
                if (it.glyph == null) it.glyph = glyphFor(it);
                if (it.glyph == null) continue;
                bmpPaint.setAlpha(alpha);
                canvas.drawBitmap(it.glyph, x - GLYPH_PAD,
                        y - (GLYPH_PAD + cfg.textSizePx), bmpPaint);
            } else {
                shadowPaint.setColor(it.color);
                shadowPaint.setAlpha(alpha);
                canvas.drawText(it.text, x, y, shadowPaint);
            }
        }

        postInvalidateOnAnimation();

        long dt = System.nanoTime() - t0;
        statFrames++;
        statNanos += dt;
        if (dt > statMaxDraw) statMaxDraw = dt;
        statActive = active.size();
        statPos = pos;
        long nowMs = SystemClock.uptimeMillis();
        if (statLastFrameAt > 0) {
            long gap = nowMs - statLastFrameAt;
            if (gap > statMaxGap) statMaxGap = gap;
            if (gap > 25) statGaps++;
        }
        statLastFrameAt = nowMs;
        if (statSince == 0L) statSince = nowMs;
        if (nowMs - statSince >= 5000L) {
            android.util.Log.i("DanmuSvc", String.format(java.util.Locale.US,
                    "draw avg=%.2f max=%.1fms frames=%d fps=%.0f gaps=%d maxgap=%dms"
                            + " active=%d items=%d next=%d pos=%.1fs lanes=%d",
                    statNanos / 1e6 / Math.max(1, statFrames), statMaxDraw / 1e6,
                    statFrames, statFrames * 1000f / Math.max(1, nowMs - statSince),
                    statGaps, statMaxGap, statActive,
                    items == null ? -1 : items.size(), nextIndex,
                    statPos / 1000f, laneCount));
            statFrames = 0L; statNanos = 0L; statSince = nowMs;
            statMaxDraw = 0L; statGaps = 0; statMaxGap = 0L;
        }
    }

    private float laneY(int lane) {
        return laneHeight * (lane + 1) - laneHeight * 0.22f;
    }

    private void drawHint(Canvas canvas) {
        if (hint == null) return;
        long now = SystemClock.uptimeMillis();
        if (now > hintUntil) { hint = null; return; }
        float size = Math.max(26f, cfg.textSizePx * 0.62f);
        hintPaint.setTextSize(size);
        float w = hintPaint.measureText(hint);
        float x = getWidth() - w - size * 0.8f;
        float y = getHeight() - size * 1.4f;
        hintPaint.setAlpha(150);
        canvas.drawText(hint, x, y, hintPaint);
    }

    private void spawn(DanmakuItem it, long pos) {
        it.width = fill.measureText(it.text);

        if (it.type == DanmakuItem.TYPE_TOP) {
            int lane = -1;
            for (int i = 0; i < topLaneCount; i++) {
                if (topFreeAt[i] <= pos) { lane = i; break; }
            }
            if (lane < 0) return;   // 顶部满了就丢掉，宁缺勿叠
            topFreeAt[lane] = pos + TOP_DURATION_MS;
            it.lane = lane;
            it.alive = true;
            active.add(it);
            return;
        }

        int lane = -1;
        for (int i = 0; i < laneCount; i++) {
            if (laneFreeAt[i] <= pos) { lane = i; break; }
        }
        if (lane < 0) return;       // 所有轨道都占着，这条丢弃

        it.speedPx = Math.max(60, cfg.speedPx);
        it.lane = lane;
        it.alive = true;
        // 该轨道要等这条弹幕的尾巴完全进场后才允许下一条
        laneFreeAt[lane] = pos + (long) ((LANE_MARGIN_PX + it.width) * 1000f / it.speedPx);
        active.add(it);
    }

    private int lowerBound(long timeMs) {
        int lo = 0, hi = items.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (items.get(mid).timeMs < timeMs) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
