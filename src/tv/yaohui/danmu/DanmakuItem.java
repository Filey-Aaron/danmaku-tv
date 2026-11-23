package tv.yaohui.danmu;

import android.graphics.Bitmap;

/** 一条弹幕。timeMs 相对视频开头。 */
public class DanmakuItem {
    public static final int TYPE_SCROLL = 0;
    public static final int TYPE_TOP = 1;

    public final long timeMs;
    public final String text;
    public final int color;
    public final int type;

    // 运行时状态
    long spawnWallMs;
    int lane;
    float width;
    int speedPx;      // 每帧位移前的像素/秒
    boolean alive;
    /** 渲染好的字形位图（含描边），由 OverlayView 缓存（后台线程写入，主线程读） */
    volatile Bitmap glyph;
    /** 是否已经排进预渲染队列 */
    boolean prefetchQueued;

    public DanmakuItem(long timeMs, String text, int color, int type) {
        this.timeMs = timeMs;
        this.text = text;
        this.color = color;
        this.type = type;
    }
}
