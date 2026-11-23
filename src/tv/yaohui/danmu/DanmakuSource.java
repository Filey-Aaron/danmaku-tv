package tv.yaohui.danmu;

import java.util.List;

/**
 * 弹幕数据源接口。真实实现（弹弹play / 自建 danmu_api）只要实现这个接口即可替换。
 */
public interface DanmakuSource {

    interface Callback {
        void onLoaded(List<DanmakuItem> items, String note);

        void onError(String message);
    }

    /** 异步拉取某部作品某一集的弹幕，回调可能在其他线程。 */
    void load(MediaInfo info, Callback cb);

    String name();
}
