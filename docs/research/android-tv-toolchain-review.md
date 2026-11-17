# Android TV 开发基础 Review

复核日期：2026-11-17。依据当前源码、此前实机检查与下列一手资料。
本轮仅修订建议，未迁移构建、引入依赖或改动业务；现行 AGENTS.md 继续有效。

## 结论

先建立可靠的构建、回归和职责边界，再决定 UI 框架迁移范围。
Kotlin 适合逐步采用，Compose for TV 适合作为候选做单页验证。
当前没有本项目的框架性能对照，不能把换框架当作兼容性或速度提升的保证。

## 专业资料给我们的启发

- [Android 架构建议](https://developer.android.com/topic/architecture/recommendations)：
  明确 UI 与数据职责；小应用可以用 package 分层，domain/use-case 层主要面向较复杂应用。
  对本项目的推论：保留一个 app 模块，先把页面状态、站点访问与播放会话分开。
- [TV 渐进迁移指南](https://developer.android.com/training/tv/playback/leanback/migrate-to-compose)：
  从小范围屏幕开始，调整状态管理并逐步验证。该文面向 Leanback；
  借鉴的是迁移方法，不代表我们的 Views 页面必须重写。
- TV 工程师 Rúben Sousa 的 [焦点规则](https://rubensousa.github.io/DpadRecyclerView/recipes/focus/)、
  [选择与焦点区别](https://rubensousa.github.io/DpadRecyclerView/selection/)及
  [按键测试](https://rubensousa.github.io/DpadRecyclerView/testing/)：
  边界焦点、选中项和快速按键要独立处理与验证；这类经验比直接照搬页面外观更有用。
- [Google JetStream 示例](https://github.com/android/tv-samples/tree/main/JetStreamCompose)：
  除 UI 外还提供 Macrobenchmark / Baseline Profile 示例。
  README 仍列有已弃用的 TvLazy API，具体 API 应按
  [当前 TV 列表指南](https://developer.android.com/training/tv/playback/compose/lists)核对，不能照抄旧配置。
- [TV 内存指南](https://developer.android.com/training/tv/playback/memory)：
  图片、视频缓冲和页面生命周期要一起测量。2GB RAM 不等于系统必然标记为 low-RAM；
  不直接套用指南针对 1GB 设备给出的内存限额。

## 按优先级收敛的建议

| 顺序 | 当前证据 | 建议与完成标准 |
| --- | --- | --- |
| 1. 固定可验证基线 | 源码 2.9.0，已测盒子 2.8.7；本机尚缺可用 JDK/SDK。CI 对签名有断言，包名和版本只打印 | 先确认当前源码的真机行为；补包名、版本、minSdk、ABI、VLC 资源的产物检查。保持发布签名、升级数据和唯一版本来源 |
| 2. 统一播放控制入口 | PlayerActivity 1539 行，含取数、UI、会话；togglePlay 和长按 OK 仍直接调用 play | 先把播放命令统一送入已有串行控制路径，再逐步分离站点取线与播放会话。保留代际、手动优先和节流；用旧回调、连续切线、退出/恢复场景验证 |
| 3. 补少量高价值回归 | 解析器、分页游标与 token 都有站点特定规则，目前无自动回归 | 用人工构造的最小 HTML/JSON 片段覆盖搜索无结果、挑战页、下一页游标和线路解析；会话边界拆出后再测旧事件不得改变新播放。无需提交整页快照 |
| 4. 列表和图片复用成熟组件 | 分类翻页把所有卡片追加到 View 树；图片管线自写 | 在一个电影页验证可回收/lazy 列表与成熟图片库；保留候选域名、Referer、异常图片处理。固定数据量下比较内存和快速遥控行为 |
| 5. 再扩展现代 UI | API 28、32 位 ARM、720p；尚未跑 Compose 原型 | Kotlin 与 Java 共存；Compose for TV 为候选，Views + RecyclerView/DpadRecyclerView 为另一条可用路线。单页达标后只沿选定路线迁移 |

代码定位：
[CI](../../.github/workflows/build.yml#L59)、
[直接播放入口](../../app/src/main/java/com/lev1s/ikantv/PlayerActivity.java#L1476)、
[长按入口](../../app/src/main/java/com/lev1s/ikantv/PlayerActivity.java#L1437)、
[列表追加](../../app/src/main/java/com/lev1s/ikantv/ui/NativeGridActivity.java#L377)。
以上是静态发现；本轮未复现新的 ANR、内存耗尽或焦点故障。

## 建议保留的简单结构

- UI：渲染屏幕状态、接收遥控操作；列表位置与焦点按稳定条目 ID 恢复。
- 页面状态：负责加载、错误、重试和生命周期；引入 Jetpack 后用 ViewModel/Lifecycle。
- 数据：沿用 Jsoup、站点规则与本地历史；只在需要替换或测试的边界增加抽象。
- 播放会话：集中持有 libVLC、串行命令、代际和续播状态，UI 不直接控制内核。

这四类职责放在现有 app 模块即可，不为每个类增加接口或 use case。
保留 libVLC、SharedPreferences 与既定 TV 视觉规格；暂不引入多模块、Hilt、Room 或双播放内核。

## 工具链与验收

Gradle/AGP 是后续复用 AndroidX 的优先基础设施建议，可先保持 Java、现有 UI 与 libVLC，
单独证明打包、资源及签名正确，再引入 Kotlin/Jetpack。先保留现有 build.sh；
新链路验收后更新项目约定并收敛为唯一构建入口，避免长期维护两套。
本轮不实施该迁移，也不同时升级 targetSdk 行为或更换播放内核。

采用新组件前，在 10.0.0.10 上用同版本数据、相同缓存条件和 release 构建记录：
冷启动耗时、连续翻页内存、返回时的条目/位置、快速方向键、加载失败重试、
换源/续播与退出恢复。图片加载完成不能抢走用户已移动的焦点，关闭面板要有明确焦点落点。
API 28 与 armeabi-v7a 必须保留。先取得当前版基线，再设置可接受的回归范围。

最先值得实施的是“发布验证 + 播放控制入口 + 核心规则回归”；
界面重构建立在这些保护之上。本轮没有候选框架原型或新的性能测试结果。
