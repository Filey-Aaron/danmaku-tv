# 弹幕伴侣 · 给 Android TV 播放器加弹幕

给 Android TV 上的播放器加一层弹幕。**VidHub 是精确适配的**（读它固定的控件 id），
其它播放器（Kodi / Plex / Emby / MX / 当贝 / 系统播放器…）走通用模式：从无障碍节点树里认片名和进度。
不注入播放器、不 hook、不改播放器的 APK —— 只读界面上已经显示的文字，把弹幕画在自己的一层透明窗口上。

![运行截图](docs/screenshot-main.jpg)

- 播放页正常出弹幕，顶部固定弹幕也支持
- 暂停弹幕一起停，拖动会重建当前窗口（不会把攒下的弹幕一口气倒出来）
- 退出播放页立即隐藏，列表页/详情页不会乱弹
- 设置页藏在「播放页连按两下菜单键」里，桌面不留图标
- 数据源接自己搭的 [danmu_api](https://github.com/huangxd-/danmu_api)，不依赖别人的公共实例

**👉 不会装？两份说明书，挑顺眼的看：**

- **[新手说明书](docs/for-beginners.md)** — 只用遥控器和 U 盘，全程不出现命令行
- **[进阶说明书](docs/tutorial.md)** — 会用电脑的话看这份，含自建弹幕服务器、开机自恢复、参数原理

---

## 快速开始

```bash
# 1. 装
adb install -r danmu-tv-1.1.2.apk

# 2. 给悬浮窗权限
adb shell appops set tv.yaohui.danmu SYSTEM_ALERT_WINDOW allow

# 3. 开无障碍（⚠️ 第三行里那个 HomeGuardService 是这台电视上原有的服务，换成你机器上 `get` 出来的值）
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services \
  'app.orchard.tv/app.orchard.tv.guard.HomeGuardService:tv.yaohui.danmu/tv.yaohui.danmu.DanmuA11yService'
adb shell settings put secure accessibility_enabled 1

# 4. 打开设置页填弹幕接口（桌面没有图标，这是故意的）
adb shell am start -n tv.yaohui.danmu/.SettingsActivity
```

APK 见 [Releases](../../releases)，或直接下仓库里的 `danmu-tv-1.1.2.apk`。

## 为什么不用 Xposed

实测过，走不通：

| 方案 | 结果 |
|---|---|
| 读系统 MediaSession | VidHub 播放视频时根本不注册会话 |
| Xposed hook `libvlc.MediaPlayer.getTime()` | hook 能挂上，进程 0.2 秒后被杀 |
| 只注入模块、一条 hook 都不挂 | **照样被杀** |

结论：VidHub 有反注入检测（进程内出现 Xposed 模块即主动 `exit`，无 FATAL、无 tombstone）。
现存的 `vidhub_cache_fix` 能活，是因为它是纯 native GOT hook，不碰 ART。

所以改成「读界面上已有的文字」这条路：没有注入、没有 hook、不碰播放器进程。

## 它怎么工作

```
播放器界面（只在控制栏可见时）
   ├─ play_item_title_top  → "黑帮领地 第1季 第5集 - 朋友的葬礼"
   ├─ text_left_time       → 02:07
   └─ text_right_time      → 38:25
              ↓  无障碍服务每 300ms 读一次（能读到就读）
        Timeline（锚点 + 推算 + 长窗口倍速估计）
              ↓
        OverlayView（透明窗，TYPE_APPLICATION_OVERLAY）
              ↓
        弹幕按媒体时间滚动；暂停/拖动自动跟随
```

几个关键设计：

- **锚点 + 推算**：控制栏出现时读真实进度重新锚定；控制栏收起后按 `位置 = 锚点 + 经过时间 × 倍速` 推算。
- **不抖的时间轴**：播放器只给到「秒」，所以推算值只要还在合理区间内就不动它，只在连续两轮跑出区间时才重锚；倍速用 ≥6 秒的长窗口估计，避免弹幕速度「呼吸」。
- **暂停判定**：读系统音频状态（`AudioManager.isMusicActive()`），媒体键暂停也能冻住弹幕。
- **渲染走媒体时间**：暂停时弹幕一起停，拖动会重建窗口。
- **性能**：悬浮窗只覆盖顶部弹幕区、开启硬件渲染、字形位图由后台线程提前 2.5 秒预渲染并做 LRU 缓存，无障碍节点/包名/音频查询全部挪到后台线程。
  实测（2014 年 MStar 芯片的小米电视，1080p 视频 + 密集弹幕）：绘制 0.3~0.8ms/帧，54~58fps；
  对照实验（弹幕层什么都不画）也只有 57~60fps，剩余丢帧是这颗芯片合成 1080p 视频 + 透明层的上限。

### 通用播放器模式（1.1 新增）

VidHub 之外的播放器没有固定控件 id，走另一条路 —— 只看节点树里"文本长什么样"：

```
窗口：取当前活动 / 最上层的那个全屏窗口（后台播放器窗口不会抢镜）
节点：整棵树扫一遍（最多 420 个节点）
  ├─ 成对时间 "12:34 / 45:00"  → 当前进度 / 总时长
  ├─ 两个独立的时间文本        → 小的当进度，大的当总时长
  └─ 顶部区域、不像按钮的文本  → 片名
```

判定「在放视频」要求二者之一：**全屏 + 有声音**，或者**读到时长 ≥ 1 分钟的进度条**。
这样能挡掉音乐 App、相册、桌面；电视切到 HDMI 输入源时也会被挡掉（读不到进度，20 秒后自己撤）。

读不到进度时的兜底：从 0 开始按墙钟走（看剧基本都从头放），一旦读到真实进度立刻重锚纠正。

认错了想排掉某个 App：设置页「排除应用」里填包名，多个用逗号隔开。
设置页顶部的「识别记录」会显示刚才在播放器里读到了什么，方便判断为什么没弹幕。

### 断线自动恢复

片名和进度**只在控制栏可见时**才读得到，所以会话被打断（进设置页、切应用、服务重连）后要能自己接上：

| 场景 | 行为 |
|---|---|
| 会话重启 | 用记住的片名/季集立刻恢复，不等控制栏 |
| 同一集再进 | 命中内存缓存，不再请求服务器 |
| 进度 | 断线时位置存盘，回来按墙钟接上；暂停中则停在原位 |
| 完全没有进度可用 | 不乱放弹幕，右下角提示「弹幕待同步 · 按一下遥控器」；有 Root 时自动按一下「上」键召唤控制栏 |
| 拉取失败 | 15 秒后重试，最多两次，带指数退避 |
| 悬浮窗被系统回收 | 1.5 秒巡检 + DetachListener 自动重建 |

另有防抖：连续 3 次（约 4.5 秒）都不在播放器里才真正撤掉弹幕层，避免控制栏弹出/收起瞬间反复重建。
非播放页（VidHub 列表/详情页）靠无障碍事件报的界面类型排除，详情页里的小窗预览播放器也不会误触发。

## 数据源

设置页「弹幕源」区：接口地址 / TOKEN / 切换数据源 / 保存并重载 / 测试连接。

- **内置测试源**：本地随机生成，用于验证渲染与同步（默认）。
- **弹幕API**：对接自建 danmu_api，兼容弹弹play v2 规范。
  流程：`/api/v2/search/episodes?anime=<片名>` → 按集号挑 episodeId → `/api/v2/comment/<id>?format=json&withRelated=true`。

自己搭一个（任意有 Docker 的机器）：

```bash
docker run -d --restart unless-stopped --name danmu-api -p 9321:9321 \
  -v /你的路径/danmu-api/config:/app/config \
  -e TOKEN=87654321 \
  --log-opt max-size=10m --log-opt max-file=3 \
  logvar/danmu-api:latest
```

然后把挂载出来的 `config/.env` 里 `RATE_LIMIT_MAX_REQUESTS` 改成 `0`（默认 3 次/分钟，切集稍快就 429），重启容器。
设置页里接口地址填 `http://<那台机器的IP>:9321`，TOKEN 填上面 `-e TOKEN=` 的值。

## 已知限制

1. **播放进度只在控制栏可见时才读得到**。拿不到进度 5 秒后会用 Root 自动发一个「上」键让控制栏弹一下（不会暂停）；没有 Root 时退回右下角提示，手动按一下遥控器即可。
2. 控制栏隐藏期间靠推算，倍速自校准；整段时间都没有按键时，长时播放可能有秒级漂移。
3. 判断播放/暂停依赖系统音频状态；无音轨的片源可能被误判成暂停。
4. 弹幕轨道从上往下填，条数少时会集中在画面上部。
5. **通用模式是猜的，不是适配**：片名和进度都从界面文字里推，所以
   - 片名是图片 / 只显示文件名很乱的播放器，搜不到弹幕；
   - 时长短于 1 分钟的短片不挂（避免把音乐 App 当播放器）；
   - 播放器如果从头到尾都不显示进度条，会被当成「不是视频」而撤掉（按一下遥控器唤出控制栏即可）。
6. 播放器里**连按两次菜单键**（900ms 内）打开设置页 —— 代价是所有按键要过一遍无障碍服务，遥控器长按会变一下一下按。可以在设置里关掉。
7. 部分电视开机会把第三方无障碍服务从列表里摘掉，导致重启后失效。有 Root 的话用 `tools/95-danmu-a11y.sh` 做开机自恢复（见教程 2.3）。
8. 只在小米电视（Android 9）上验证过：VidHub 走精确适配，系统播放器/HDMI 输入源走通用模式（会被正确排除）。其他机型/播放器请用设置页的「识别记录」自查。

## 构建

不依赖 Gradle，手工链路（JDK 17 + Android SDK build-tools 34）：

```bash
bash build.sh
```

依次跑 javac → d8 → aapt2 compile/link → 塞 classes.dex → zipalign → apksigner。
第一次构建会自动生成 `keys/mod.jks`（密码 `android`，仅用于本地安装，不用于上架）。

工程结构：

```
src/tv/yaohui/danmu/
├── DanmuA11yService.java   核心：盯播放器窗口、读节点、管悬浮层与会话恢复
├── PlayerProbe.java        通用播放器识别（扫节点树猜片名/进度，无固定 id）
├── Timeline.java           位置估计（锚点 + 推算 + 暂停/拖动判定）
├── OverlayView.java        弹幕渲染（轨道、速度、透明度、顶部弹幕、字形预渲染）
├── DanmuApiSource.java     弹弹play v2 接口客户端（缓存 / 负缓存 / 退避重试）
├── DanmakuSource.java      数据源接口
├── MockSource.java         内置测试源
├── TitleParser.java        标题 → 片名/季/集
├── DanmuConfig.java        配置读写
└── SettingsActivity.java   设置页（不注册桌面图标）
tools/95-danmu-a11y.sh      Magisk 开机自恢复无障碍绑定
docs/tutorial.md            使用教程
```

## 隐私

- 只读取 VidHub 播放器界面上的文字（片名、两个时间），不读其他应用、不读屏幕内容、不上传任何数据。
- 唯一的外发请求是「用片名去你自己填的弹幕接口查弹幕」，只发往设置里填的地址。

## 许可

MIT。弹幕内容来自第三方接口，版权归原作者，仅供个人观看使用。
