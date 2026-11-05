/* === 爱看电视 TV 模式脚本：遥控器方向键空间导航 ===
   思路：不跟站点的鼠标逻辑较劲，只管三件事
   1) 让所有能点的东西（包括选集/线路这种可点击 div）都能获得焦点
   2) 方向键按下时，按几何位置找最近的可聚焦元素并聚焦+滚动到位
   3) OK 键对 div 类元素补发 click；播放页自动把焦点送到大播放按钮 */
(function () {
  if (window.__tvModeInstalled) return;
  window.__tvModeInstalled = true;
  document.documentElement.classList.add('tv-mode');

  var CLICKABLE_DIVS = '#video-list .item, #lineContent div[name="lineData"], .line-res div';
  var FOCUSABLE_SEL = 'a[href], button, input, select, textarea, video, ' +
    '.vjs-big-play-button, .vjs-play-control, .vjs-fullscreen-control, ' +
    CLICKABLE_DIVS + ', [tabindex]';
  var lastFocused = null;

  function isVisible(el) {
    if (!el || el === document.body) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    var s = window.getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;
    return true;
  }

  function prepare() {
    var divs = document.querySelectorAll(CLICKABLE_DIVS);
    for (var i = 0; i < divs.length; i++) {
      if (!divs[i].hasAttribute('tabindex')) divs[i].setAttribute('tabindex', '0');
    }
    loadPosters();
  }

  /* 海报自救：站点 common.js 用了老内核（Chrome<80）解析不了的新语法（??），
     整文件失效导致它自带的懒加载永不初始化、海报永远空白。
     这里按站点原本的规则自己加载：豆瓣图走它的图片代理，失败再直连。 */
  function b64url(str) {
    return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  function loadPosters() {
    var imgs = document.querySelectorAll('img[data-src]');
    for (var i = 0; i < imgs.length; i++) {
      (function (el) {
        if (el.__tvLoaded) return;
        el.__tvLoaded = true;
        var raw = el.getAttribute('data-src');
        if (!raw) return;
        el.onerror = function () {
          el.onerror = null;
          if (el.src !== raw) el.src = raw; /* 代理失败则直连原图 */
        };
        if (raw.indexOf('doubanio.com') > -1 && el.id) {
          el.src = 'https://imgp.ikanbot.eu.org/i/' + el.id + '?u=' + b64url(raw);
        } else {
          el.src = raw;
        }
      })(imgs[i]);
    }
  }

  function focusables() {
    prepare();
    var nodes = document.querySelectorAll(FOCUSABLE_SEL);
    var out = [];
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.disabled) continue;
      if (el.getAttribute('tabindex') === '-1' && el.tagName !== 'VIDEO') continue;
      if (isVisible(el)) out.push(el);
    }
    return out;
  }

  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  /* 底部固定导航：它不在文档流里，纯几何导航永远「够不着」（会被中间内容
     一直抢走，或滚到底后它在当前元素上方）。业界做法是边界跳转：
     内容区按「下」越过最后一屏 → 直接跳进导航栏；导航栏里按「上」→ 回到刚才的内容。 */
  var lastContentFocus = null;
  function inNav(el) {
    return !!(el && el.closest && el.closest('.navbar-fixed-bottom'));
  }

  function move(dir) {
    var cur = (document.activeElement && document.activeElement !== document.body)
      ? document.activeElement : lastFocused;
    var items = focusables();
    if (!items.length) return false;
    if (!cur || items.indexOf(cur) === -1) {
      focusEl(items[0]);
      return true;
    }
    if (inNav(cur)) {
      if (dir === 'down') return true;              /* 已在导航栏，最底了，吞掉按键免得背后页面乱滚 */
      if (dir === 'up') {
        if (lastContentFocus && document.contains(lastContentFocus)) {
          focusEl(lastContentFocus);
          return true;
        }
      }
    }
    var cr = cur.getBoundingClientRect(), cc = center(cr);
    var best = null, bestScore = Infinity, bestPrimary = Infinity;
    for (var i = 0; i < items.length; i++) {
      var el = items[i];
      if (el === cur) continue;
      var r = el.getBoundingClientRect(), c = center(r);
      var dx = c.x - cc.x, dy = c.y - cc.y;
      var primary, secondary, ok = false;
      if (dir === 'right' && dx > 8) { ok = true; primary = dx; secondary = Math.abs(dy); }
      if (dir === 'left' && dx < -8) { ok = true; primary = -dx; secondary = Math.abs(dy); }
      if (dir === 'down' && dy > 8) { ok = true; primary = dy; secondary = Math.abs(dx); }
      if (dir === 'up' && dy < -8) { ok = true; primary = -dy; secondary = Math.abs(dx); }
      if (!ok) continue;
      /* 主轴距离优先，副轴偏移重罚，近似人眼找焦点的方式 */
      var score = primary + secondary * secondary / Math.max(primary, 40) * 2 + secondary * 0.4;
      if (score < bestScore) { bestScore = score; best = el; bestPrimary = primary; }
    }
    if (best && bestPrimary <= window.innerHeight * 0.9) { focusEl(best); return true; }
    /* 「下」越界：跳进底部导航栏（横向最接近的那一项） */
    if (dir === 'down' && !inNav(cur)) {
      var navLinks = document.querySelectorAll('.navbar-fixed-bottom a');
      var navBest = null, navDx = Infinity;
      for (var n = 0; n < navLinks.length; n++) {
        var nc = center(navLinks[n].getBoundingClientRect());
        var d = Math.abs(nc.x - cc.x);
        if (d < navDx) { navDx = d; navBest = navLinks[n]; }
      }
      if (navBest) { focusEl(navBest); return true; }
    }
    if (best) { focusEl(best); return true; }
    /* 这个方向没有元素了：滚动页面后重试一次（懒加载/长列表） */
    var scrolled = false;
    if (dir === 'down') { window.scrollBy(0, window.innerHeight * 0.7); scrolled = true; }
    if (dir === 'up') { window.scrollBy(0, -window.innerHeight * 0.7); scrolled = true; }
    if (dir === 'right') { window.scrollBy(window.innerWidth * 0.5, 0); scrolled = true; }
    if (dir === 'left') { window.scrollBy(-window.innerWidth * 0.5, 0); scrolled = true; }
    return scrolled;
  }

  function focusEl(el) {
    if (!el) return;
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    lastFocused = el;
    markFocused(el);
    if (inNav(el)) return; /* 固定导航本来就在屏幕上，别让页面跟着滚 */
    /* 用 nearest 而不是 center：只在必要时滚动，首行海报聚焦时页面保持在顶，
       搜索框不会被顶掉一半；底部固定栏会遮挡的量再手动补滚 */
    try { el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
    catch (e) { el.scrollIntoView(false); }
    try {
      var r = el.getBoundingClientRect();
      var limit = window.innerHeight - 84; /* 底部栏高 + 呼吸距 */
      if (r.bottom > limit) window.scrollBy(0, r.bottom - limit);
      else if (r.top < 6) window.scrollBy(0, r.top - 6);
    } catch (e) {}
  }
  window.__tvFocusEl = focusEl;

  /* 类名焦点标记：给元素本身和它所在的海报卡都打上 .tv-focused */
  var lastMarked = [];
  function markFocused(el) {
    for (var i = 0; i < lastMarked.length; i++) lastMarked[i].classList.remove('tv-focused');
    lastMarked = [];
    if (!el || !el.classList) return;
    el.classList.add('tv-focused');
    lastMarked.push(el);
    var card = el.closest ? el.closest('.item') : null;
    if (card && card !== el) { card.classList.add('tv-focused'); lastMarked.push(card); }
  }

  document.addEventListener('focusin', function (e) {
    lastFocused = e.target;
    if (!inNav(e.target)) lastContentFocus = e.target;
    markFocused(e.target);
    /* 记住本页焦点位置：从播放页按返回回到列表时，焦点回到刚才那张海报，
       不用从第一张重新选（sessionStorage 按页面路径分别存） */
    try {
      var el = e.target;
      var key = el.tagName + '|' + (el.getAttribute && (el.getAttribute('href') || el.id || '') || '') +
        '|' + ((el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 40));
      sessionStorage.setItem('tvfocus:' + location.pathname + location.search, key);
    } catch (err) {}
  }, true);

  function restoreFocus() {
    try {
      var key = sessionStorage.getItem('tvfocus:' + location.pathname + location.search);
      if (!key) return null;
      var items = focusables();
      for (var i = 0; i < items.length; i++) {
        var el = items[i];
        var k = el.tagName + '|' + (el.getAttribute && (el.getAttribute('href') || el.id || '') || '') +
          '|' + ((el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 40));
        if (k === key) return el;
      }
    } catch (err) {}
    return null;
  }

  document.addEventListener('keydown', function (e) {
    var k = e.key;
    var dir = null;
    if (k === 'ArrowRight') dir = 'right';
    else if (k === 'ArrowLeft') dir = 'left';
    else if (k === 'ArrowDown') dir = 'down';
    else if (k === 'ArrowUp') dir = 'up';
    if (dir) {
      /* 输入框里左右键要留给移动光标 */
      var tag = document.activeElement && document.activeElement.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA') {
        if (dir === 'left' || dir === 'right') return;
      }
      if (move(dir)) { e.preventDefault(); e.stopPropagation(); }
      return;
    }
    if (k === 'Enter' || k === ' ') {
      var el = document.activeElement;
      if (el && el !== document.body && el.matches && el.matches(CLICKABLE_DIVS)) {
        e.preventDefault();
        el.click();
      }
    }
  }, true);

  /* 页面动态内容（线路/选集异步加载）出现后补 tabindex 并补加载海报 */
  var mo = new MutationObserver(function () { prepare(); });
  function startObserver() {
    if (document.body) mo.observe(document.body, { childList: true, subtree: true });
    loadPosters();
    /* 懒加载图也可能晚到：头 30 秒每 2 秒兜一遍 */
    var n = 0;
    var timer = setInterval(function () {
      loadPosters();
      if (++n > 15) clearInterval(timer);
    }, 2000);
  }
  if (document.body) startObserver();
  else document.addEventListener('DOMContentLoaded', startObserver);

  /* 首焦点：播放页优先大播放按钮；列表页优先第一张海报 */
  window.__tvFocusFirst = function () {
    var tries = 0;
    (function attempt() {
      tries++;
      var target = null;
      if (tries === 1) target = restoreFocus();
      if (!target && location.pathname.indexOf('/play') === 0) {
        target = document.querySelector('.vjs-big-play-button') ||
                 document.querySelector('#lineContent div[name="lineData"]') ||
                 document.querySelector('video');
      }
      if (!target) {
        target = document.querySelector('.row .item a, .v-list .item a, .item a') ||
                 document.querySelector('input[name="q"]');
      }
      if (!target) {
        var all = focusables();
        target = all.length ? all[0] : null;
      }
      if (target && isVisible(target)) { focusEl(target); return; }
      if (tries < 8) setTimeout(attempt, 400);
    })();
  };

  /* 原生层媒体键调用：切换播放/暂停 */
  window.__tvTogglePlay = function () {
    var v = document.querySelector('video');
    if (!v) return;
    if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function () {}); }
    else v.pause();
  };
})();
