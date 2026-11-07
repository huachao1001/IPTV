# AGENTS.md — IPTV 播放体验优化工作记录

> 本文件记录本轮「让播放更流畅、切台更快、不重复播放」的**实测依据**、
> **已完成的改动**和**待办**。后续接手请先读这里，避免重复踩坑。
>
> 所有结论都来自真机 logcat / 分片字节级分析，不是推测。
> 设备：小米电视 `MiTV4-ANSM0` / Android 6.0.1 / Amlogic / 1080p 面板
> 连接方式：`adb connect 192.168.31.63:5555`

---

## 一、诊断结论（已实测确认）

### 1. 「卡顿」的真因不是缓冲不够

抓 3 分钟播放日志，119 次采样**全部** `state=3`（STATE_READY）：

```
state=3 → 119 次    重缓冲 0 次    掉帧 0 次（dropped=0 一条都没有）
```

缓冲量稳定在 4~12 秒之间波动，说明网络完全喂得饱。
**所以"不流畅"要往显示侧找，不是网络侧。**

### 2. 内容是 1080i25，屏幕是 60Hz —— 3:2 下拉抖动

对真实分片做 TS/PES/SPS 字节级解析（脚本见 `build/ts_probe.py`、`build/sps2.py`）：

| 项目 | 实测值 | 说明 |
|------|--------|------|
| 分辨率 | 1920×1080 | |
| `frame_mbs_only` | **0** | **隔行（interlaced）**，1080i |
| profile / level | High / 4.1 | H.264 |
| 帧间隔 | 精确 3600 ticks @90kHz | = 40.00ms = **25.000 fps** |
| 音轨 | stream_type `0x03` | MPEG-1 Layer II（MP2） |
| 分片 | 5 秒/片，窗口 5 片 = 25 秒 | 每片 ~5.25MB |
| 码率需求 | ~1074 KB/s | |

**1080i 25fps 内容输出到 60Hz 逐行面板，每一帧要重复显示 2.4 次**，
即经典的 3:2 pulldown judder——这就是「明明不卡但看着不顺」的来源。
这个抖动在应用层无法根治，属于显示链路问题（见待办）。

### 3. 起播慢的真因：每次换台都阻塞探测 4.5 秒

原 `play()` 流程：换台 → `SourceProber.pick()` 并发探测**全部**线路 →
探测完才 `player.setMediaItem()` + `prepare()`。

```java
PROBE_TOTAL_MS = 4500   // 所有线路的总预算，到点才返回
```

而用户来回切的多半就是那十几个台，线路早已验证过。
**这是"每次切台都要重新加载"的直接原因。**

### 4. 严重 BUG：`LiveVideoRenderer` 换台后不重建基准 → 长时间冻屏

`LiveVideoRenderer` 用「已播最大 PTS」高水位线过滤重复帧，但基准只在
`onEnabled/onDisabled` 时重建。同一播放器换频道时这两个回调**不一定触发**，
于是：

- 上一条流的 PTS 基准（如 63973 秒）留在渲染器里
- 新流的 PTS 从 0 开始 → **每一帧都小于基准 → 全被判成"重复帧"丢弃**
- 画面一直冻到 `FREEZE_LIMIT_US = 60 秒` 兜底才恢复

这同时解释了用户说的**「重复播放」和「换台后黑屏很久」**。

### 5. 电视根本连不上节目单源站

真机实测四个源全部失败：

| 源 | 结果 |
|----|------|
| `cdn.jsdelivr.net` | `SSLHandshakeException: Connection reset by peer` |
| `raw.githubusercontent.com` | `SocketTimeoutException`（443 连不上） |
| `gh-proxy.com` | `CertPathValidatorException: Trust anchor not found` |
| `cdn.jsdelivr.net/best-fan` | `SSLHandshakeException: Connection reset` |

**所以「每日自动刷新」从来没成功过，一直在用 APK 内置的旧 seed。**

---

## 二、已完成的改动

### 新增文件

| 文件 | 作用 |
|------|------|
| `SourceMemory.java` | 按频道名持久化「上次播成功的线路 URL」，换台直接用它起播 |
| `preview_card_bg.xml` | 抽屉预览卡背景 |

### 播放器（`PlaybackTuning.java`）

- **修 `setBufferDurationsMs` 参数顺序**（这是崩溃级 bug）：
  正确顺序是 `(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForRebufferMs)`，
  且必须 `bufferForPlayback <= minBuffer <= maxBuffer`。
  写错会直接 `IllegalArgumentException: minBufferMs cannot be less than bufferForPlaybackMs`。
- 起播门槛 6s → **4s**；重缓冲门槛 5s；min 8s；max 10s → **14s**
- `TARGET_OFFSET_MS` 12s → **10s**（首帧更快、延迟更小）
- 新增 `resetStreamState()` / `setLastRenderer()`：换流时显式清视频渲染器基准

### 视频渲染器（`LiveVideoRenderer.java` + `LiveRenderersFactory.java`）

- 新增 `resetForNewStream()`，并在 `startPlayback()` 里每次换流前调用
- `LiveRenderersFactory` 创建实例后登记到 `PlaybackTuning`

### 换台路径（`MainActivity.play()`）

三级降级，**前两级都不阻塞**：

1. **记忆线路**（`SourceMemory`）→ 立刻起播 + 后台精修
2. **节目单 `response-time` 最快的未标记线路** → 立刻起播 + 后台精修
3. 全被标记过 → 才走阻塞探测

新增 `playToken`：每次 `play()` 自增，异步回调带令牌回来校验，
避免「切走又切回同一个台」时旧结果串台。

### 选源（`SourceProber.java`）

- 新增 `pickBetter(ctx, ch, playing, struggling, cb)` 后台精修，双门槛：
  - 还没出画面（`struggling=true`）→ `REFINE_URGENT_GAIN = 1.25`（换线没成本）
  - 已出画面 → `REFINE_GAIN = 1.6`（避免看着好好的突然黑一下）
- 新增 `betterFallback()`：兜底不再按分数选，改按**码率需求低的优先**
  （分片小、攒得快，起播成功率更高）
- 动态探测预算：候选 ≤ 3 时用 `PROBE_BUDGET_SOLO_MS = 5000`（读数更真实）
- **`PROBE_TOTAL_SOLO_MS = PROBE_BUDGET_SOLO_MS + 2000`**
  —— 这个必须严格大于单路预算，否则探测被总超时统一取消、
  结果全是 null，表现为 `pick=fallback(-1)`、一个台都放不出来。（踩过）

### 起播看门狗（`MainActivity`）

- `STARTUP_TIMEOUT_MS = 7000`：7 秒未出画面 → 标记该线路 `starved` + 换下一条
- `STARTUP_GIVE_UP_ROUNDS = 4`：连试 4 轮仍不行 → 停止重试、提示用户换台
  （节目单里确实有整台都喂不动的，如 CETV-1 三条线路仅 100~440KB/s，需求 1074KB/s）

### 数据与排序

- `assets/seed.m3u8` 换成 `mzky/checklist` 清单：
  **136 频道 / 519 线路**，带 `response-time` 和 `tvg-logo`，CCTV 每台 9~18 条备胎
- `ChannelStore.DEFAULT_SOURCES` 换成 mzky 多镜像（jsDelivr → raw → gh-proxy → best-fan 兜底）
- `Channel.sortBySpeed()`：清晰度降序，同清晰度内 `response-time` 升序
- 默认频道从 index 0 改为优先 `CCTV-1`（index 0 往往是冷门坏台）

### 抽屉焦点预览

- 布局新增 `previewCard`（画面右下角：台标 + 频道名 + 分组 + 线路数）
- `setupFocusPreview()` 挂 `OnGlobalFocusChangeListener`：
  焦点在频道行上移动时实时预览，离开列表/切台/关抽屉即收起
- 正在播放的台不弹预览（用户已经在看了）

---

## 三、待办

### A. 按运营商选线路（用户提出，正在验证）

用户家是**移动宽带**。节目单里 519 条线路分散在几十个小机房 IP 上，
跨网访问（移动→电信/联通）会明显掉速。若能把线路按运营商分类、
优先选同网（移动）的源，理论上能同时改善起播速度和稳定性。

**待做**：实测各 IP 归属与真实吞吐，验证「移动线路确实更快」这个假设，
再决定是否在 `SourceProber` 里加运营商偏好权重。

### B. 1080i25 → 60Hz 面板的抖动（显示侧）

已确认内容隔行、面板 60Hz。可选方向（按侵入性排序）：

1. 让 `PlayerView` 用 `SurfaceView`（当前已是）+ 依赖 Amlogic 硬件 DI
   （`dmesg` 里确认 DI 模块在位，但 Android 侧走的是 GLES 合成，可能没吃到硬件去隔行）
2. 尝试切显示模式到 1080p50/1080p25（`/sys/class/display/mode`，
   需 root，本机 `su` 不存在，普通 shell 无权限写）
3. 应用层无解，需要系统/内核侧配合

### C. 节目单刷新在电视上不可用

电视连不上 GitHub/jsDelivr/gh-proxy（见诊断 5）。
当前靠内置 seed 兜底，可用但不会自动更新。

**待做**：找一个电视真能连上的镜像（国内 OSS / Gitee / 自建），
否则「每日自动刷新」始终是死代码。

### D. 验证

`build/v10.log` 那次真机验证被中断，**新版快路径 + 后台精修尚未完整回归**。
需要在设备上确认：

- [ ] 首次进入 CCTV-1 能出画面
- [ ] 第二次切回同一台走记忆线路、明显更快
- [ ] 换台后不再冻屏（`resetForNewStream` 生效）
- [ ] 坏台（CETV-1）4 轮后放弃并提示
- [ ] 抽屉内焦点移动能看到预览卡

---

## 四、调试工具

```powershell
# 编译（JDK 路径容易写错，注意是 Software\AndroidStudio\jbr）
$env:JAVA_HOME="D:\Software\AndroidStudio\jbr"
.\gradlew.bat :app:assembleDebug

# 安装 + 清数据 + 抓日志
$adb="D:\Software\Android\sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell pm clear com.iptv.live
& $adb logcat -c
& $adb shell am start -n com.iptv.live/.MainActivity
& $adb logcat -d -v time > build\log.txt
```

关键日志 TAG：

| TAG | 内容 |
|-----|------|
| `IPTVPROBE` | 每路实测速率/需求/得分、最终选源决策、精修收益 |
| `IPTVPLAY` | 2 秒一次播放器状态（state/buffered/pos/audio）+ refine 换线 |
| `IPTVSTALL` | 起播超时、放弃换线 |
| `IPTVLOAD` | 每个分片的字节数与耗时、分片跳号 |
| `IPTVVIDEO` | 重复帧剔除、掉帧 |
| `IPTVAUDIO` | MP2/MP3/AAC 解码器探测结果 |

分片分析脚本（Python，需手动下载分片）：

```powershell
$py="$env:USERPROFILE\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
& $py build\ts_probe.py build\segs\seg0.ts    # 帧率 / PTS 连续性
& $py build\sps2.py     build\segs\seg0.ts    # 分辨率 / 隔行标志 / VUI
```

---

## 五、踩坑记录

1. **`setBufferDurationsMs` 参数顺序** → 直接构造失败崩溃。见上文。
2. **`PROBE_TOTAL_SOLO_MS` 必须 > 单路预算** → 否则所有探测被取消。
3. **PowerShell `Set-Content` 会破坏 UTF-8 中文注释**。
   本项目源码里有大量中文注释，**必须用 `edit` 工具改**，不要用
   `Set-Content`/`Out-File` 整文件覆写。踩过一次，靠 `git checkout --` 救回。
4. **`LocalClip` 类名**：`Channel` 的 `sources` 排序会改变下标，
   `SourceMemory` 存的是 URL 而非下标，避免排序后错位。
5. **电视无 `su`、无 `curl`**：不能改 `/sys/class/display/mode`，
   也不能直接命令行测源，只能靠应用内探测日志。
