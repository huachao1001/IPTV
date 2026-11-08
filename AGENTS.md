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

### 抽屉焦点居中（原「焦点预览卡」已删除）

早期在画面右下角做过一张 `previewCard`（焦点频道的台标 + 频道名 + 分组 +
线路数），由 `setupFocusPreview()` 挂 `OnGlobalFocusChangeListener` 实时刷新。
它和"正在播放"的 `infoBar` 各自一套逻辑、又都锚在右下角，实测两张卡互相穿插
（`build/t2.png`），用户分不清哪个是正在看的台——**这张卡现在整个删掉了**。

保留下来的是同一个监听器的另一半职责：焦点在频道行上移动时把那一行
**滚到垂直正中**（规则 5）。方法因此改名为 `setupFocusCentering()`：

```java
list.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
    if (newFocus != null && isInside(newFocus, list)
            && listManager != null && !smoothScrollPending) {
        int pos = listManager.focusedPosition(newFocus);
        if (pos >= 0) {
            listManager.smoothCenterOn(pos);
        }
    }
});
```

- 台标只有左上角顶栏那一处，见下文「台标只有一处」。
- 右下角只剩虚拟遥控器（仅 debug 包），没有任何信息卡。

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

### 遥控器按键（本轮规则，用户明确定义，勿回退）

**左右两块面板是一个整体菜单**：OK 键同时叫出左侧频道抽屉 + 右侧清晰度面板，
也同时消失。左右键**不再**单独打开某一个面板——它们只负责在菜单内部移动焦点。

| 键 | 菜单未打开 | 菜单打开时 |
|----|-----------|-----------|
| **OK / 确认** | 打开完整菜单（左抽屉 + 右清晰度），焦点落当前频道 | 交给焦点所在项（选台 / 选清晰度 / 切分组） |
| **上 / 下** | 上/下一个频道 | 在菜单内移动焦点（分组栏里移动即**自动切分组**） |
| **左 / 右** | 不打开菜单 | 在左右两块面板之间跳焦点 |
| **BACK** | 退出应用 | 收起整个菜单 |

> 菜单打开时从**频道列表**按左**两次**才进分组栏：第一次交给焦点导航
> 从行内子 View 退到行本身，第二次才跨到分组栏。这是实测结论，
> 写自动化脚本时必须按这个次数走，否则会误判成"左键进不去分组"。

#### 规则原文（用户 2026-11-08 定，后续改动必须逐条对照）

1. **按 OK 键左右侧同时出来**，同时出现或同时消失，作为**一个完整菜单**显示，
   不再分两个不同逻辑；左右键不再单独打开某一个面板。
2. **上下键切换频道**，但**只有左右菜单没出现时**才是切换频道。
3. 菜单（左频道列表 + 右清晰度）出现时，上下左右键**用于在菜单内选择**，
   左菜单可以跳到右侧清晰度选项——两块是一体的。
4. 右侧清晰度菜单**宽度要小**，不需要那么宽。
5. 左侧菜单必须**保证选中的频道行永远可见**，即自动滚动到当前选中项。
6. **定期刷新**：每次打开 App 后台自动刷新频道列表，
   **无效的频道和无效的清晰度线路自动移除**。
7. 底部栏**可以调小**，直接在**右下角跟随左右菜单一起显示**，不再单独展示。

#### 实现要点

- 菜单开关只有一个入口 `openMenu()` / `closeMenu()`，抽屉和清晰度面板必须同生同灭。
  任何"只关一个"的写法都会留下半套 UI（例如没有频道的清晰度面板悬在右边）。
- `inQualityPanel()` / `inDrawer()` 都算"菜单打开"，上下键的分流必须同时判断两者。
- 左右键是对称的焦点搬运：右键把焦点送进清晰度面板，左键把它搬回频道列表。
- 规则 7 的底栏现在是**抽屉自身底部的一行**（`drawerSub`：`分组 · 数量` +
  更新时间），跟抽屉同生同灭，不再有独立的 `bottomArea` / `infoBar`。
  `updateDrawerInfo()` 负责写这一行。
- 抽屉落焦后必须 `scrollCurrentIntoComfort(current)`，保证当前频道行不被裁在视口外
  （规则 5）。`focusDrawerList()` 的 6 次重试就是为了这一点：
  抽屉刚 `setVisibility(VISIBLE)` 时 `RecyclerView` 还没布局，
  一次性 `requestFocus()` 会静默失败（实测焦点停在 `root` 上）。
- 清晰度面板落焦目标是**已 selected 的那一行**，不是按下标猜——
  面板是按 `displayKey()` 去重后的视图，和 `sourceIndex` 不同构。

#### 台标只有一处：左上角顶栏里的 `infoLogo`

**用户原话**："右下角的 logo 有 2 个，你排查下是否有多个逻辑让他出现了，
你把这两个逻辑删除"。

之前右下角确实有**两个** `ImageView` 叠在一起，各自一套显隐逻辑：

| 视图 | 逻辑 | 说明 |
|------|------|------|
| `previewLogo` | `setupFocusPreview()` + `previewFor()` + `renderPreviewCard()` + `hidePreviewCard()` | 焦点移到哪一行就显示那一台的台标（"即将切到的台"） |
| `infoLogo` | `renderInfoBar()` + `showInfoBar()` / `hideInfoBarNow()` + `HIDE_INFO_BAR_MS` 计时器 | 正在播放的台（"现在在看的台"） |

两套逻辑互不知情，用户看到的是一台机器上同时出现"待切台"和"在播台"
两个台标，分不清哪个是真的在播。

现在**只保留一处**：

- 整个 `bottomArea`（含 `previewLogo`、`infoBar`）从 `activity_main.xml` 删除；
- `infoLogo` 移进 `topBar`，作为 `nowName` 前面的第一项
  （`88dp × 44dp`、`layout_marginEnd=16dp`、默认 `gone`）；
- `previewLogo` 及其全部方法、`HIDE_INFO_BAR_MS` / `hideInfoBar` /
  `showInfoBar()` / `hideInfoBarNow()` 全部删除；
- `setupFocusPreview()` 改名为 `setupFocusCentering()`，只保留焦点行居中。

台标的**可见性完全跟随顶栏**，不再有独立的淡出计时器：顶栏由
`showBar()` / `hideBar` 统一管理（`HIDE_BAR_MS = 6000`），
BACK 键收起时判断的是 `topBar.getVisibility()`。

实测（`build/t_logo.py`，1280×720）：全屏只有一个 `ImageView`：

```
topBar     bounds=[0,0][1280,54]
infoLogo   bounds=[20,14][70,39]     ← 左上角，紧贴台名
nowName    bounds=[79,12][163,41]
nowMeta    bounds=[187,17][1144,36]
```

#### 规则 5 的滚动：居中 + 平滑过渡 + 边缘淡出

`scrollCurrentIntoComfort()` 早期实现是无条件
`scrollToPositionWithOffset(pos, 0)`——每次都把选中行硬顶到列表首行。
后果是用户在抽屉里连续上下移动焦点时列表一直抖，本来好好待在视口中间的行
也被拽到最上面，观感很跳。

现在分两条路：

| 场景 | 入口 | 行为 |
|------|------|------|
| 结构性变化（开菜单、切分组、跨多行跳） | `CenteringLayoutManager.centerOn()` | 瞬移，一次性摆到正中 |
| 上下键逐行移动 | `CenteringLayoutManager.smoothCenterOn()` | 摆到正中 + 补一段位移过渡 |

##### 平滑滚动为什么不用现成的两个 API（都试过，都不行）

**1. `smoothScrollToPosition()` / `LinearSmoothScroller`**

内置「够可见就不动」的启发式。实测连按 DOWN，前 4 次按键焦点行只
**单帧跳 92px**，第 5 次才有动画——用户看到的是"有时丝滑、有时硬切"。
而且默认速度是按 `MILLISECONDS_PER_INCH / densityDpi` 算的，
320dpi 下约 `0.31ms/px`，滚一行 93px 只给 **29ms**，25fps 录屏里不足一帧。

**2. `ValueAnimator` + `RecyclerView.scrollBy()` 逐帧驱动**

动画值确实在走，`dy=-204/-111/-18/75` 都算对了，
但 `scrollBy` 会和 RecyclerView 的待布局请求打架——
滚动量被随后的布局按锚点算回去，**画面一帧都不动**。

##### 现在的做法：真实滚动瞬时完成，视觉位移用 `translationY` 补

1. `scrollToPositionWithOffset()` 把目标行立刻摆到正中（一步到位，可靠）；
2. 在 `onLayoutCompleted()` 里用 `contentOffset()` 的前后差值算出
   「内容实际移动了多少」（`delta`，正数=内容往上滚）；
3. 把所有行 `translationY` 设成 `+delta`（看起来还停在旧位置），
   再用 `ValueAnimator` 动画回 0。

平移只影响绘制、不影响布局，因此不会被布局管线吞掉，
**每一次**按键都有同样的动画。

| 参数 | 值 | 说明 |
|------|-----|------|
| `MS_PER_PX` | `1.5f` | 一行约 93px → 140ms ≈ 4 帧，眼睛能明确看到运动 |
| `MIN_ANIM_MS` / `MAX_ANIM_MS` | `120` / `260` | 太短像跳、太长连按积压 |
| `DecelerateInterpolator(1.6f)` | — | 起手快、落点稳 |
| `FADE_BAND_DIVISOR` | `6` | 上下各 1/6 视口做淡出带，中间 2/3 全实 |
| `EDGE_MIN_ALPHA` | `0.30f` | 边缘行最低不透明度 |

##### 四个必须记住的坑

**坑 1：`ValueAnimator` 第一次回调要等下一个 vsync。**
`startOffsetAnim()` 里必须**立刻** `applyOffset(currentOffset)` 一次，
否则本次布局这一帧会先按"终态"画出来（录屏里是一帧 `+94` 的跳变），
下一帧才补上位移，看起来像"先跳一下再倒回来"。

**坑 2：补偿量符号是 `+delta`，不是 `-delta`。**
`delta > 0` 表示内容已经向上滚了；要让这一帧看起来停在旧位置，
绘制位移必须是 `+delta`（`translationY` 正方向朝下）。
写反了的表现是"内容先跳到目标位置，再倒着滑回旧位置"。

**坑 3：位移量不能量目标行。**
`scrollToPositionWithOffset()` 只是挂一个待处理滚动，它要到**下一次布局**
才生效；而 `onLayoutCompleted()` 可能先被一次无关的布局（落焦引起的重排）
调用，那时 `findViewByPosition(target).getTop()` 还是旧值，`delta=0`。
必须用 `contentOffset()`（`首个子 View 的位置 × 平均行高 - top`），
并且 `pendingPosition` **不能**在一次 `delta=0` 的布局后就清掉，
要等 `PENDING_MAX_ATTEMPTS = 4` 次仍为 0 才放弃。

**坑 4：`computeVerticalScrollOffset()` 需要传 `State`。**
`LinearLayoutManager` 上的那个重载在布局回调外拿不到 `State`，
所以自己写了 `contentOffset()`。

##### 连续快速按键不回弹

`smoothCenterOn()` **不停**上一个动画：`pendingOffsetBefore` 记的是
真实布局位置，`currentOffset` 仍在更新，布局回调里用
`currentOffset + delta` 作新起点，位移于是从眼睛看到的当前位置接着走。
实测按键间隔 260ms（短于动画时长）连按 5 次，
`dy` 序列恒为同号递减（`-32 -24 -16 -10 -6 -4 -2`），没有变号回弹。

##### 开合菜单 / 切分组必须清残留平移

`cancelScrollAnim()` 要在这三处调用，否则残留的 `translationY`
会让重新出现的列表整体偏半行（像第一行被裁掉一截）：
`openMenu()`、`closeMenu()`、`applyGroup()`。

##### 验证方法（`uiautomator dump` 采不到 200ms 级动画）

必须 `screenrecord` 录屏 + 逐帧检测**蓝色焦点高亮块**（`#F23D8BFD`）
的 top 坐标。用帧差法会被背景视频干扰，不可靠。

判定标准：瞬移 = 单帧跳满一行（`+92/+94`）；平滑 = 多帧递减
（`-30 -24 -17 -11 -6 -4 -2`）。脚本见 `build/t_track.py`（滚动）、
`build/t_fade.py`（边缘淡出，量各行亮度比）。

##### 模拟器上的测试前提（踩过多次）

- **点分组名只移焦点，不切分组**，必须再按一次 OK。
- 不切到「央视频道」的话默认分组只有 1 个台，按 DOWN 什么都不会发生。
- **列表开头几行没有滚动余量**，居中不了、也就没有滚动动画可看，
  必须先按 5 次把它推到中部再录屏。
- 菜单关闭时按 BACK 会**退出应用**（`onKeyDown` 走 `super`），
  脚本里不能先 BACK 去收底栏。

#### 频道行居中的实现（`CenteringLayoutManager`）

继承 `LinearLayoutManager`，`requestChildRectangleOnScreen()` 返回 `false`
关掉系统那套「滚到可见」，滚动只由本类驱动。原因：默认行为下
焦点走到最下面一行时，RecyclerView 把下一行滚到只露出一条边就停手，
那一行「算可见」又没完整布局，焦点搜索找得到却跳不过去——
连续按 DOWN 到第 5 行就彻底卡死（真机、模拟器都能复现）。

`focusedPosition(view)` 沿父链找焦点所在行，因为焦点实际落在行内的子 View 上。

#### 规则 6：死线路黑名单（`DeadSources`）

节目单里相当一部分线路是死的（机房下线、切片 404、或只有 100~300 KB/s
而 1080 要 1 MB/s）。每次换台重新踩一遍，用户看到的就是"切过去黑屏十几秒
再自己换回来"。

`DeadSources` 把**已被真实播放证伪**的地址落盘，`ChannelStore.load()` 里
`dropDead()` 直接剔除：

| 字段 | 值 |
|------|-----|
| SharedPreferences | `dead_sources` |
| TTL | 3 天（过期的 `isDead()` 顺手清掉，重新给一次机会） |
| 上限 | 3000 条，超了按时间戳淘汰最老的 |

判死证据只有三种，**都来自播放而不是一次速度探测**（探测测的是分片开头的
突发速率，能比真实持续速率高四五倍，用它判死会把好源误杀）：

- 起播看门狗超时 → `startup-timeout`
- 分片跳号 `starved` → `chunk-jump`
- 反复缓冲降档

反向也必须留：`STATE_READY` 真正出画面时调 `DeadSources.revive(url)`，
免得一次临时断网造成的误判永久生效。

**`dropDead()` 的保底**：每条线路都死时**保留第一条**。宁可让用户看到
"该频道暂时无法播放"，也好过频道直接从节目单里蒸发——后者会让分组里
少一个台，用户以为节目单坏了。

#### 规则 6 的补强：菜单里每个选项都要当场可播（`SourceAudit`）

死线路黑名单只解决"播失败过的会被剔掉"。用户更直接的诉求是：
**打开菜单，点哪一档就得能播，不能点了才发现无效。** 这需要预先体检。

`SourceAudit`（`source/` 包）把"这条线路能不能出数据"变成可查询的事实，
四条边界必须记住：

| 边界 | 内容 |
|------|------|
| 只判**连通性** | 取清单 → 取第一个分片出字节。**不判快慢**：能不能喂动 1080 只能靠真实播放判定 |
| 判死**不写** `DeadSources` | 两者证据强度不同（一次速度探测会误杀好源），混用会把好源永久拉黑 |
| 只在**带宽有余量**时跑 | 起播/卡顿期间整个停手，否则和正在播的那路抢带宽 |
| 结论**只在内存** | `OK_TTL_MS = 15min` / `BAD_TTL_MS = 10min`。坏源不能给太短的 TTL：过期就重新入队，太短会让一批死链被无限重测、白吃好源的验证带宽 |

##### 余量判据：按「速率 vs 码率需求」，不是「缓冲厚度」

**这是本轮最关键的一处修正。** 早先判据是 `buffered > 8000ms`，
但很多源的分片窗口本身只有 3~5 秒（实测模拟器 `buffered` 峰值 5.3 秒），
缓冲永远到不了 8 秒 —— **体检一次都不会跑**，
"菜单里每个选项都有活线"这个承诺等于没做（表现为面板永远显示"未知"）。

现在是：

```
实测下载速率 ÷ 这路流的码率需求 ≥ 1.6 倍 → 有余量
                              <  1.3 倍 → 收手
```

- 滞回带 1.3~1.6 防止阈值附近每秒开关一次；
- 码率需求来自 `onLoadCompleted` 的 `MediaLoadData.mediaEndTimeMs - mediaStartTimeMs`
  （**不是** `mediaDurationUs`，`MediaLoadData` 没有这个字段，编译直接过不去）；
- 拿不到 mediaDuration（裸流）才退回看 `AUDIT_MIN_BUFFER_MS = 8000`。

##### 关闭必须宽限 6 秒（`AUDIT_OFF_GRACE_MS`）

分片边界那一两秒里，测速的 4 秒窗口会被排空（读数变 "—"），
**同时**播放器常掉进 `STATE_BUFFERING` —— 这恰恰是"上一个分片播完、
下一个还没到"的正常节奏，不是卡顿。

早先把 `!eligible` 当成硬证据立刻关，实测每 5~6 秒开关一次（每个分片一遍），
探测线程每次读两三秒就被打断，一条都测不完。现在统一宽限 6 秒，
但**前提是"上一次确实有余量"**（`auditOkAt`）；起播那几秒不放行。

##### `ABORTED = -2`：被打断不是结论

`probe()` / `sample()` / `readText()` 因让路提前返回时给 `ABORTED`，
`loop()` 只把它**放回队尾**：

- **不下任何结论**（混成 `-1` 会把好源判死）；
- **也不能从队列拿掉**（早先是 `QUEUED.remove`）——拿掉等于回到"从没测过"，
  而重新入队只发生在开菜单/移动焦点时。弱网下被让路是常态
  （实测 10 条里有 2~3 条 defer），结果是那几条永远显示不出来。

##### 两个队列与调度

| 队列 | 内容 |
|------|------|
| `FRONT` | 用户正看的台（`prioritize`），队首 |
| `BACK` | 当前分组其余线路（`rotate`），轮着测 |

并发 `THREADS = 2`、`MAX_QUEUE = 400`、`ROTATE_BATCH = 24`。

##### 结论怎么落到界面

- 右侧面板：`renderQualityPanel()` 按体检结论挑**每档的可用代表线路**，
  整档全死的档位**不显示**；全台全死才全部显示 + 压暗 + `" ✕"`。
- 频道行胶囊：`ChannelAdapter.bindQualities()` 对死档压暗 + `✕`。
- 选源：`quickestSource()` / `SourceProber.pickBlocking` / `pickBetterBlocking`
  都跳过 `SourceAudit.isBad()` 的线路。
- 播起来就 `markPlaying(url)`（`STATE_READY` 里调）。
- `refreshMenuHealth()` 有 **900ms 防抖**（`HEALTH_REFRESH_MS`）：
  体检每出一条结论就 `removeAllViews()` + `notifyDataSetChanged()`
  会让列表反复重排，滚动和焦点都被"按住不动"，观感是卡一下卡一下。

#### 规则 6：每次启动都刷新

`refreshIfStale()`（只在缓存过期 24h 时才拉）已换成 `refreshInBackground()`：
**每次打开 App 都拉**。原来用户一天内第二次打开完全不刷新，坏掉的线路
永远留在列表里。

- 拉取在后台线程（`iptv-startup-refresh`），不挡起播；
- 失败也调 `reloadKeeping()`——让 `dropDead()` 的黑名单生效，
  即使这次没拉到新数据，"剔除坏线路"这件事也该做。

### 「正在播放」只能按频道名判定，绝不能按下标

**这是本轮最严重的一个语义 bug，两个症状来自同一个根因。**

`current` 早先同时承担两种语义：

1. 「正在播的台在 `channels` 里的下标」；
2. 「抽屉里列表焦点落在第几行」。

两者只在「菜单打开、且正在浏览正在播的那个分组」时才碰巧相等。一旦用户
切到别的分组浏览（这是最常见的操作），它们就分叉了。
更糟的是 `applyGroup()` 里有 `current = Math.max(0, found)` ——
正在播的台不在新分组里时 `found = -1`，被夹成 `0`，于是：

- **症状 A**：列表第一行被标上「正在播放」，而实际在播的是另一个分组里的台。
  用户打开一个不含正在播频道的分组，就看到"第一个永远是正在播放"。
- **症状 B**：降档（`lowerQualityInstead`）、换源（`repickSource`）、
  重开流（`onSilentStall` / `recoverPlayback`）、分片跳号判死（`noteChunk`）、
  实测清晰度落盘（`rememberRealQuality`）、清晰度状态日志（`qualityState`）
  全都作用到 `channels.get(current)` —— 也就是**用户正在浏览的那一行**，
  而不是**真正在播的那一台**。用户看到的是"降档没生效""换了源还是卡"。

修法是彻底拆开，三条不变量：

| 字段 | 唯一语义 |
|------|----------|
| `current` | 列表焦点行下标，**允许 -1**（本分组没有正在播的台） |
| `playingName` | **正在播放的唯一权威**，跨分组稳定 |
| `playingRef` | 播放器真正在用的 `Channel` **实例** |

1. **`ChannelAdapter` 按名字判定**：`setCurrent(int)` 换成 `setPlaying(String)`，
   `onBindViewHolder` 里 `playingName.equals(c.name)` 决定是否显示胶囊和
   `setActivated`。名字对不上就**一条都不标**，这才是实事求是。
2. **播放侧一律 `playingChannel()`**，返回 `playingRef`。不用
   `indexInMaster(playingName)` 重新找对象：节目单每次刷新都会解析出**全新的
   Channel 对象**，线路顺序甚至数量都可能变，`sourceIndex` 会指到另一条 URL 上。
3. **`applyGroup()` 里 `current` 该夹到 0 就夹到 0**（方向键要有落点），
   但**绝不**把那一行标成正在播放。

#### 验证脚本为什么之前一直"验证通过"（假的）

`virtual remote` 的按键坐标**不是固定的**：菜单关闭时它在屏幕中偏右
（`OK=(957,493)`），菜单打开时会横移让开右侧清晰度面板
（`OK=(1162,493)`）。脚本里写死一套坐标，菜单一开所有点击都落到别处，
表现为"按了没反应"或"点到了别的控件"——而脚本只看自己 dump 出来的焦点，
于是误判成通过。

现在统一走 `build/rc.py`：**每次按键前先 dump 一次，用按键文字
（`▲▼◀▶ OK 返回`）查出当前真实坐标再点**。

另外 `正在播放` 胶囊是 `ChannelAdapter.addChip()` 里 `new TextView(ctx)`
动态创建的，**没有 resource-id**。想按 id 过滤会把它全部滤掉，
必须按几何位置归组：文本中心同时落在 `id/row` 矩形与 `id/list` 矩形内。
`id/list` 那层判断是必要的——右下角虚拟遥控器的按键文字会和底部行 y 轴重叠。

### 「正在播放」误标：已实测确认的结论

| 场景 | 结果 |
|------|------|
| 央视频道（在播 CCTV-5） | 标记只有 `CCTV-5` |
| 卫视频道（不含在播台） | **一行都不标** |
| 切回央视频道 | 标记仍是 `CCTV-5` |
| 跨分组浏览一趟再回来 | 标记仍是 `CCTV-5`，与顶栏 `nowName` 完全一致 |
| 菜单关闭时 DOWN×4 | 顶栏 `CCTV-2 → CCTV-3 → CCTV-4 → CCTV-5`，确实换台 |

### 分组归属：投票，不是"第一条说了算"

`PlaylistParser.parse()` 原实现用**第一条**线路的 `group-title` 决定
整个频道的分组。但上游清单里同一频道的各条线路分组写法并不统一
（实测 `build/cache2.m3u8`，256 条线路）：

| 频道 | 分组分布 |
|------|----------|
| `CCTV1` | 央视频道 ×5，**General ×1** |
| `CCTV5` | 央视频道 ×7，体育频道 ×1 |
| `CCTV-13` | 央视频道 ×1，News ×1 |
| `CCTV-14` | 央视频道 ×2，无分组 ×1 |

谁排前面纯属生成顺序。于是 CCTV-1 会因为那单独一条 `General`
被整个搬进一个叫 `General` 的独立分组——分组栏里凭空多出一项，
用户在自己的「央视频道」里找不到 CCTV-1。

现在改成**两遍解析**：

1. 第一遍只收集 `Entry`（key / 显示名 / 原始分组 / logo / 清晰度 / 响应耗时 / URL）；
2. `voteGroups()` 对每个频道的分组**按票数取多数**，然后才创建 `Channel`。

有效分组优先：`ChannelGroups.isPlaceholderGroup()` 认定的占位名
（`General` / `News` / `Other` / `Misc` / `Undefined` / `Default`）
**不算票**，即使票数更多也不采纳。全部线路都是占位名时才回退到原始值，
再交给 `canonical()` 收敛成「其他频道」。

离线按同样规则复算 `cache2.m3u8` 的结果（`build/g_check.py`）：

```
有效线路: 256    频道数: 57
分组分布: 卫视频道 34 / 央视频道 18 / 其他频道 4 / 卡通频道 1
占位分组残留: 无 -> PASS
重复显示名:   无 -> PASS
CCTV-1 group=央视频道 线路=6      CCTV-5 group=央视频道 线路=8
CCTV-9 group=央视频道 线路=7      CCTV-13 group=央视频道 线路=13
```

#### `(576i)` 后缀必须和 `(720p)` 一样剥掉

`cleanName()` 原来只认 `p` 后缀。节目单里有 `CCTV-9 (576i)`，
`normalize()` 之后得到 `CCTV9(576I)`，与正常的 `CCTV9` 是**两个键**，
于是分组里出现两个 CCTV-9（一个干净名、一个带 `(576i)`）。
现在正则同时认 `[pPiI]`，`qualityFromName()` 也一并改了。

### 规则 2 的延伸：分组焦点移动 → 列表自动跟随

用户原话："分类切换时频道列表要跟随自动切换，不需要用户再点 OK 确认"。

`stepGroupFocus()` 原来只 `groupAdapter.setCurrent(target)` 移焦点，
列表纹丝不动，必须再按 OK 才切分组。现在移焦点的同时直接
`applyGroup(target)`：

- 只换列表内容，**不打断正在播放的画面**（`applyGroup` 本身只管列表）；
- **不关闭菜单**——用户是在"翻着看"；
- 焦点**留在分组栏**，不会被抢到频道列表（`requestFocusRobust(row)` 之后
  才 `setCurrent`，顺序不能反）；
- OK 仍然可用，落到频道列表选台，行为不变。

实测（`build/t_final1.py` / `t_play6.py`）：连按 4 次 DOWN，
列表依次变成 央视 18 台 → 卫视 34 台 → 卡通 1 台 → 其他 4 台，
底栏 `drawerSub` 同步更新。

### 菜单关闭后的换台基准：必须相对"正在播放的台"

这是 `current` 语义分离之后的**第二个后遗症**，也是本轮最后一个真 BUG。

用户操作很常见：

1. 正在看 CCTV-3；
2. 按 OK 打开菜单，往下翻到「卫视频道」看两眼（列表跟着切过去了）；
3. 没选台，直接按 BACK 收起菜单；
4. 按一下 DOWN，想换到 **CCTV-4**。

原实现是 `stepChannel(current + 1)`，而 `current` 此刻是「卫视频道」的第一行，
于是换过去的是**北京卫视**——用户眼里就是"按一下换台，跳了个毫不相干的台"。
数字键跳台同理：`channels.get(n-1)` 取的是浏览分组的第 n 个台。

修法是先"把基准找回来"，见 `ensurePlayingGroup()` + `stepFromPlaying()`：

```java
private void ensurePlayingGroup() {
    if (master.isEmpty() || TextUtils.isEmpty(playingName)) return;
    if (indexOfName(playingName) >= 0) return;      // 正在播的台就在当前列表
    int want = groupNames.indexOf(channelGroup(playingName));
    if (want >= 0 && want != currentGroup) applyGroup(want);
}
```

上下键 / CHANNEL_UP / CHANNEL_DOWN / 触摸滑动 / 数字键**全部**先过它，
再按 `indexOfName(playingName)` 当下标基准。

#### 顺序陷阱：`ensurePlayingGroup()` 必须在 `channels.isEmpty()` 判断之前

第一版写成：

```java
if (master.isEmpty() || channels.isEmpty()) return;   // ← 错
ensurePlayingGroup();
```

后果是**在空收藏分组里收起菜单后，上下键彻底失效**：收藏是空的，
`channels` 为空直接 return，分组永远切不回去，之后每次按键都没反应。
用户感受就是"按上下键没动静，必须再开一次菜单才能恢复"。

正确顺序是先切回正在播的那一组，再判断列表空不空（实测 `build/t_base2.py`
场景 C 就是这条回归）。

| 场景 | 期望 | 实测 |
|------|------|------|
| 播 CCTV-3，浏览卫视后收起，按 DOWN | `CCTV-4`（同分组下一台） | ✅ |
| 播 CCTV-4，浏览卡通频道后收起，按数字 `2` | `CCTV-2` | ✅ |
| 播 CCTV-2，进空收藏分组后收起，按 DOWN | `CCTV-3`（不崩、不失效） | ✅ |
| 不浏览分组时 DOWN → UP | 相邻切换且能回退 | ✅ |

验证脚本：`build/t_base2.py`（A/B/C/D 四段全 PASS）。

### 清晰度面板为什么移到右侧

原来清晰度是横排在底部信息条里的胶囊，问题有三个：

1. 线路一多就得横向滚动（CCTV-1 有十几个备胎），遥控器不好找；
2. 底栏同时承担「我在看什么」和「换个清晰度」两件事，语义混在一起；
3. OK 键要负责呼底栏 + 落焦胶囊，上下键又是换台，按键语义绕。

现在右侧面板：贴右缘竖排，每档一行，宽度只占屏幕 16%（够放 `1080P` 四个字），
样式与左侧抽屉同源（`quality_row_bg.xml` / `quality_text.xml` 对齐
`group_bg.xml` / `group_text.xml`：10dp 圆角、焦点实心蓝、选中淡蓝 + 描边）。
档位少时 `wrap_content` 紧贴内容，档位多时 `ScrollView` 限高到屏幕 72% 自己滚。

### 缓冲圈为什么一直闪（本轮定位，两条独立病根）

用户原话："缓冲旋转进度一直闪，要自动检测，不能一直闪；如果是缓存时间短导致的，
要自动调整参数"。查下来是**两个独立的 bug 叠在一起**，都必须修。

#### 病根一：`shouldContinueLoading` 照抄了点播公式

`DefaultLoadControl` 的判据是 `buffered < min || (buffered < max && speed > 1)`。
1 倍速下**攒到 `min` 就停手**——对点播没问题（数据一直在那儿），
对直播是陷阱：停手时手里的余量正好是最薄的那一档，下个分片稍慢就播空、
进一次 BUFFERING，再攒到 min 再播空。

真机实测序列（`build/` 日志）：

```
16:53:42  buffered=9769
16:53:44  buffered=7756
16:53:48  buffered=3761
16:53:50  buffered=1751
16:53:52  buffered=102   ← 播空，进 BUFFERING
16:53:58  buffered=9683  ← 重开后排到直播边缘，跳到 9.7 秒
```

**周期 16 秒**，和「5 秒分片 × 3」完全对得上。

改成"一直吃到 `max` 为止"：`return bufferedMs < maxBufferMs();`
直播的数据量天然有上限（到直播边缘就没有了），不会像点播那样把整部片子拖下来。
实测缓冲深度上限从 9.7 秒涨到 **14.7 秒**，播空次数显著下降。

#### 病根二：`max` 被写成大于 `offset`（攒不满的墙）

直播能缓冲多深，**完全由"播放位置落后直播多少"决定**——数据到直播边缘就没了。
第一版档位表把 `max` 写成 14000 而 `offset` 只有 10000，
等于把上限设在够不着的墙上，播放器会一直请求、永远攒不满。

档位表现在刻意**去掉了「最小缓冲」这一列**（改成一吃到底后它不参与任何判断，
留一个不生效的列只会误导后人），只保留 `{gate, rebuf, max, offset}`：

| 档 | gate | rebuf | max | offset |
|----|------|-------|-----|--------|
| 0 | 4500 | 5500 | 12000 | 14000 |
| 1 | 5000 | 6000 | 14000 | 16000 |
| 2 | 5500 | 6500 | 15000 | 17000 |
| 3 | 6000 | 7000 | 15500 | 18000 |
| 4 | 7000 | 8000 | 16000 | 18000 |

**三条硬约束，改数字前必须逐行核对**：`max < offset`、`offset` 明显小于
直播窗口（实测 5 片 × 5 秒 = 25 秒，上限取 18 秒留 7 秒富余）、`gate <= max`。

第 0 档 `offset` 从 10 秒放宽到 14 秒：分片 5 秒一个，只落后 10 秒时手里
最多攥住一片多一点；14 秒能稳稳装下两个整片。**起播速度不受影响**——
`shouldStartPlayback` 只看起播门槛（4.5 秒），不看 offset。

#### 病根三：`onStreamChanged()` 把卡顿计数也清了

第一次跑出来每次都是 `rebuffer hits=1/2`，档位一次都升不上去。
原因是看门狗重开同一条线路会走 `resetStreamState()` → `onStreamChanged()`，
那里把 `hits` 一起清了。但**同一条地址被重开本身就是"喂不动"的证据**，
当成全新一轮等于永远攒不到 `ESCALATE_HITS`。

现在 `onStreamChanged()` **只清 `stableSince`，绝不碰 `hits`**。
档位是「这台机器 + 这条网络」的结论，不是某个频道的结论，跨台带过来才对。

#### 升档必须有三道闸（否则 30 秒升到满档）

修好上面三条后立刻暴露新问题：**模拟器坏网络下 30 秒内连升 4 档**，
延迟被推到 18 秒、起播门槛涨到 7 秒。三道闸缺一不可：

| 闸 | 常量 | 作用 |
|----|------|------|
| 最短卡顿时长 | `MIN_FLASH_MS = 1000` | 分片边界几十毫秒的空档在坏网络上每秒都有，全算进来会把档位瞬间推满 |
| 同一次抖动去重 | `HIT_DEDUPE_MS = 15000` | 一次卡顿会多次进出 BUFFERING，不去重会按 2~3 倍计 |
| 升档冷却 | `RAISE_COOLDOWN_MS = 120000` | 加厚一层要观察一段时间才知道够不够，连着升毫无信息量 |

**卡顿时长从 STATE_READY 里算**：进入 BUFFERING 时记 `rebufferStart`，
回到 READY 时算 `now - rebufferStart` 传给 `onRebuffer(stalledMs)`。
顺手把满档告警限频到 30 秒一次（`MAX_LOG_INTERVAL_MS`），否则日志被淹。

#### 缓冲圈的显示：短暂缓冲一个像素都不画

`BufferingUi`（`ui/` 包）专门管这件事，规则三条：

1. **起播立刻显示**——屏幕是黑的，不给反馈用户以为按了没反应；
2. **出过画面之后进缓冲，先等 `SHOW_DELAY_MS = 1200`**。
   正常分片边界的空档在 400 毫秒以内，追直播的微调也在 1 秒以内，
   真的"喂不动"必然是数秒级。1.2 秒把所有正常抖动挡在门外；
3. **进度分母跟着档位走**（`governor.playGateMs()` / `rebufferGateMs()`），
   封顶 98%——真攒够了播放器会立刻转 READY、转圈直接消失；
   显示 100% 却还停在缓冲，用户看到的是一个静止的满环，反而像卡死。

日志 `IPTVBUBBLE` 三行就是全部证据：

```
show first-play          ← 起播，立刻转圈
suppressed short bubble   ← 短暂缓冲被延迟窗口挡掉，压根没画（不再闪的直接证据）
show after 1200ms         ← 真卡住了才显示
```

`reset()` / `showNow()` / `hide()` 三个入口分别对应：换台、用户手选清晰度
（明确要求等待）、播放失败或放弃。

### 预取：按 OK / 焦点移动就把相邻频道抓进缓存

用户原话："要提前缓存，比如用户按 OK，就要预判用户可能上下切换，
提前预判做缓存"。`PlaybackCache`（`play/` 包）做两件事：

#### 1. 磁盘缓存，消除重复下载

`SimpleCache` + `LeastRecentlyUsedCacheEvictor(256MB)`，目录 `cacheDir/media`。
换台来回切、播放器抖动重开同一条线路时，分片直接从磁盘读。

**整个进程只能建一个 `SimpleCache`**：同一目录被打开两次会直接抛
`IllegalStateException: Another SimpleCache instance uses the folder`。
所以做成静态单例。初始化失败（上次进程被杀在写入中间）时删掉重建一次，
仍失败就退化为无缓存直连——**缓存坏了绝不能连累播放**。

接入点在 `PlaybackTuning.create()`：`setMediaSourceFactory(new
DefaultMediaSourceFactory(PlaybackCache.factory(ctx)))`。
`DefaultHttpDataSource` 那套超时/UA 参数搬进了 `PlaybackCache.factory()`，
**不要再在 `PlaybackTuning` 里另建一份**，两处各写一套迟早不一致。

#### 2. 预取"起播那几秒"，不是整条流

直播流是无限的，抓整条会占满带宽和磁盘，反而把正在播的那一路拖卡。
只取**播放列表 + 前 `PREFETCH_SEGMENTS = 3` 个分片**。

调用时机就是用户意图最明显的两个时刻：

| 时机 | 位置 | 候选顺序 |
|------|------|----------|
| 按 OK 打开菜单（几乎一定是要换台） | `openMenu()` | 焦点行 → ±1 → ±2 |
| 列表里上下移动焦点 | `moveChannelFocusTo()` | 同上 |

每个台只取它当前最可能用的那条线路（`quickestSource()`），
不去预取一堆确定不会用的备胎。

**预取必须能立刻取消**（`cancelPrefetch()`）。用户浏览得快时候选一直在变，
不取消的话几十个请求同时排队、抢占当前播放带宽，正好造成"越预取越卡"。
取消点：`closeMenu()`（放弃换台）、`play()`（这一路真要播了）、`onDestroy()`。

预取并发固定 2 线程（`PREFETCH_THREADS`），最多同时 3 路（`PREFETCH_MAX`）。

日志：`IPTVCACHE cache ready dir=... limit=256MB`、
`IPTVCACHE prefetch ok segs=3 <url>`、`IPTVCACHE prefetch skip <url> -> <异常>`。

#### 坑：`android.net.Uri` 没有 `resolve`

补相对地址只能自己按最后一个 `/` 算，顺便处理 `//host/path` 和 `?query`。

#### 坑：`CacheDataSource.close()` 抛 IOException

`finally` 里裸调 `source.close()` 编译不过，必须包 try/catch。

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
- [ ] 菜单里每档都能出画面（体检结论在真机网络下的准确率）

#### 七条菜单规则：模拟器已回归（2026-11-08）

| 规则 | 结论 | 证据 |
|------|------|------|
| 1 OK 同开同关 | ✅ | `build/x2.png` 左右面板 + 右下底栏同时出现；`build/x4.png` BACK 后全部消失 |
| 2 菜单外上下键换台 | ✅ | `build/u1.png` 菜单关闭时 DOWN 只换台，不弹菜单 |
| 3 菜单内四向导航 | ✅ | `build/x3.png` 右键把焦点送进 576P 档（实心蓝） |
| 4 右侧面板窄化 | ✅ | 16% 宽，`1080P`/`576P` 完整不裁 |
| 5 选中行自动可见 | ✅ | `build/v1.png` 连按 6 次 DOWN，CCTV-11 完整露出 |
| 6 启动刷新 + 剔死源 | ✅ | 日志有 `auto-refresh ok channels=58`、`drop-dead`、`dead_sources.xml` 落盘 |
| 7 底栏缩小跟随菜单 | ✅ | 底栏贴右下角 `wrap_content`；菜单关后 `build/x4.png` 不再常驻 |

#### release 包（虚拟遥控器隐藏）

`assembleRelease` 通过并装机验证（`build/x1.png` → `build/x4.png`）：

- [x] 画面里没有虚拟遥控器
- [x] 设置菜单里没有"虚拟遥控"这一项（`VirtualRemote.attach()` 在非
      `FLAG_DEBUGGABLE` 包直接 return，`available()` 恒为 false）
- [x] 菜单开合、左右键焦点搬运、BACK 收起全部正常

> 剩余未验证项都是**真机专属**（记忆线路起播速度、冻屏、坏台放弃），
> 模拟器无 MP2 解码器（有画面无声音），且远程源只能连 `gh-proxy.com`，
> 分组名是英文 `General` 而非内置 seed 的中文分组。

#### 本轮（菜单每档都要有效 / 点当前台直接收菜单 / 图标）

`build/t_audit.py` 四段全 PASS（2026-11-08 模拟器）：

| 项 | 结论 | 证据 |
|----|------|------|
| 再次选中在播的台 → 只收菜单 | ✅ | 日志 `IPTVPLAY select-current ch=CCTV-6, just closing menu`；播放次数 `1 -> 1`（没有重新起播） |
| 面板里每档都有活线 | ✅ | CCTV-6 的 720P 整档全死 → 面板只剩 `['1080P','576P','自动']` |
| 体检在余量时真跑 | ✅ | 20 秒内 25 条结论；`headroom on speed=41.9 MB/s need=317 KB/s` |
| 菜单开合 / 左右面板回归 | ✅ | BACK 收起 + OK 打开 + `drawer`/`qualityPanel` 都在 |

回归：`t_base2.py`(A/B/C/D)、`t_final1.py`(规则 2 + 一致性 + 跨分组)、
`t_qual.py`、`t_prefetch.py`、`t_logo.py`(全屏只 1 个 `ImageView`) 全 PASS。

##### 「点当前台直接收菜单」为什么要单独做

用户在菜单里再次选中**正在播的那个台**，程序本来会走完整 `play()`：
清空播放器、重新探测线路、重新缓冲。用户看到的是"我就点了一下，
怎么黑屏重来了"。现在 `select(int)` 一开始就比对名字：

```java
if (picked.name.equals(playingName)) {
    closeMenu();   // 只收菜单，什么都不重开
    return;
}
```

判据必须是**名字**而不是下标：`current` 只是列表焦点行，
浏览过别的分组之后它和"正在播的台"根本不是一回事（见上文语义拆解）。

##### 图标：Android 6 用不到矢量图（重要）

电视是 **Android 6.0.1**，**不支持 adaptive icon**（需 8.0+）。
`mipmap-anydpi-v26/ic_launcher.xml` 里那份矢量前景**从来没有生效过**，
真正用的是 `mipmap-{mdpi..xxxhdpi}/ic_launcher.png`。
所以"改了矢量图但桌面没变"不是缓存问题，是**改错了文件**。

现在图标由 `build/make_icons.py` 生成（Pillow，**4 倍超采样 + LANCZOS** 下采样，
`FILL_RATIO = 0.80`），重跑即可重绘全部 10 个 PNG
（`ic_launcher{,_round}.png` × 5 档）。波形必须**分段画折线 + 每个采样点补圆点**，
不能用 `joint="curve"`（会画出放射状毛刺）。

验证方式只有一个：**桌面截图**（`build/_home_icon2.png`）。
装完 APK 不重绘桌面缓存也正常，多看一次即可。

##### 测试脚本的两个致命陷阱（都踩过）

1. **不能读顶栏 `nowName` 判"在播哪个台"**。顶栏 `HIDE_BAR_MS = 6000`，
   而 `uiautomator dump` 要等主线程空闲（换台时正跑阻塞探测好几秒），
   等它返回顶栏已自动隐藏 —— 读到空串，把成功误判成失败。
   一律读 `IPTVCH play ch=` 日志（`play()` 里打，不受界面计时影响）。
2. **logcat 环形缓冲会被体检日志冲爆**。默认 `main` 只有 256KB，
   而 `IPTVAUDIT` 一次会话有几百行，很快把 `IPTVCH` 挤掉，读到的也是空串。
   脚本开头必须 `adb logcat -G 8M`（`rc.ensure_log_room()`）。

另外 `names()` 只返回**屏幕上可见的行**，列表按规则 5 会把选中行滚到正中，
所以"分组第 1 个台"未必是可见第一行。要读分组顺序必须先按 UP 退到顶。

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
| `IPTVAUDIT` | 线路体检：`headroom on/off speed=.. need=..`、`ok/bad/defer <url>`、`prioritize`、`rotate` |
| `IPTVCH` | **当前在播哪个台**（`play ch=<name> idx=<n>`）。自动化脚本判"换台成功"只认这一行 |
| `IPTVPROBE` | 每路实测速率/需求/得分、最终选源决策、精修收益 |
| `IPTVPLAY` | 2 秒一次播放器状态（state/buffered/pos/audio）+ refine 换线 + `select-current` |
| `IPTVSTALL` | 起播超时、放弃换线 |
| `IPTVQUAL` | 清晰度上限 / 手动选档 / 降档原因（`why=`） |
| `IPTVLOAD` | 每个分片的字节数、耗时、**实测速率与码率需求**、分片跳号 |
| `IPTVBUBBLE` | 缓冲圈显隐决策（`show first-play` / `suppressed short bubble` / `show after 1200ms`） |
| `IPTVCACHE` | 磁盘缓存与预取（`cache ready` / `prefetch ok segs=`） |
| `IPTVVERIFY` | 旧线路复验（`old-line ok= dead= total=`） |
| `IPTVVIDEO` | 重复帧剔除、掉帧 |
| `IPTVAUDIO` | MP2/MP3/AAC 解码器探测结果 |

**抓日志前先给缓冲扩容**：`& $adb logcat -G 8M`。
默认 `main` 只有 256KB，体检日志几百行就能把 `IPTVCH` 挤掉（实测踩过）。
脚本里走 `rc.ensure_log_room()`。

**判"在播哪个台"绝不要读顶栏 `nowName`**：`HIDE_BAR_MS = 6000`，
而 `uiautomator dump` 要等主线程空闲（换台时正卡在阻塞探测上），
等它返回顶栏已经隐藏，读到空串会把成功误判成失败。读 `IPTVCH`。

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
6. **`MediaLoadData` 没有 `mediaDurationUs`**。想拿分片覆盖的媒体时长，
   只能用 `mediaEndTimeMs - mediaStartTimeMs`（`Us` 那个字段在别的类里）。
7. **`java -cp *.aar` 读不到类**：aar 其实是 zip，先改后缀解压再 `javap -classpath classes.jar`。
8. **`am start` 不会重跑 `play()`**：应用已在跑时它只把任务提到前台，
   于是没有新的 `IPTVCH`。脚本要读在播台必须先 `am force-stop` 再 `am start`。
9. **图标改矢量图在 Android 6 上无效**：见上文「图标：Android 6 用不到矢量图」。
