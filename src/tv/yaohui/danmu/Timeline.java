package tv.yaohui.danmu;

/**
 * 播放时间轴估计器。
 *
 * 播放器只在控制栏可见时把真实进度告诉界面，所以采用"锚点 + 推算"：
 * 每读到一次真实进度就重新锚定，两次锚点之间的观测值顺带校准倍速。
 */
public class Timeline {

    private long anchorPos = 0L;
    private long anchorWall = 0L;
    private float rate = 1.0f;
    private boolean playing = false;
    private boolean hasAnchor = false;

    private long lastSamplePos = -1L;
    private long lastSampleWall = 0L;
    /** 当前这个"秒值"第一次被看到的墙钟时间（判断暂停、估倍速用） */
    private long sameSinceWall = 0L;
    /** 长窗口倍速估计（秒级采样的量化误差要靠长时间摊薄） */
    private long rateRefPos = -1L;
    private long rateRefWall = 0L;
    /** 连续多少轮推算值跑出合理区间 */
    private int outsideCount = 0;

    private long durationMs = 0L;

    public void reset() {
        hasAnchor = false;
        playing = false;
        rate = 1.0f;
        sameSinceWall = 0L;
        anchorPos = 0L;
        lastSamplePos = -1L;
        sameSinceWall = 0L;
        rateRefPos = -1L;
        rateRefWall = 0L;
        outsideCount = 0;
        durationMs = 0L;
    }

    public void setDuration(long d) {
        if (d > 0) durationMs = d;
    }

    public long getDuration() {
        return durationMs;
    }

    public boolean hasAnchor() {
        return hasAnchor;
    }

    public boolean isPlaying() {
        return playing;
    }

    public float rate() {
        return rate;
    }

    /** 外部（音频状态）判定播放/暂停：切换时把锚点平移到当前位置，位置不会跳 */
    public void setPlaying(boolean p, long wallMs) {
        if (playing == p) return;
        if (hasAnchor) {
            long cur = positionAt(wallMs);
            anchorPos = cur;
            anchorWall = wallMs;
        }
        playing = p;
    }

    /**
     * 界面读到真实进度时调用。
     *
     * 注意：播放器显示的是"分:秒"（parseTime 只能到秒），所以 posMs 是**这一秒的截断值**，
     * 真实位置在 [posMs, posMs+1000)。以前每 300ms 采样就重锚一次，等于把平滑推算
     * 一遍遍打回秒台阶上——弹幕看着就是"停一下、窜一下"的卡顿。
     * 现在只在推算值真的跑出这个区间（且连续两轮）时才重锚，其余时间保持连续；
     * 倍速用 ≥6 秒的长窗口慢慢估，避免速度忽快忽慢。
     */
    public void onSample(long posMs, long wallMs) {
        if (posMs < 0) return;

        if (!hasAnchor) {
            anchor(posMs + 500L, wallMs);
            playing = true;
            noteSample(posMs, wallMs);
            return;
        }

        boolean sameValue = (posMs == lastSamplePos);
        long unchangedFor = sameValue ? (wallMs - sameSinceWall) : 0L;

        // 秒值 1.6 秒都没动 → 暂停（正常播放时秒值必然在变）
        if (unchangedFor >= 1600L) {
            if (playing) {
                anchor(positionAt(wallMs), wallMs);
                playing = false;
            }
            noteSample(posMs, wallMs);
            return;
        }

        long pred = positionAt(wallMs);
        long mid = posMs + 500L;              // 这一秒的中点，误差期望为 0
        long low = posMs - 300L;
        long high = posMs + 1400L;

        if (pred < low || pred > high) {
            // 跑出合理区间：连续两轮都这样才硬重锚，避免一次抖动就跳位置
            if (++outsideCount >= 2) {
                anchor(mid, wallMs);
                outsideCount = 0;
            }
        } else {
            outsideCount = 0;   // 区间内一律不动位置，保持连续
        }
        playing = true;
        noteSample(posMs, wallMs);
    }

    private void noteSample(long posMs, long wallMs) {
        if (posMs != lastSamplePos) {
            // 秒值翻页：上一秒值从 prevValWall 持续到 now，可用来估倍速
            // 倍速只做长窗口估计（≥6 秒），秒级采样的量化误差才不会变成速度抖动
            if (rateRefPos < 0) {
                rateRefPos = posMs;
                rateRefWall = wallMs;
            } else if (wallMs - rateRefWall >= 6000L) {
                long dWall = wallMs - rateRefWall;
                long dPos = posMs - rateRefPos;
                if (dPos > 0) {
                    float observed = (float) dPos / (float) dWall;
                    if (observed > 0.3f && observed < 3.0f) {
                        rate = clamp(rate * 0.7f + observed * 0.3f, 0.5f, 2.5f);
                    }
                }
                rateRefPos = posMs;
                rateRefWall = wallMs;
            }
            sameSinceWall = wallMs;
        }
        lastSamplePos = posMs;
        lastSampleWall = wallMs;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void anchor(long posMs, long wallMs) {
        anchorPos = posMs;
        anchorWall = wallMs;
        hasAnchor = true;
    }

    /**
     * 断线恢复用：手上一时读不到真实进度（控制栏没弹出来），
     * 先用上次已知位置 + 墙钟接着走，等真正的进度值来了会自动重锚校正。
     */
    public void seed(long posMs, long wallMs, boolean asPlaying) {
        if (posMs < 0) return;
        anchor(posMs, wallMs);
        playing = asPlaying;
        lastSamplePos = -1L;
        lastSampleWall = 0L;
        sameSinceWall = 0L;
        rateRefPos = posMs;
        rateRefWall = wallMs;
        outsideCount = 0;
    }

    /** 当前估计位置 */
    public long positionAt(long wallMs) {
        if (!hasAnchor) return 0L;
        long p = playing ? anchorPos + (long) ((wallMs - anchorWall) * rate) : anchorPos;
        if (p < 0) p = 0;
        if (durationMs > 0 && p > durationMs) p = durationMs;
        return p;
    }
}
