# 开发上下文与实机基线

检查日期：2026-11-17（Asia/Singapore）。用于接续开发；版本、CI 和设备状态会变化，
开始新一轮开发时重新确认。本轮已完成2.9实测、3.0构建修复和有明确边界的最终回归；
详细证据分别见 `research/2.9.0-device-test.md` 与 `3.0-delivery.md`。

**交接点**：用户已授权完成3.0、完整功能回归并推送GitHub；家人观看期间已按要求暂停，
用户明确恢复后由专职GPT-6 Luna/max测试成员独占盒子操作，现已结束遥控并停在原生首页。
code16已保留数据安装，9项核心回归和发布断言通过，代码推送main且CI通过；
现场未新增本包崩溃/ANR，网络代理已恢复。听感、长期播放与错误态重试等边界见 `research/3.0.0-device-test.md`。

## 1. 源码与架构

- 私有仓库：`Leev1s/ikantv`，默认分支 `main`。
- 2.9对照提交：`a1a0831de152d72f78e09b1f98de82b3289fbb47`；3.0按构建、播放器、UI和验收文档分组提交。
- `build.sh` 的版本为 **3.0.0 / versionCode 16**；Gradle从此文件读取版本。
- 应用唯一身份：`com.lev1s.ikantv`。3.0采用Gradle/AGP、RecyclerView与Glide，播放仍为libVLC。
- `ui/NativeHomeActivity` 是桌面入口；`TabBar` 连接首页、搜索、电影、剧集、榜单、看过。
  首页用横向海报轨道，分类页用六列网格。`NativeBrowseActivity` 统一处理浏览页 Menu 回首页。
- `data/HttpFetcher` 抓 HTML，`IkanHtmlParser` 用 Jsoup 解析，`IkanDataSource` 聚合入口。
  搜索翻页必须沿用页面返回的 `n` 游标，不能自行构造。
- `PosterLoader` 用Glide提供图片缓存、候选地址回退与旧请求隔离；`HistoryStore` 用 SharedPreferences
  保存最多 50 条观看记录。播放进度另存在播放器的 `player` SharedPreferences。
- `PlayerActivity` 先从播放页字段计算 token，调用站点 `getResN` 获取线路；失败时回退应用内
  WebView harvest。播放核心是 libVLC 3.5.1，HTML 解析依赖 Jsoup 1.18.3。
- 播放器控制在单线程执行器中串行处理，使用播放代际过滤旧命令与回调，
  自动换源有 1.5 秒节流。修改播放器前先读历史换源崩溃报告，再核对当前实现。
  报告描述的是旧版本，不能直接当作当前仍存在的问题。
- 2.9实测发现剧集别名重复、卡片单行标题回归和焦点裁切；3.0按交付清单修复。
  `PlaybackCatalog`负责逻辑集名与旧续播迁移，`PlaybackRules`负责token与资源字段规则。
- 候选实测另外发现RecyclerView布局重入、Mali视频Surface退出崩溃、首页错误态焦点和电影片尾误切版本。
  最终代码保留默认布局保护，退出前主线程detach，播放控制仍在串行线程，片尾只读同代进度快照。

## 2. GitHub Actions

唯一 APK 工作流是 [Build APK](https://github.com/Leev1s/ikantv/actions/workflows/build.yml)：

- 触发：push 到 main、PR、手动 dispatch。
- 环境：Ubuntu 24.04、Temurin JDK 17、Android platform 34、build-tools 34.0.0；3.0超时20分钟。
- 3.0路径：资源与依赖校验 → 核心JUnit回归 → Gradle release → 产物断言 → 上传测试报告及APK。
- 校验固定发布证书 SHA-256：
  `945801887001b69f1d0a557ff58f8263e6374a07584d257b0046e5687e9e21ff`。
- 2.9基线查询时，最近 9 次 Build APK 均成功；最近 12 条 Actions 记录另外包含 3 次成功的 Dependabot 运行。
  最新 Actions 记录是 Dependabot，不能将它与最新 APK 构建混淆。当前没有打开的 PR。
- 2.9对照 APK 构建：[run 42232548900](https://github.com/Leev1s/ikantv/actions/runs/42232548900)，
  2026-11-06 22:17:07 开始，22:18:01 完成，提交就是上述 HEAD。
  构建、签名与包信息验证、artifact 上传各步骤都成功。
- 日志确认产物为 `com.lev1s.ikantv`、code 9、2.9.0，签名与固定证书一致。
  本次该运行使用仓库 base64 密钥回退路径；不在上下文中记录任何密钥或口令。
- artifact：`ikantv-a1a0831de152d72f78e09b1f98de82b3289fbb47`，约 19.8 MB，
  检查时未过期，到期时间 2026-12-06 22:17:56。保留期为 30 天，下载后 APK 为 `ikantv.apk`。
- 上述9次成功与run均属于2.9基线；3.0需要核对main对应提交的独立Build APK运行，
  不以Dependabot或旧版本的成功状态代替。
- 3.0代码提交 `79a8dfe5f40a3850083a5feb99707d34471b894d` 的
  [run 44117607876](https://github.com/Leev1s/ikantv/actions/runs/44117607876)已成功；
  测试/build、独立身份/资源断言及两类artifact上传全部通过。
  下载APK后再次核验签名/code16/name3.0.0，CI包与实测包的DEX、manifest、原生库一致；
  SHA及仅有的元信息/PNG编码差异见3.0报告。后续文档提交的运行仍须独立确认。
- Dependabot 每周检查 GitHub Actions；libVLC / Jsoup 仍由脚本固定版本和 SHA-256，
  Gradle分发包也固定SHA，新增依赖使用固定版本与Gradle锁文件。

## 3. 本机构建条件

本机已有 `adb`、`gh`、`uv`，`gh` 可读取此私有仓库。原先缺少JDK/SDK，
本轮已在 `/private/tmp/ikantv-toolchain/` 下载并核验官方Temurin17与Android SDK。
这些是本轮临时路径，重启或清理后须重新设置，不将工具二进制提交仓库。

准备好 JDK 17 和指定 SDK 后，构建命令为：

```bash
export JAVA_HOME=/private/tmp/ikantv-toolchain/jdk-17.0.21+9/Contents/Home
export ANDROID_HOME=/private/tmp/ikantv-toolchain/sdk
bash build.sh
```

SDK 必须包含 `platforms/android-34/android.jar` 和 `build-tools/34.0.0`。
唯一正式输出为 `build-manual/ikantv.apk`。资源与依赖由脚本恢复，不提交二进制和构建目录。
密钥沿用现有身份，参见 `keystore/README.md`。

本次工具执行需要在允许联网的环境中运行 `gh` 和 ADB；受限沙箱曾阻止 ADB 启动 5037 服务，
随后在允许的环境中已成功连接盒子。不要将这种执行限制误判为设备或项目故障。

## 4. Android TV 实机

| 项目 | 本次实测 |
| --- | --- |
| ADB | `10.0.0.10:5555`，已连接且可执行 shell |
| 本机局域网 | `10.0.0.100`，盒子流量经 `en0` |
| 厂商 / 型号 | Droidlogic / V_BOX14 |
| 硬件 | Amlogic，board platform `p291` |
| 系统能力 | SDK **28**；release 属性写 `14`，fingerprint 却标识 Android **9** |
| ABI | `armeabi-v7a,armeabi`，应用运行 ABI 为 `armeabi-v7a` |
| 屏幕 / density | `1280x720` / `213 dpi` |
| 内存 | `MemTotal: 2054236 kB`，约 2GB |
| 数据分区 | 约 11GB，本次剩余约 6.1GB |
| 安全补丁属性 | `2024-06-05`，仅记录设备自报值 |
| WebView | `com.google.android.webview` 138.0.7204.179 |
| 升级前基线 | **2.9.0 / versionCode 9**，minSdk 28、targetSdk 34；从2.8.7保留数据升级 |
| 当前候选 | **3.0.0 / versionCode 16**，22:13:26安装，原签名/包名不变；首次安装时间仍为2026-11-06 13:38:48 |

设备营销版本与底层 API 不一致，兼容性必须按 API 28 判断。
以下首次观察属于2.8.7；后续2.9完整检查见独立报告，不能混为同一版本。

已完成的有限检查：

- 启动原生首页，站点数据与海报实际加载成功，看到明确的琥珀色焦点框。
- 用方向键与确认键进入电影分类，看到六列海报网格与分类标签。
- 点播样片《懦夫》，界面获得 29 条线路记录，线路 27 出现实际视频画面，
  后续截图进度到约 01:03 / 2:06:49。面板标注该线路探测约 905ms；
  这些是该样片当时的结果，不代表所有线路可用，也不把“720P”版本名当作实际解码分辨率。
- 下键打开换源面板，Menu 切到“选择版本”，返回关闭面板，发送 OK 暂停后退出样片。
- 分类页 Menu 返回首页。检查结束停在原生首页。

首次检查未覆盖的2.9换源、搜索和翻页，随后已按独立报告补测。
首次检查未验证长时间播放、音频听感与自动播完跳集；3.0后来补测了真实片尾及自动下一集，
长时间连续播放与听感仍保留为未测，见3.0报告。
样片点播会按应用正常行为留下观看历史与播放进度；未清除用户数据。

## 5. 后续开发与复测入口

```bash
adb connect 10.0.0.10:5555
adb -s 10.0.0.10:5555 shell am start -W -n com.lev1s.ikantv/.ui.NativeHomeActivity
adb -s 10.0.0.10:5555 shell dumpsys package com.lev1s.ikantv
adb -s 10.0.0.10:5555 shell input keyevent 82
```

遥控键码：上 19、下 20、左 21、右 22、确认 23、返回 4、Menu 82。
浏览页 Menu 回首页；播放器 Menu 打开换源，面板内 Menu 切换选集/换源，不能混淆。

此盒子的 `exec-out screencap -p` 会在 PNG 前输出厂商调试文字，直接重定向不是有效图片。
可靠截图方式如下，截图和 UI dump 留在临时目录，不进入仓库：

```bash
adb -s 10.0.0.10:5555 shell screencap -p /data/local/tmp/ikantv-screen.png
adb -s 10.0.0.10:5555 pull /data/local/tmp/ikantv-screen.png /private/tmp/ikantv-screen.png
adb -s 10.0.0.10:5555 shell uiautomator dump /data/local/tmp/ikantv-window.xml
adb -s 10.0.0.10:5555 pull /data/local/tmp/ikantv-window.xml /private/tmp/ikantv-window.xml
```

后续完整闭环：核对新版本号与发布签名 → 构建成功 → 保留数据升级安装 →
真机验证本次改动涉及的浏览、焦点和播放行为。3.0新增的纯Java测试只覆盖核心规则；
构建成功不能证明实机焦点、播放稳定性或声音效果。Manifest的跨应用旧注释已清理。
