# IPTV 直播（Android TV）

基于仓库里的 `cn_all_status.m3u8` 播放列表构建的原生电视直播 App。

## 功能

- **遥控器 + 触摸双操作**：上下键 / CH+ / CH- / 数字键换台，OK 打开双栏抽屉，触摸可点按、上下滑换台、左右滑切画面比例
- **分组抽屉**：左侧分组导航（收藏、央视频道、卫视频道、影视频道、卡通频道、体育频道…），右侧频道列表；分组顺序由 `ChannelGroups.ORDER` 决定
- **清晰度标签**：只向用户展示清晰度（1080P/720P/576P…），不暴露"源"的概念；清晰度取自 `tvg-name` 标记，播出后用 `onVideoSizeChanged` 实测校正并写入 `QualityMemory`
- **底部信息条**：播放页贴边满幅，logo + 频道名 + 清晰度胶囊，点胶囊即切到该路；遥控器按 ↓ 聚焦胶囊，↑ 收回
- **收藏**：频道行长按加入/取消收藏，收藏作为第一个分组
- **手动选线路**：长按频道行可指定具体线路，该选择不会被自动选线覆盖
- **不自动换线**：播放失败或卡顿只提示，换不换由用户决定
- **离线台标**：119 个台标随包内置（`assets/logos`），已做视觉尺寸归一化
- **每日自动刷新**：AlarmManager 每天 04:10 拉取节目单，多镜像兜底；开机/升级后自动重注册
- **离线兜底**：安装包内置一份节目单，无网络也能出频道

## 界面尺寸适配

电视的 density 上报极不可靠：同样是 1920×1080 的两台机器，可能一台报 240dpi（1280×720dp），
另一台报 320dpi（960×540dp）。按 `dp` 布局会导致两台观感差 33%。

因此本项目**完全绕开 dp/sp**：

- 布局 XML 里的 `dp`/`sp` 只是 1080p 基准占位
- 真实尺寸由 `UiScale` 按**屏幕物理像素**换算：
  - `UiScale.pxW(x)` — 按屏宽比例，横向间距/宽度
  - `UiScale.pxH(x)` — 按屏高比例，字号/行高/纵向间距
  - `UiScale.px(x)` — 短边对齐，宽高都要缩放时用
  - `UiScale.pctW/H(p)` — 屏幕百分比，抽屉和底栏用
- 抽屉宽度取屏宽 52%，两栏按权重 0.30 / 0.70 分
- 字号统一走 `UiScale.text(view, at1080)`

4K 电视上所有尺寸自动放大，无需额外资源目录。

## 台标归一化

原始台标长宽比和留白差异极大（可见内容填充率 23%~100%），直接 `fitCenter` 撑满会让
有的顶格、有的缩成一小条。`tools/normalize_logos.py` 按**可见内容面积**归一化：

```powershell
cd tv-app
python tools/normalize_logos.py            # 处理并备份原图到 tools/logo_originals
python tools/normalize_logos.py --restore  # 从备份还原
```

处理后的台标统一为 480×240 画布、内容面积占比约 42%，列表里按 2:1 取框即视觉一致。

## 数据源

按顺序尝试，前一个失败自动换下一个：

1. `https://cdn.jsdelivr.net/gh/best-fan/iptv-sources@master/cn_all_status.m3u8`
2. `https://gh.bravetimes.cn/https://raw.githubusercontent.com/best-fan/iptv-sources/master/cn_all_status.m3u8`
3. `https://raw.githubusercontent.com/best-fan/iptv-sources/master/cn_all_status.m3u8`

节目单里有大量死链，换台前由 `SourceProber` 并发探测，取最先响应的一路。

## 预览模式

`MainActivity.PREVIEW_ONLY`（当前为 `true`）会彻底跳过播放器创建、选源探测和后台刷新，
画面位置只显示静态图，用于专心调界面。恢复直播改成 `false` 即可。

## 编译

```powershell
cd tv-app
$env:JAVA_HOME="D:\AndroidStudio\jbr"
.\gradlew.bat :app:assembleDebug
```

产物：`tv-app/app/build/outputs/apk/debug/app-debug.apk`

## 安装与调试

```powershell
adb connect 10.161.27.233:5555
adb -s 10.161.27.233:5555 install -r tv-app\app\build\outputs\apk\debug\app-debug.apk
adb -s 10.161.27.233:5555 shell am start -n com.iptv.live/.MainActivity
```

调试可直接指定节目单和频道：

```powershell
adb shell am start -n com.iptv.live/.MainActivity -e playlist <m3u8-url> -e channel 3
```

## 按键

| 按键 | 作用 |
|------|------|
| ↑ / ↓ | 上一个 / 下一个频道；底部栏可见时 ↓ 聚焦清晰度、↑ 收回 |
| CH+ / CH- | 同上 |
| 数字键 | 直接跳台（1.5 秒内可连续输入） |
| OK / Enter | 打开频道列表 |
| ← / → | 上一个 / 下一个频道（焦点在底部栏时横向切换胶囊） |
| Menu / Z | 循环切换画面比例：自适应 → 拉伸 → 裁剪 |
| Back | 关闭列表 / 退出 |
