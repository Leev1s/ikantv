# 换源崩溃代码审查报告（爱看电视 v2.6.4）

- 审查时间：2026-11-04
- 审查方式：纯静态代码审查（盒子未开机，无真机验证）
- 症状（用户原述）：播放中源卡住 → 手动换源时，很多次整个 App 卡死然后重启，无法正常切源续播
- 审查文件：`PlayerActivity.java`（988 行，全文）、`MainActivity.java` 拉起路径、`AndroidManifest.xml`、随包 libVLC `classes.jar` 的 `MediaPlayer` 方法表（javap 核对）

## 结论先行

切线链路的全部 libVLC 调用（`stop / setMedia / play`）都跑在**主线程**，且随包 libVLC 3.x 的 `MediaPlayer` **只有同步 `stop()`、没有异步 stop**（javap 实测，见附录）。当旧源是「卡住」而不是「干净报错」时，其输入线程正阻塞在网络读里，同步 `stop()` 要等输入线程退出才能返回——主线程就被钉在 JNI 调用里，轻则数秒无响应，重则 ANR 后进程被系统杀掉，表象正是「卡死 → 整个 App 重启」。这是与症状吻合度最高的根因（假设 H1）。

同时事件处理**没有任何播放代际（generation）守卫**：上一条线路迟到的 `EncounteredError` 会被算到当前线路头上，触发误标记坏源 + 连环自动换线，与手动换线叠加后会对播放器形成高频 `stop→setMedia→play` 锤击，这是第二嫌疑（假设 H2/H3，native 崩溃与状态错乱方向）。

代码中可排除的方向：播放器实例是单例复用、无多 Activity 并存；`Media` 用完即 `release()` 是 libVLC 官方标准用法、不是泄漏；共享集合的读写基本都在主线程，未见并发修改/越界的实锤路径；OOM/内存泄漏证据不足（见 H4）。

---

## 一、根因假设清单（按可能性排序）

### H1（最可能）：切线时主线程同步 `vp.stop()` 被卡死的旧流堵住 → ANR → 进程被杀

**证据**

- `playUrlInternal()`（PlayerActivity.java:613-632）：每次播放/切线都走 `stopPlayback()` → 新建 `Media` → `vp.setMedia()` → `vp.play()`，全程在调用者线程执行。
- `stopPlayback()`（:660-665）：`saveState()` 后直接 `vp.stop()`，无异步、无超时保护。
- 切线的全部入口都在主线程：换源面板点击（:165-168 `switchLine`）、`onPlayError` 的事件 Runnable（经 `handler.post` 回主线程，:548、:597-601）、选集点击、EndReached 自动下一集（:583-594）。
- javap 核对随包 `org.videolan.libvlc.MediaPlayer`：只有 `public void stop()`，**没有 stopAsync 之类的方法**（libVLC 4 才引入异步 stop；3.x 的 `libvlc_media_player_stop` 语义是等输入线程终止后才返回）。
- 加重因素：VLC 选项只设了 `--network-caching=2000`（:541、:623），**没有给 HTTP 输入设任何读超时**。源「卡住」最常见的形态是服务器保持连接但不再发数据（或发得极慢），此时输入线程长时间阻塞在 read 上，`stop()` 等它退出就遥遥无期。

**触发时序**（与用户描述逐项对上）

1. 线路 A 播放中卡住：画面停住/反复缓冲，输入线程阻塞在网络读，尚未产生 `EncounteredError`。
2. 用户按 ▼ 开换源面板，点线路 B。
3. 主线程执行 `switchLine → playUrlInternal → stopPlayback → vp.stop()`，在 native 层等待线路 A 的输入线程退出——等不到。
4. 主线程冻结：遥控无响应、面板关不掉、画面定格（用户看到的「卡掉」）。
5. 约 5 秒输入无响应触发 ANR，系统杀进程；用户从桌面/最近任务重新进入，表象为「整个 App 重启」，续播无从谈起。

为什么是「很多次」而不是「每次」：旧源若已经干净报错（连接被拒、404、断流），输入线程不在阻塞读里，`stop()` 很快返回，切线就正常；只有旧源处于「连接还活着但不吐数据」的真卡死状态时才触发——与用户「源卡了的时候换源才容易死」的语境完全一致。

### H2：错误风暴下高频 `stop→setMedia→play` 锤击 libVLC → native 崩溃（SIGSEGV）

**证据**

- `onPlayError()`（:634-650）：收到错误即同步调用 `playUrlInternal()` 切下一条，全程**无最小间隔、无次数上限、无「切换进行中」标志**。
- `EncounteredError` 事件（:597-601）每来一次就走一遍上述链路。死链路（域名失效、秒拒）报错回得极快时，自动换线会以机器速度把多达 27 条线路挨个 `stop→setMedia→play` 一遍；libVLC 3.x 在上一路输入尚未完全拆解时被 `setMedia` 是公认的 native 不稳定点（VLC 官方 App 对播放器操作做了串行化处理，本代码没有）。
- 用户手动换线与自动换线共用同一条 `playUrlInternal`，没有任何互斥/合并：手动点 B 的同一时刻，自动换线可能正在切 C，两次切换在主线程消息队列里背靠背执行，`stop` 的对象可能已经是对方刚 `setMedia` 的新流。

**症状对应**：若 H2 成立，表象更接近「一切换立刻闪退/回桌面」而非「先冻住几秒」；与 H1 的区分靠日志（见第四节）。

### H3：迟到的错误事件被算到新线路头上（无代际守卫）→ 误标坏源、连环乱跳

**证据**

- 事件监听器（:548-606）把每个 VLC 事件 `handler.post` 到主线程后**无条件按当前 `curEp/curLine` 处理**：`onPlayError`（:634-636）标记坏的是执行那一刻的 `curLine`，而不是真正出错的那条线路。
- 事件从 native 线程投递到主线程队列需要时间；用户抢先手动切线（点击消息先被处理）后，排在其后的旧线 `EncounteredError` Runnable 才执行，就会把**新线路**标记为坏源并立刻自动跳走。用户感知：「我刚切过去它自己又跳了 / 刚切就卡一下又换了」。
- `badSources`（:75）只增不减、整个会话有效，被误标的好线路再也回不来；多轮误伤后会走到「这一集的线路都试过了播不了」（:646-648）弹面板收场。
- 同类问题较轻的版本：旧流的 `Buffering` 事件（:555-567）会给新流乱刷加载文案/进度，属于表象问题。

**定位**：H3 单独不杀进程，但它制造的连环自动切换正是 H2 的锤击来源、也可能把用户送进一条同样卡死的线路再触发 H1。三者是一条因果链上的三环。

### H4（证据不足，低）：反复切线造成 native 资源/内存累积 → LMK 杀进程

**审查结论：Java 层未发现泄漏路径。**

- `LibVLC` 与 `MediaPlayer` 只在 `ensureVlc()`（:535-546）创建一次、整个 Activity 生命周期复用，`releasePlayer()`（:667-680）在 `onDestroy` 完整走 `stop → detachViews → release`，顺序正确。
- 每次切线的 `Media` 在 `setMedia` 后立即 `release()`（:627-628）——这是 libVLC 的引用计数约定（`setMedia` 自己持有引用），**不是泄漏点，不要「修」它**。
- 隐藏 harvest WebView 在取数成功后 `destroy()`（:458-463），退出时 `onDestroy` 兜底销毁（:983）。
- 残余不确定性：libVLC 内部在输入异常终止时是否完全回收 native 资源，从 Java 代码看不出；盒子只有 2GB RAM，WebView + VLC + 系统同住，内存压力本就存在。若真机日志出现 lowmemorykiller 记录再按此方向查（见第四节），代码层面暂无可指认的漏点。

### H5（低）：Java 层异常杀进程

**审查结论：切线链路上未发现未捕获异常的实锤路径。** 面板 Adapter 的 `episodes.get(curEp)` / `lines.get(p)` 下标均受数据结构约束；`currentUrl()` 对空集有判空（:527-530）；`playUrlInternal` 主体有 try/catch 并转入 `onPlayError`（:625-631）。`switchEpisode` 的 `keepPos` 参数被忽略（:670-678，续播位置在切集时实际不保留）是功能缺陷、与崩溃无关，附带记录。

### 已排除项

- **多播放器实例打架**：`PlayerActivity` 在 Manifest 中为 `singleTask`，`MainActivity` 以普通 `startActivity` 拉起（MainActivity.java:255-259），同一时刻只有一个播放页、一个 `LibVLC` 实例，不存在 libVLC 多实例共存问题。
- **测速线程池与切线的并发冲突**：测速（:689-730）只在池线程做 HTTP，结果经 `handler.post` 回主线程写 `lineLatency/lineProbeDone` 并刷面板；`lines/episodes` 的结构性修改只发生在 `onHarvestJson`（主线程）。`badSources/lineLatency` 虽是普通 `HashMap/HashSet`，实际读写也都在主线程——**未发现 ConcurrentModification/越界的并发路径**。唯一共享的是 `probePool` 同时被原生取数（:188-200）与测速共用，最多互相排队变慢，不构成崩溃。
- **Surface/attach 时机**：`attachViews` 在 `ensureVlc` 里对着布局中常驻的 `VLCVideoLayout` 只做一次（:543），切线不 detach、不重建，无「vout 未 attach 就 play」的问题。

---

## 二、第一名假设（H1）的完整失败链推演

```
线路A播放中，源站连接假死（TCP 还开着、数据不再来）
  → VLC 输入线程阻塞在 access read；UI 侧只收到零星 Buffering 事件，
    setLoading(true,"正在缓冲…")，prepared 仍为 true，无 EncounteredError
  → 用户判断「这条卡了」，按 ▼ 开面板、选线路B（dispatchKeyEvent :165-168）
  → 主线程：closePanel() → switchLine(B) → playUrlInternal(urlB)
  → stopPlayback()：saveState()（vp.getTime() 还能返回）
                  → vp.stop()  ← 同步 JNI 调用，要求输入线程先终止
  → 输入线程卡在无超时的网络 read 里，迟迟不退出
  → 主线程在 libvlc_media_player_stop 内核等（数秒～数分钟量级）
  → 遥控输入无人处理 → InputDispatcher 报 ANR（主线程 5s 无响应）
  → 系统杀掉 com.lev1s.ikantv 进程 → 用户重进 = 「APP 重启」
  → 期间 saveState 已存的是 A 的旧进度，B 根本没开始播，无所谓「续播」
```

对照组（为什么有时切线没事）：旧源已断流/被拒时输入线程能立即退出，`stop()` 亚秒返回，整条链顺畅——与用户「很多次会」而非「每次都会」的措辞吻合。

H2/H3 如何并入这条链：若旧源在用户点 B 的前后脚报出 `EncounteredError`，H3 的误判会立刻再触发一次自动切线，新一轮 `playUrlInternal` 又对着（可能同样假死的）线路做同步 `stop()`——等于在同一个冻结点上再撞一次；若错误来得快而密，则退化为 H2 的高频锤击，结局从 ANR 变成 native 层直接崩溃。两种死法都源于同一组设计缺陷：**主线程同步调用可阻塞的播放器操作 + 事件无代际守卫 + 切换无串行化**。

---

## 三、修复方案（小步、沿用 libVLC 标准用法，不造轮子）

按优先级排，前两项是治本的，后面是配套。

### 修复 1：播放器控制操作搬离主线程，串行执行（治 H1、H2）

- 新增一个单线程 `ExecutorService`（或 `HandlerThread`）专责播放器操作，例如 `playerOps`。
- `playUrlInternal()` 拆成两半：UI 部分（`setLoading` 文案、`updateSubText`）留在主线程；VLC 部分（`stopPlayback()` 里的 `vp.stop()`、`ensureVlc()`、`new Media`、`vp.setMedia()`、`vp.play()`）整体投到 `playerOps` 里顺序执行。libVLC 的 `MediaPlayer` 方法本身是线程安全的（官方 App 亦在非 UI 线程调用），这样主线程永不被 `stop()` 堵住，最坏情况只是「新线路晚几秒出来」，遥控和面板始终可操作。
- `onDestroy`/`releasePlayer()` 同样投到 `playerOps` 执行后再放行（或直接在该线程内 `stop→detach→release`），避免退出时主线程再吃一次同步 stop；`probePool` 之外注意 `onDestroy` 里对 `playerOps` 用 `shutdown()`（不要 `shutdownNow` 打断正在进行的 release）。
- 改动范围：`playUrlInternal`、`stopPlayback`、`releasePlayer`、`ensureVlc` 四个方法的调用点，事件监听器与面板代码不动。

### 修复 2：给播放尝试加代际号，事件按代际认领（治 H3）

- 加字段 `private volatile int playGen = 0;` 与 `private volatile long playGenStartMs;`。
- 每次真正发起播放（修复 1 中投到 `playerOps` 的那一段开头）先 `int g = ++playGen; playGenStartMs = SystemClock.uptimeMillis();`。
- 事件监听器在 **native 回调当场**（`handler.post` 之前）捕获 `final int g = playGen;` 写入 Runnable；Runnable 执行时若 `g != playGen`，说明是上一代流的迟到事件，直接丢弃（至少 `EncounteredError` 与 `EndReached` 必须如此；`Buffering` 可放宽，只丢弃会触发动作的类型）。
- 残余窗口：native 事件在代际切换后极短时间内仍可能以旧事件读到新 `playGen`——窗口在毫秒级，且后果只是偶发一次误判，可接受；配合修复 3 的节流，实际影响趋零。若要更严，可同时校验事件到达时间早于 `playGenStartMs` 的直接丢弃。

### 修复 3：自动换线节流 + 手动/自动切换合并（治 H2 的锤击）

- 加字段 `switchInFlight`（或复用代际号 + 时间戳）：一次切线发起后 1.5 秒内，新的自动换线请求只记录「待切目标」不立即执行，到期后若当前流还没 `Playing` 再执行最后一次请求；手动点击永远优先且立即生效（并取消待执行的自动请求）。
- 自动换线加整场上限提醒即可，不必硬限次数——节流后 27 条线最坏情况也要约 40 秒才轮完，`stop()` 又在后台线程，不再具备杀伤性。
- `onPlayError` 的 Toast 加合并：同一轮自动换线链只在最终落定（开始播或全部失败）时提示一次，避免 Toast 排队刷屏拖慢主线程。

### 修复 4（可选、低风险）：给输入加网络层兜底超时

- 在 `Media` 选项里已有 `:network-caching=2000`；可再给 libVLC 全局选项补 `--http-timeout=10` 类参数前先查 3.5.1 是否支持（该选项在 libVLC 里并非所有版本都对 HLS access 生效），**不确定就不要加**，以免无效改动。真正的兜底是修复 1 让 `stop()` 阻塞不再伤害 UI——此条仅作备选记录，不列入首批改动。

### 附带（与崩溃无关，顺手记录，本次可不改）

- `switchEpisode(int, boolean keepPos)` 的 `keepPos` 未被使用（:670-678），切集从不保留进度，与键位设计注释不符，后续迭代时处理。

---

## 四、盒子开机后的验证清单

### 复现步骤（按命中率排序）

1. **假死源复现（验 H1）**：选一部多线路的片子，挑一条能播但明显卡顿的线路播起；等它卡住（画面停住≥10 秒、只有缓冲提示）时，按 ▼ 选另一条线路。观察：App 是否整体冻住、遥控是否失灵、多久后回桌面/重启。重复 3–5 次（可故意在刚卡住的瞬间立刻换线，命中率最高）。
2. **连环点线（验 H2/H3）**：播放中打开换源面板，快速逐条点过 5–8 条线路（含已知播不了的）；再试选集面板快速连点不同集数。观察：是否闪退、是否出现「刚切过去又自己跳走」、好线路是否被莫名标成「播不了」。
3. **死线自动轮切（验 H2）**：找一部线路大多失效的片子让它自动轮切，观察轮切节奏是否快到异常、过程中是否崩溃。

### 要抓的日志（崩溃/卡死后立刻抓，趁 logcat 未滚掉）

| 假设 | 抓取命令 / 特征签名 |
|---|---|
| H1 ANR | `adb logcat -d \| grep -E "ANR in com.lev1s.ikantv\|Application is not responding\|InputDispatcher"`；再看 `/data/anr/traces.txt`（盒子 adbd 为 root，可直接 `adb pull`）：主线程栈应停在 `org.videolan.libvlcjni` / `libvlc_media_player_stop` 附近的 native 帧，即实锤 |
| H2 native 崩溃 | `adb logcat -d \| grep -E "Fatal signal\|SIGSEGV\|signal 11"`；`/data/tombstones/` 最新文件的 backtrace 应含 `libvlc.so / libvlcjni.so` 帧 |
| H4 LMK/OOM | `adb logcat -d \| grep -iE "lowmemorykiller\|Killing.*ikanbottv\|Process com.lev1s.ikantv.*has died"` |
| Java 异常（排除项） | `adb logcat -d \| grep -A 20 "FATAL EXCEPTION"` 中出现本包名与 PlayerActivity 栈帧 |
| 自家日志 | `adb logcat -d -s IkanPlayer` 看 `VLC encountered error` / `play failed` 的出现频率与切线动作的时间对应关系 |

### 修复前先行验证（建议）

在动手修复前，先出一版只加计时的诊断包：在 `stopPlayback()` 的 `vp.stop()` 前后各打一条带时间戳的 `Log.i(TAG, ...)`，真机复现一次——若日志出现 stop 耗时数秒以上且与 ANR 时间吻合，H1 实锤，再按第三节顺序落地修复 1+2+3；若 stop 耗时正常但有 tombstone，则优先按 H2 只做串行化+节流（修复 1、3）。

---

## 附录：关键代码位置索引

| 位置 | 内容 |
|---|---|
| PlayerActivity.java:613-632 | `playUrlInternal`：stop→setMedia→play，主线程同步执行 |
| PlayerActivity.java:660-665 | `stopPlayback`：`saveState` + 同步 `vp.stop()` |
| PlayerActivity.java:548-606 | VLC 事件监听器：无代际守卫，`EncounteredError` 在 :597-601 |
| PlayerActivity.java:634-650 | `onPlayError`：按当前 `curLine` 标坏源并同步再切线，无节流 |
| PlayerActivity.java:165-168 | 换源面板点击 → `switchLine(position, true, safePos())` |
| PlayerActivity.java:535-546 | `ensureVlc`：单例创建 + 一次性 `attachViews`（正确用法） |
| PlayerActivity.java:667-680 | `releasePlayer`：stop→detach→release，仅 `onDestroy` 调用 |
| PlayerActivity.java:689-730 | 测速：池线程取数、主线程回写，未见并发冲突 |
| javap 实测 | 随包 `MediaPlayer` 只有同步 `stop()`，无异步 stop 方法 |
