package tv.yaohui.danmu;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 假弹幕源：按作品和时长程序化生成，用于验证渲染与同步。
 * 换成真实源只需实现 DanmakuSource 并在 DanmuConfig 里改一行。
 */
public class MockSource implements DanmakuSource {

    private static final String[] POOL = {
            "前排占座", "这段太真实了", "演员选得好", "笑死我了",
            "有字幕吗", "这画质可以", "背景音乐是什么", "第二遍看了",
            "这里有点拖", "神作预定", "弹幕测试一下", "同步准不准看这里",
            "我是在电视上看的", "这集节奏不错", "终于等到更新",
            "声音有点小", "这个镜头绝了", "别剧透啊",
    };

    private static final int[] COLORS = {
            0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
            0xFFFFD54F, 0xFF80D8FF, 0xFFFF8A80, 0xFFB9F6CA,
    };

    @Override
    public String name() {
        return "内置测试源";
    }

    @Override
    public void load(final MediaInfo info, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long dur = info.durationMs > 0 ? info.durationMs : 45 * 60 * 1000L;
                Random r = new Random((info.key() + dur).hashCode());
                List<DanmakuItem> out = new ArrayList<>();
                long t = 800;
                int i = 0;
                while (t < dur - 2000 && out.size() < 3000) {
                    String text = POOL[r.nextInt(POOL.length)];
                    if (i % 17 == 0) text = info.title + " 测试弹幕 #" + (i + 1);
                    int color = COLORS[r.nextInt(COLORS.length)];
                    // 大约 8% 做成顶部固定弹幕
                    int type = r.nextInt(100) < 8 ? DanmakuItem.TYPE_TOP : DanmakuItem.TYPE_SCROLL;
                    out.add(new DanmakuItem(t, text, color, type));
                    t += 700 + r.nextInt(2600);
                    i++;
                }
                cb.onLoaded(out, name() + " · " + out.size() + " 条");
            }
        }, "mock-source").start();
    }
}
