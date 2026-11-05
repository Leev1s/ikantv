# ikanbot.com 官网只读逆向探测报告

- 探测时间：2026-11-03（CST）上午
- 方式：只读 GET（桌面 UA curl 串行慢速，共 19 次请求）+ 既有源码副本分析；未注册/登录/提交，未点击广告路由，未绕过任何校验，未下载视频分片（仅读取了 1 个 m3u8 索引文件）
- 证据来源标注：〔实测〕= 本次 live 请求；〔源码〕= research/ 下既有副本或本次拉取的站点静态文件（common.js v3.2 / common.css v3.0 / play_new.js v1.64）

---

## 0. 一页结论（给 TV 客户端用的）

1. **全站只有一个 XHR 接口**：`GET /api/getResN`。其余页面（首页/榜单/片单/分类/搜索/历史）全部是服务端渲染 HTML 或纯本地渲染，没有搜索建议、榜单、历史等隐藏接口。〔源码全量 grep + 实测〕
2. **getResN 的 token 算法已完全逆向且可原生复刻**（订正此前「不可在原生侧重实现」的结论）：token 只依赖播放页 HTML 里的 `#current_id` 与 `#e_token` 两个隐藏字段，是确定性字符串切割，无会话/cookie 绑定（本次无 cookie 调用即返回 state:1）。算法见 §3.2。风险：算法写在页面内联混淆脚本里、无版本号，站点可随时改；建议原生直算优先、遇 -401 回退现有 WebView harvest。〔实测验证：电影/剧集各一次，均 state:1〕
3. **站点自身没有播放失败重试/自动换线**：play_new.js 里没有任何 player error 处理，线路坏了就卡住等用户手点。TV 端的自动换线是纯增益，必须自研（已做）。〔源码 play_new.js〕
4. **广告是「点击劫持」式**：common.js 按地理位置 + 点击计数，在列表页每点第 3 个播放链接、或播放页点播放器/线路时，新标签打开 `/go/as|mtag|cn1|cn2` 广告路由，带 20–60 分钟冷却。原生拦截架构天然不触发；但注意列表页计数逻辑把 `isTrusted===undefined` 也算可信点击，WebView 内程序化点击要小心。〔源码 common.js〕
5. **海报懒加载改写规则已摸清**：只有 doubanio.com 的图会被改写成自家代理 `imgp.ikanbot.eu.org/i/{视频ID}?u={base64url(原图URL)}`，失败还有 imgp1/imgp2 换域名、`/proxy?url=`、默认图三级回退。TV 端可直接按此规则拼 URL，不必等页面改写。〔源码 common.js〕
6. **m3u8 特征**：样本为 AES-128 加密（相对路径 enc.key、IV 全零）的媒体播放列表，无多码率 master 档——播放器支持 AES-HLS 即可，不需要自适应码率逻辑。〔实测 1 个索引文件，未拉分片〕
7. **Cloudflare 校验**：本次探测（curl + 桌面 UA）访问 `/`、`/play/`、`/api/` 均未触发校验、全程 200 且多为 CDN HIT。此前老 WebView 在 /play/ 遇「Just a moment」的现象应与客户端指纹/行为评分相关、非路径固定策略；TV 端保留现有「检测标题自动重载」即可。〔实测 + 主线记录〕

---

## 1. 技术栈指纹

| 项 | 结论 | 证据 |
|---|---|---|
| CDN/边缘 | Cloudflare（Server: cloudflare，cf-cache-status，CF-RAY） | 〔实测响应头〕 |
| 前端框架 | Bootstrap 3.4.1（cdnjs）+ 自有 common.css v3.0（仅 2.4KB，多为搜索框/loader/导航 flex） | 〔源码 home.html / common.css〕 |
| JS 库 | Zepto 1.1.6（非 jQuery）、vanilla-lazyload 17.8.3、video.js 8.4.0 + zh-CN 语言包（仅播放页） | 〔源码 home.html / play.html〕 |
| 站点脚本 | /resources/common.js?v=3.2（广告/懒加载/地理判定）、/resources/play_new.js?v=1.64（仅播放页）；静态资源用 `?v=` 做版本 | 〔源码〕 |
| 统计 | Google Analytics gtag.js，ID `G-68RFVMY7K6`，全站加载；未见百度统计/ CNZZ 等 | 〔源码全量 grep〕 |
| 后端线索 | 响应头含 `Vary: Origin / Access-Control-Request-Method / Access-Control-Request-Headers`（Spring 风格 CORS）、`Content-Language: zh-CN`、JSON API 风格，疑似 Java/Spring 后端；站点自述为「网络爬虫技术检索」聚合搜索引擎 | 〔实测响应头 + meta description〕 |
| 缓存 | HTML：浏览器侧 `Cache-Control: no-cache,no-store`，CDN 侧 `CDN-Cache-Control: public,max-age=3600`（边缘缓存 1 小时，实测 Age 540、HIT）；getResN 的 CDN max-age=304（约 5 分钟，数值古怪但属实） | 〔实测响应头〕 |
| 其他响应头 | `X-Frame-Options: SAMEORIGIN`；响应带 Set-Cookie（值未记录） | 〔实测〕 |
| meta | viewport `width=device-width, initial-scale=1, maximum-scale=1`；`X-UA-Compatible: IE=EmulateIE9`（老古董声明） | 〔源码 home.html〕 |
| robots.txt | 对 AhrefsBot/YandexBot/SemrushBot 等一批 SEO 爬虫 `Disallow: /` | 〔实测〕 |

页面无服务端 UA 分版：research/ 中 home.html 与 home-desktop.html 字节完全一致（27078 B），本次 live 首页亦为 27078 B——桌面/移动差异纯靠 CSS 响应式 + 少量 JS matchMedia。〔源码比对 + 实测〕

---

## 2. 站点结构与路由

### 2.1 路由总表

| 页面 | URL 规律 | 渲染方式 | 每页条数 | 证据 |
|---|---|---|---|---|
| 首页 | `/` | 服务端渲染；板块：最近热门电影 / 最近热门剧集 / 一周口碑榜（.v-list > h4） | 每板块 6–12 | 〔源码 home.html〕 |
| 电影/剧集列表 | `/hot/index-{movie\|tv}-{标签}.html`，翻页 `-p-{n}` 追加在标签后：`/hot/index-tv-热门-p-2.html` | 服务端渲染 | 24 | 〔实测 hot-movie / hot-tv-p2〕 |
| 榜单 | `/billboard.html`（单页，无分页） | 服务端渲染，多榜单拼接：豆瓣一周电影口碑榜、豆瓣全球口碑剧集榜等，条目带名次数字 | 全页约 90 个 /play/ 链接，79KB | 〔实测 billboard〕 |
| 片单列表 | `/kanlist/{主题}-p-{n}.html`，主题：全部/剧情/励志/喜剧/复仇/恐怖/悬疑/情感/治愈/漫威/爱情/甜蜜/科幻/获奖… | 服务端渲染；条目不直达播放，指向文章页 | — | 〔实测 kanlist〕 |
| 片单详情 | `/article/{数字ID}`（如 /article/120「那些意外结局的电影」） | 服务端渲染 | 约 20 个 /play/ 链接 | 〔实测 article/120〕 |
| 分类 | `/category/` = 最新入库 feed；`/category/{id}` 子类：1=电影、2=剧集、18=动漫、19=综艺、20=纪录片 | 服务端渲染 | 48（/category/ 首页样本） | 〔实测 category〕 |
| 分类翻页 | `/category/?p={n}`（**查询参数式**，与 /hot/ 的路径式不同） | 同上 | — | 〔源码 category 页内链接〕 |
| 搜索 | `GET /search?q={词}`，翻页 `&p={n}&n={游标}` | 服务端渲染（表单 GET 提交） | 20 | 〔源码 search.html〕 |
| 播放 | `/play/{数字ID}`（详情+播放合一） | 服务端渲染外壳 + getResN 异步装线路 | — | 〔实测〕 |
| 播放历史 | `/history.html` | **纯前端渲染**：读 localStorage `playHistory` 生成列表，无服务端数据 | 最多 10 条 | 〔实测 history + 内联脚本〕 |
| 广告跳转 | `/go/as`、`/go/mtag`、`/go/cn1`、`/go/cn2` | common.js 按权重/地区挑选后 window.open（本次未访问，符合只读边界） | — | 〔源码 common.js〕 |

### 2.2 /hot/ 标签体系

- movie 标签（样本）：热门、最新、华语、欧美、日本、动作、喜剧、动画、恐怖、悬疑、冷门佳片…
- tv 标签（样本）：热门、国产剧、港剧、韩剧、日剧、美剧、英剧、日本动画、综艺、纪录片…
- 规律统一：`index-{movie|tv}-{标签}`；标签即中文词，无 ID 化。TV 端若原生化列表，标签清单需硬编码或从页内导航提取（导航里有全集链接）。

### 2.3 搜索分页游标 `n`

- 第 1 页链接无 `n`；第 2 页起链接形如 `/search?q=庆余年&p=2&n=Q1UBABl…`。
- `n` 为 base64 包装的不透明游标，解码后可见明文字段名 `score_desc|aka_count_desc` 及二进制数值——是搜索引擎侧的排序游标（search-after 式），**不可自
...[truncated 9252 chars]