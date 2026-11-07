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

### 优先高清 + 卡顿降档 + 不来回切（本轮核心）

策略是「**先试最高清，确认喂不动再降一档，降下去就锁住**」。

#### 为什么需要「锁住」

实测 1080 线路 `222.223.41.27:8888/hls/1/index.m3u8`：

| 项目 | 实测值 |
|------|--------|
| 分辨率 | 1920×1080（`[1080]` 标签**可信**） |
| 分片 | 6.2~6.5 MB / 6.3 秒 |
| 码率需求 | **≈ 1.0 MB/s** |
| 电视实际持续速率 | **0.42 ~ 0.54 MB/s**（首片突发可达 2.02 MB/s） |

起播能出画面，播十几秒就缓冲耗尽。而 `SourceProber.pickBetter()` 每隔几秒
在后台探一遍，**探的是分片开头的突发速率**，1080 偶尔被测出 2 MB/s
就会被判"最优"选中 → 刚降下去又跳回 1080 → 再卡 → 再降。
用户看到的就是画面反复重建。所以上限必须落盘、且所有选源路径共用。

#### 三条不变量

1. **档位只认节目单标记**：`Channel.Source.quality` 只由 `tvg-name` 的
   `[1080]`/`[720]`/`[576]` 或频道名 `(720p)` 决定，**永不被实测覆盖**。
   实测值写入独立的 `measured` 字段，只用于界面显示与同档内排序。
   > 之前实测会把 `quality` 改成 576，档位因此漂移，`QualityCap` 判据失效。
   > URL 里猜出来的 `hd`/`1080` 也只写 `measured`，不参与档位。
2. **一次只降一档**：`QualityCap.nextLower()` 取「严格小于当前的最高档」。
   1080 喂不动应降到 720，而不是直接跌到 576。
3. **降档只信真实播放证据**，不信一次速度探测：
   | 触发点 | 日志 `why=` | 形态 |
   |--------|-------------|------|
   | 起播看门狗超时 | `startup-timeout` | 7 秒没出画面 |
   | 出画面后反复缓冲 | `rebuffer-heavy` | 90 秒内 ≥3 次进 `state=2` |
   | 分片跳号 | `chunk-jump` | 连续 2 次断层 |
   | 静默卡死 | `silent-stall` | 第 2 轮重开后仍不动 |

#### 防来回切的四道闸

| 闸 | 位置 | 作用 |
|----|------|------|
| `QualityCap`（落盘） | `quality_cap.xml` | 频道级清晰度上限，`CAP_TTL_MS = 3 小时` |
| 选源过滤 | `SourceProber.pickBlocking` / `pickBetterBlocking` | 超上限的线路**不探测**，不可能被选中 |
| 起播过滤 | `quickestSource()` | 上限内取最高档，同档取 `response-time` 最快 |
| 记忆线路过滤 | `play()` 快路径一 | `SourceMemory` 的 URL 也要过上限，否则切台回来又撞 1080 |

另外两条：
- `qualityLoweredThisSession`：同一次播放**只降一次**，避免连续黑屏；
- `QUALITY_LOWER_COOLDOWN_MS = 30 秒`：新线路起播的缓冲不算卡顿，
  否则会一降到底（越看越糊）。

精修（`pickBetterBlocking`）额外加 `floor`：**只许同档或更高**。
一次几秒的探测不足以判定"喂不动"，降档一律交给上面四条真实证据。

#### 用户手动选清晰度

`switchQuality()` 会 `QualityCap.clear()`、重置 `qualityLoweredThisSession`
**和** `lastQualityLowerAt`：用户亲手选了 1080 就照播 1080，但**仍允许**后续
卡顿自动降档（否则就是"点了高清后一直卡着不动"）。

清 `lastQualityLowerAt` 是必须的：上一轮自动降档留下的 30 秒冷却如果不清，
用户刚点完高清，观察期一过就撞上这条冷却，第一次降档会被无声吞掉。

##### 90 秒观察期（`MANUAL_PICK_GRACE_MS`）

用户手动点了某档清晰度之后，`MANUAL_PICK_GRACE_MS = 90 秒` 内**禁止自动降档**
（`lowerQualityInstead()` 直接返回 false，日志 `IPTVQUAL lower skip ... manualGrace=Ns/90s`），
精修也**不做任何静默换线**（日志 `IPTVPLAY refine skip-manual-grace`）。
理由：用户点 1080 说明他想看高清；若两秒后因一次起播缓冲就被降回 720，
他只会觉得"点了没反应"或"跟我对着干"。换线会重建管线、画面黑一下，
刚从菜单里选完就看到黑屏同样是坏体验。

**观察期绑定频道名**（`manualPickChannel` + `inManualGrace(channel)`）。
不能做成全局 90 秒：用户在 A 台点了高清，马上换到 B 台，
B 台的自动降档和精修不该被 A 台的操作一起冻住——B 台可能本来就喂不动。

**唯一的豁免：还没出过画面。** `lowerQualityInstead()` 里若 `hasPlayed == false`
（屏幕只有缓冲圈，没有"被打断的观看"），允许降档，日志
`IPTVQUAL lower grace-waived ... no-picture-yet`。否则用户选中一条死线路时
只能连续 4 轮起播超时（约 40 秒黑屏），最后提示"该频道暂时无法播放"，
比降一档难看得多。

**起播看门狗的手动豁免**：观察期内第一次 `startup-timeout` 先重开**同一条**线路
（`manualGraceStrikes` / `MANUAL_GRACE_STRIKES = 1`，日志
`IPTVSTALL startup-timeout retry-same ... strike=1/1`），第二次才降档。
高码率源起播慢七八秒很常见，一次超时就降档等于"点了 1080 七秒后变 720"。

> `manualGraceStrikes` 在 `play()`、`switchQuality()`、`disarmStartupWatchdog()`
> 三处都要清零。它属于单次播放，不能带到下一次——否则某次重试过之后，
> 后续每次起播超时都会少一次机会。

### 清晰度上限的两个陷阱（已修，勿回退）

**1. `effectiveRank()` 只能认节目单标记，绝不掺实测值。**
`Source.quality` 是档位的唯一依据，实测结果只写 `Source.measured`（仅供显示与同档排序）。
早期版本让 `effectiveRank()` 在无标记时回退到 `measured`，后果是：
一条无标记线路播过一次被测成 576P，档位就从 0 变成 576，
立刻被"降到底档"的上限 `cap=0` 排除掉——**明明被允许的线路自己消失了**。

**2. `nextLower()` 不许退到 0。**
曾经在没有更低标记档时返回 0（含义"只允许无标记线路"），
实测是个陷阱（`build/final_grace.log` 14:58:56）：那批线路清晰度未知，
既可能是真 576，也可能是没标清楚的 1080 坏源。降下去后连续三路
`startup-timeout`、`buffered=0`，而这个上限会落盘保留 3 小时，
**该频道 3 小时内彻底放不出来**。现在没有更低标记档时返回 `Integer.MAX_VALUE`
（"降不下去"），让调用方去换同档线路。

**兜底：放弃判定前先撤销上限。**
起播看门狗在 `startupFailures >= STARTUP_GIVE_UP_ROUNDS` 时，若该频道
还有清晰度上限，先 `QualityCap.clear()` + 清掉所有 `starved` 标记再 `repickSource()`，
日志 `IPTVQUAL give-up releases cap ...`，而不是直接提示"无法播放"。
这样即使上限被历史数据污染（`quality_cap.xml` 会跨启动保留 3 小时），
也能自愈回可播状态。这层兜底和 `nextLower()` 无关：
即使以后降档逻辑再出错，它也能把这个台救回来。

### 数据源更换：mzky → best-fan

旧 seed（`mzky/checklist`）519 条线路**一个清晰度标记都没有**，
`Channel.rank()` 全返回 0，`sortBySpeed()` 退化成纯按 `response-time` 排，
于是永远命中最快的那批 720×576 小机房——**这才是"画面一直是低清"的根因**。

`best-fan/iptv-sources` 的条目带 `[1080]/[720]/[576]` 标记，实测可信。
合并后：**754 线路 / 153 频道 / 303 条带标记**（1080P=173、720P=29、576P=99）。

> 合并脚本 `build/merge_seed.py`：best-fan 优先，旧 seed 补全不重复的线路。
> 注意 `[S]` 后缀（如 `CCTV1[1080][S]`）不会拆台：`qualityOf()` 取第一个括号。

### 遥控器按键（本轮按要求重排）

| 键 | 行为 |
|----|------|
| **上 / 下** | 上/下一个频道 |
| **左 / 右** | 打开左侧抽屉（分组 + 频道列表） |
| **OK / 确认** | 打开底部信息条，并把焦点落到当前清晰度胶囊 |
| **BACK** | 逐层退出：胶囊 → 底栏 → 抽屉 |

- 抽屉打开后焦点落到**当前频道行**，`focusDrawerList()` 带 6 次重试：
  抽屉刚 `setVisibility(VISIBLE)` 时 `RecyclerView` 还没布局，
  一次性 `requestFocus()` 会静默失败（实测焦点停在 `root` 上）。
- OK 键必须同时 `focusQualityPill()`：上下键已改为换台，
  焦点留在画面上就再也选不到清晰度了。

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
