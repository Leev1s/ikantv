# ikantv（爱看电视·原生版）

ikanbot.com 的 Android TV 原生客户端：原生界面浏览
（海报墙、分类、搜索、观看历史），播放走 libVLC 硬解。

- 应用名：爱看电视·原生版
- 包名（applicationId）：`com.lev1s.ikantv`
- 当前版本：versionCode 5 / versionName 2.8.4
- 系统要求：Android 9+（minSdk 28），targetSdk 34，横屏，仅 armeabi-v7a
- 同系列还有一个 WebView 版「爱看电视」（`com.lev1s.ikanbottv`），在另一个工程里。
  两个应用完全独立：各自安装、各自记录，互不调用、互不共享状态；本应用遇到
  站点人机校验或解析失败时只在应用内提示重试，不跳转到另一个应用。

## 构建

不走 Gradle，用自包含的手工链（aapt2 + javac + d8 + zipalign + apksigner），
本地与 CI 同一条命令：

```bash
bash build.sh   # 产物：build-manual/ikantv.apk
```

`build.sh` 会自己处理三件事：

1. 把图标、横幅、字体等二进制资源从 `ci-assets/*.b64` 还原并验 SHA-256
   （仓库只存文本，二进制以 base64 形式存放，清单在 `ci-assets/MANIFEST.txt`）；
2. 从 Maven Central 下载并验 SHA-256 两个依赖：libVLC for Android 3.5.1、
   Jsoup 1.18.3（脚本 `tools/fetch-deps.sh`，产物在 `build-deps/`，不进 git）；
3. 找签名密钥签名。密钥的放法见 `keystore/README.md`——明文 `.jks` 永不进 git。

本地前置：JDK 17、`ANDROID_HOME` 指向含 platform 34 与 build-tools 34.0.0 的 SDK。

## 云端构建（GitHub Actions）

`.github/workflows/build.yml`：推 `main`、开 PR、或手动 `workflow_dispatch` 都会
在云端用同一条 `build.sh` 构建，验签与包信息后把 APK 作为 artifact 上传
（保留 30 天）。密钥优先读仓库 Secret `SIGNING_KEYSTORE_B64`，
其次读仓库内的 `keystore/ikantv.jks.b64`，都没有就直接报错。

## 目录

```text
app/src/main/java/com/lev1s/ikantv/
  ui/     原生界面：首页海报墙、分类网格、搜索、历史、焦点与海报加载
  data/   站点抓取与 HTML 解析、播放线路、海报地址、历史存储
  PlayerActivity.java  libVLC 播放器：选集/换源双面板、线路测速、坏源自动换线、续播
thirdparty/vlc-res/    libVLC 运行时要 inflate 的布局（须以 org.videolan 包编入）
ci-assets/             二进制资源的 base64 文本 + SHA-256 清单
docs/research/         可行性研究、站点探测、换源崩溃审查等历史报告
```

命名约定：代码包名与 applicationId 已统一为 `com.lev1s.ikantv`（2.8.2 起），
Manifest 里 Activity 用相对类名。历史背景：2.8.1 及以前代码包是
`com.lev1s.ikanbottv`、applicationId 是 `com.lev1s.ikanbottv.native`
（`native` 是 Java 关键字、作不了包名），2.8.2 借仓库独立化的机会一并改名；
盒子上按新应用处理（旧包数据迁移与桌面重排见装机记录）。

给 AI 协作者的工作约定见 [AGENTS.md](AGENTS.md)。
