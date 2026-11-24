package tv.yaohui.danmu;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 设置界面。故意不注册桌面图标（没有 LAUNCHER / LEANBACK 入口）。
 * 打开方式：adb shell am start -n tv.yaohui.danmu/.SettingsActivity
 * 也可直接带参数写入配置：
 *   --es source api --es api http://192.168.1.100:9321 --es token 87654321
 */
public class SettingsActivity extends Activity {

    private static final int MIN_TEXT = 24, MAX_TEXT = 96;
    private static final int MIN_SPEED = 80, MAX_SPEED = 400;
    private static final int MIN_ALPHA = 20, MAX_ALPHA = 100;

    private DanmuConfig cfg;
    private TextView status;
    private TextView summary;
    private TextView sourceState;
    private TextView testResult;
    private TextView lastProbeView;
    private EditText apiInput;
    private EditText tokenInput;
    private EditText blacklistInput;

    /** 弹幕总开关：按钮文案和右侧状态都跟着当前开关变 */
    private Button danmuBtn;
    private TextView danmuState;

    /** 步进器的数值展示，refresh() 时统一刷新 */
    private final java.util.Map<String, TextView> valueViews = new java.util.HashMap<>();

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = DanmuConfig.load(this);
        applyIntentExtras();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(60, 40, 60, 40);
        root.setBackgroundColor(0xFF14161A);

        TextView title = new TextView(this);
        title.setText("电视弹幕伴侣");
        title.setTextSize(30);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(17);
        status.setTextColor(0xFFB9C2CF);
        status.setPadding(0, 14, 0, 8);
        root.addView(status);

        summary = new TextView(this);
        summary.setTextSize(17);
        summary.setTextColor(0xFF8FD6A8);
        summary.setPadding(0, 0, 0, 8);
        root.addView(summary);

        sourceState = new TextView(this);
        sourceState.setTextSize(17);
        sourceState.setTextColor(0xFFFFD54F);
        sourceState.setPadding(0, 0, 0, 4);
        root.addView(sourceState);

        testResult = new TextView(this);
        testResult.setTextSize(17);
        testResult.setTextColor(0xFF9AA5B1);
        testResult.setPadding(0, 0, 0, 14);
        root.addView(testResult);

        lastProbeView = new TextView(this);
        lastProbeView.setTextSize(15);
        lastProbeView.setTextColor(0xFF7F8C9B);
        lastProbeView.setPadding(0, 0, 0, 12);
        root.addView(lastProbeView);

        // ---------- 弹幕源 ----------
        root.addView(sectionTitle("弹幕源"));

        root.addView(row("切换数据源", null, new Runnable() {
            @Override
            public void run() {
                cfg.sourceType = DanmuConfig.SRC_API.equals(cfg.sourceType)
                        ? DanmuConfig.SRC_MOCK : DanmuConfig.SRC_API;
                apply();
            }
        }));

        apiInput = input("接口地址", "http://192.168.1.100:9321", cfg.apiBase, false);
        root.addView(holder(apiInput));

        tokenInput = input("TOKEN", "留空=默认 87654321", cfg.apiToken, false);
        root.addView(holder(tokenInput));

        root.addView(row("填入示例地址", "改成你自己 danmu_api 的地址", new Runnable() {
            @Override
            public void run() {
                apiInput.setText("http://192.168.1.100:9321");
                cfg.apiBase = "http://192.168.1.100:9321";
                cfg.sourceType = DanmuConfig.SRC_API;
                apply();
            }
        }));

        root.addView(row("保存并重载", "让播放中的这集立刻重新拉弹幕", new Runnable() {
            @Override
            public void run() {
                cfg.apiBase = apiInput.getText().toString().trim();
                cfg.apiToken = tokenInput.getText().toString().trim();
                if (blacklistInput != null) {
                    cfg.blacklist = blacklistInput.getText().toString().trim();
                }
                cfg.save(SettingsActivity.this);
                sendBroadcast(new Intent("tv.yaohui.danmu.RELOAD"));
                refresh();
                toast("已保存并通知播放器重载");
            }
        }));

        root.addView(row("测试连接", "用最近播过的片名试拉一次", new Runnable() {
            @Override
            public void run() {
                testSource();
            }
        }));

        root.addView(row("识别当前界面", "看能不能从播放器里读出片名和进度", new Runnable() {
            @Override
            public void run() {
                testResult.setTextColor(0xFF9AA5B1);
                testResult.setText("正在识别…（读的是屏幕上最大那个窗口）");
                sendBroadcast(new Intent("tv.yaohui.danmu.PROBE"));
            }
        }));

        // ---------- 显示 ----------
        root.addView(sectionTitle("显示"));

        root.addView(row("无障碍服务", "打开系统无障碍设置", new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Throwable ignored) { }
            }
        }));

        // 弹幕总开关：按钮文字写成「动作」（按下去会发生什么），右侧常驻显示当前状态
        LinearLayout switchBox = new LinearLayout(this);
        switchBox.setOrientation(LinearLayout.HORIZONTAL);
        switchBox.setGravity(Gravity.CENTER_VERTICAL);
        switchBox.setPadding(0, 8, 0, 8);

        danmuBtn = new Button(this);
        danmuBtn.setTextSize(18);
        danmuBtn.setFocusable(true);
        danmuBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg.enabled = !cfg.enabled;
                apply();
                toast(cfg.enabled ? "弹幕已开启，回到播放页就能看到"
                        : "弹幕已关闭，播放器里不再显示");
            }
        });
        LinearLayout.LayoutParams danmuLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        danmuLp.rightMargin = 28;
        switchBox.addView(danmuBtn, danmuLp);

        danmuState = new TextView(this);
        danmuState.setTextSize(18);
        switchBox.addView(danmuState);
        root.addView(switchBox);

        root.addView(row("所有播放器", "开：Kodi / Plex / Emby / MX / 当贝等全屏播放也挂弹幕", new Runnable() {
            @Override
            public void run() {
                cfg.allPlayers = !cfg.allPlayers;
                apply();
                toast(cfg.allPlayers ? "已开启：任意播放器全屏播放都会挂弹幕" : "已关闭：只认 VidHub");
            }
        }));

        blacklistInput = input("排除应用", "包名，多个用逗号隔开", cfg.blacklist, false);
        root.addView(holder(blacklistInput));

        root.addView(stepper("字号", "textSize", new Runnable() {
            @Override
            public void run() {
                cfg.textSizePx = clamp(cfg.textSizePx - 4, MIN_TEXT, MAX_TEXT);
                apply();
            }
        }, new Runnable() {
            @Override
            public void run() {
                cfg.textSizePx = clamp(cfg.textSizePx + 4, MIN_TEXT, MAX_TEXT);
                apply();
            }
        }));

        root.addView(stepper("速度", "speed", new Runnable() {
            @Override
            public void run() {
                cfg.speedPx = clamp(cfg.speedPx - 30, MIN_SPEED, MAX_SPEED);
                apply();
            }
        }, new Runnable() {
            @Override
            public void run() {
                cfg.speedPx = clamp(cfg.speedPx + 30, MIN_SPEED, MAX_SPEED);
                apply();
            }
        }));

        root.addView(stepper("不透明度", "alpha", new Runnable() {
            @Override
            public void run() {
                cfg.alphaPct = clamp(cfg.alphaPct - 10, MIN_ALPHA, MAX_ALPHA);
                apply();
            }
        }, new Runnable() {
            @Override
            public void run() {
                cfg.alphaPct = clamp(cfg.alphaPct + 10, MIN_ALPHA, MAX_ALPHA);
                apply();
            }
        }));

        root.addView(row("显示区域", "全屏 / 上部 40% 之间切换", new Runnable() {
            @Override
            public void run() {
                cfg.areaFraction = cfg.areaFraction > 0.9f ? 0.4f : 1.0f;
                apply();
            }
        }));

        root.addView(row("菜单键手势", "开：播放器里连按两次菜单键进设置（代价：遥控器长按变卡）", new Runnable() {
            @Override
            public void run() {
                cfg.menuGesture = !cfg.menuGesture;
                apply();
                toast(cfg.menuGesture
                        ? "已开启，播放器里连按两次菜单键进设置"
                        : "已关闭，遥控器长按恢复正常；设置页请用电脑脚本打开");
            }
        }));

        TextView hint = new TextView(this);
        hint.setText("提示：默认「所有播放器」模式，任何一个全屏播放视频的 App 都会挂弹幕，"
                + "片名和进度靠读界面文字猜出来，偶尔认错可以在「排除应用」里填包名排掉。"
                + "只想要 VidHub 就关掉「所有播放器」。"
                + "按键唤出控制栏会自动重新校准进度。接口地址填自建 danmu_api 的根地址，"
                + "开了 token 就填 TOKEN，否则留空。"
                + "若遥控器长按音量键只能一下一下按，说明「菜单键手势」开着——它需要拦截所有按键才能工作，"
                + "关掉即可恢复顺手。");
        hint.setTextSize(15);
        hint.setTextColor(0xFF6B7684);
        hint.setPadding(0, 26, 0, 0);
        root.addView(hint);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        registerProbeReceiver();
        refresh();
        ensureA11yBound();
    }

    private android.content.BroadcastReceiver probeReceiver;

    private void registerProbeReceiver() {
        probeReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, Intent i) {
                String t = i == null ? null : i.getStringExtra("text");
                testResult.setTextColor(0xFF8FD6A8);
                testResult.setText(t == null ? "识别失败：服务没回话" : t);
            }
        };
        IntentFilter pf = new IntentFilter("tv.yaohui.danmu.PROBE_RESULT");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(probeReceiver, pf, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(probeReceiver, pf);
            }
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onDestroy() {
        try {
            if (probeReceiver != null) unregisterReceiver(probeReceiver);
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    /** 支持 adb 直接写配置：--es source api --es api http://... --es token xxx */
    private void applyIntentExtras() {
        Intent it = getIntent();
        if (it == null) return;
        boolean dirty = false;
        String s = it.getStringExtra("source");
        if (s != null) {
            cfg.sourceType = "api".equalsIgnoreCase(s) ? DanmuConfig.SRC_API : DanmuConfig.SRC_MOCK;
            dirty = true;
        }
        String api = it.getStringExtra("api");
        if (api != null) {
            cfg.apiBase = api.trim();
            cfg.sourceType = DanmuConfig.SRC_API;
            dirty = true;
        }
        String tk = it.getStringExtra("token");
        if (tk != null) {
            cfg.apiToken = tk.trim();
            dirty = true;
        }
        if (dirty) cfg.save(this);
    }

    private TextView sectionTitle(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(16);
        tv.setTextColor(0xFF5B6673);
        tv.setPadding(0, 22, 0, 6);
        return tv;
    }

    /** 带输入框的一行：返回 EditText，父容器通过 getParent() 加进布局 */
    private EditText input(String label, String hintText, String value, boolean numeric) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, 8, 0, 8);

        Button tag = new Button(this);
        tag.setText(label);
        tag.setTextSize(18);
        tag.setFocusable(false);
        tag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (apiInput != null) apiInput.requestFocus();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 24;
        box.addView(tag, lp);

        EditText et = new EditText(this);
        et.setText(value == null ? "" : value);
        et.setHint(hintText);
        et.setTextSize(18);
        et.setSingleLine(true);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(0xFF6B7684);
        if (numeric) et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setFocusable(true);
        et.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        box.addView(et, elp);

        // 用 tag 记住外层容器，外部通过 holder(et) 取出来 addView
        et.setTag(box);
        return et;
    }

    private View holder(EditText et) {
        Object t = et.getTag();
        return (t instanceof View) ? (View) t : et;
    }

    /**
     * 「− 字号 56 +」这种步进器。
     * 遥控器焦点默认落在「−」上（大屏用户最常做的是调小），左右键切到「+」。
     */
    private View stepper(String label, String key, final Runnable onMinus, final Runnable onPlus) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, 8, 0, 8);

        Button minus = makeStepButton("−  " + label, onMinus);
        Button plus = makeStepButton("+", onPlus);
        plus.setContentDescription(label + " 加大");

        TextView value = new TextView(this);
        value.setTextSize(18);
        value.setTextColor(0xFFE8EDF2);
        value.setPadding(28, 0, 8, 0);
        valueViews.put(key, value);

        box.addView(minus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(plus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(value);
        return box;
    }

    private Button makeStepButton(String text, final Runnable action) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(18);
        btn.setFocusable(true);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return btn;
    }

    private void setVal(String key, String text) {
        TextView tv = valueViews.get(key);
        if (tv != null) tv.setText(text);
    }

    /**
     * 覆盖安装 / 系统清理会把本应用的无障碍绑定踢掉，服务一死弹幕和菜单手势都失效。
     * 打开设置页时顺手检查一次，缺了就用 root 写回去（保留原有的其它服务，别把 HomeGuard 弄丢）。
     */
    private void ensureA11yBound() {
        try {
            String self = getPackageName() + "/" + DanmuA11yService.class.getName();
            String cur = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur != null && cur.contains(self)) return;
            String val = (cur == null || cur.trim().isEmpty()) ? self : cur.trim() + ":" + self;
            String cmd = "/system/bin/sh -c \"su -c \\\"settings put secure enabled_accessibility_services '"
                    + val + "'\\\"\"";
            Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor();
            toast("弹幕服务刚才掉线了，已重新绑定");
        } catch (Throwable t) {
            // 没有 root 就只能靠用户在系统设置里手动打开
        }
    }

    private View row(String label, String sub, final Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, 8, 0, 8);

        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(18);
        btn.setFocusable(true);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 24;
        box.addView(btn, lp);

        if (sub != null) {
            TextView tv = new TextView(this);
            tv.setText(sub);
            tv.setTextSize(16);
            tv.setTextColor(0xFF9AA5B1);
            box.addView(tv);
        }
        return box;
    }

    private void apply() {
        cfg.save(this);
        sendBroadcast(new Intent("tv.yaohui.danmu.RELOAD"));
        refresh();
    }

    private void refresh() {
        boolean a11y = isA11yOn();
        boolean overlay = Settings.canDrawOverlays(this);
        status.setText("无障碍：" + (a11y ? "已开启" : "未开启")
                + "　|　悬浮窗：" + (overlay ? "已授权" : "未授权"));
        if (danmuBtn != null) {
            // 按钮写"按下去会怎样"，右侧写"现在是怎样"，一眼能分清
            danmuBtn.setText(cfg.enabled ? "关闭弹幕" : "开启弹幕");
        }
        if (danmuState != null) {
            danmuState.setText(cfg.enabled ? "● 正在显示" : "○ 已关闭");
            danmuState.setTextColor(cfg.enabled ? 0xFF8FD6A8 : 0xFF9AA5B1);
        }
        summary.setText("弹幕 " + (cfg.enabled ? "开" : "关")
                + "　范围 " + (cfg.allPlayers ? "所有播放器" : "仅 VidHub")
                + "　字号 " + cfg.textSizePx
                + "　速度 " + cfg.speedPx
                + "　不透明度 " + cfg.alphaPct + "%"
                + "　区域 " + (cfg.areaFraction > 0.9f ? "全屏" : "上部")
                + "　菜单手势 " + (cfg.menuGesture ? "开" : "关"));
        setVal("textSize", cfg.textSizePx + " px");
        setVal("speed", cfg.speedPx + " px/s");
        setVal("alpha", cfg.alphaPct + "%");
        sourceState.setText("当前数据源：" + cfg.sourceLabel());
        String lp = getSharedPreferences("danmu", MODE_PRIVATE).getString("lastProbe", "");
        lastProbeView.setText(lp == null || lp.isEmpty()
                ? "识别记录：还没在任何播放器里识别过"
                : "识别记录：" + lp);
        if (testResult.getText().length() == 0) {
            testResult.setText("「测试连接」会用最近播过的片名试拉一次，先确认服务器在跑");
        }
    }

    private void testSource() {
        String lastTitle = getSharedPreferences("danmu", MODE_PRIVATE).getString("lastTitle", "");
        int lastEp = getSharedPreferences("danmu", MODE_PRIVATE).getInt("lastEp", 0);
        int lastSeason = getSharedPreferences("danmu", MODE_PRIVATE).getInt("lastSeason", 0);
        if (lastTitle == null || lastTitle.trim().isEmpty()) {
            toast("还没在播放器里识别到片名，先去播放器里播一集");
            return;
        }
        cfg.apiBase = apiInput.getText().toString().trim();
        cfg.apiToken = tokenInput.getText().toString().trim();
        cfg.save(this);

        MediaInfo info = new MediaInfo();
        info.title = lastTitle;
        info.episode = lastEp;
        info.season = lastSeason;
        final String target = info.title + (info.episode > 0 ? " 第" + info.episode + "集" : "");
        testResult.setTextColor(0xFF9AA5B1);
        testResult.setText("测试中…　" + target + "　via " + cfg.newSource().name());

        cfg.newSource().load(info, new DanmakuSource.Callback() {
            @Override
            public void onLoaded(List<DanmakuItem> items, String note) {
                final String msg = "成功：" + note;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        testResult.setTextColor(0xFF8FD6A8);
                        testResult.setText(msg);
                        toast(msg);
                    }
                });
            }

            @Override
            public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        testResult.setTextColor(0xFFFF8A80);
                        testResult.setText("失败：" + message);
                        toast("失败：" + message);
                    }
                });
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private boolean isA11yOn() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return flat != null && flat.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}
