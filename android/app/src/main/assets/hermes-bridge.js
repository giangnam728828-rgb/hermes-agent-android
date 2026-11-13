/* ============================================================
   Hermes Agent · Android 原生壳 <-> 网页面板桥接
   幂等注入：每次页面加载 / 轮询都会执行，重复调用不会叠加监听器。
   原则：只调用面板已有的 <a href> 与 localStorage 键，不改 DOM 结构、
   不覆盖 React 组件、不拦截网络请求，功能一个不少。
   ============================================================ */
(function () {
  if (window.HermesAndroid && window.HermesAndroid.__v === 3) return;

  function base() {
    var b = window.__HERMES_BASE_PATH__ || "";
    return b.replace(/\/$/, "");
  }

  var HA = {
    __v: 3,

    /** 用面板自己的导航链接切页（SPA 路由，不重载、不丢状态）。 */
    nav: function (path) {
      var full = base() + path;
      try {
        var links = document.querySelectorAll("a[href]");
        for (var i = 0; i < links.length; i++) {
          var href = links[i].getAttribute("href") || "";
          var clean = href.split("?")[0].split("#")[0].replace(/\/$/, "");
          if (clean === full || href === path || clean === path) {
            links[i].click();
            return true;
          }
        }
      } catch (e) { /* 落到下面的兜底 */ }

      try {
        if (location.pathname !== full) {
          history.pushState({}, "", full);
          window.dispatchEvent(new PopStateEvent("popstate", { state: {} }));
        }
      } catch (e) { /* 落到下面的兜底 */ }

      // 侧栏在窄屏下可能没渲染，300ms 后若路由没变就整页跳转兜底。
      var target = full;
      setTimeout(function () {
        if (location.pathname !== target) location.href = target;
      }, 300);
      return true;
    },

    route: function () { return location.pathname || "/"; },

    reload: function () { location.reload(); },

    /**
     * 把原生创建的工作区路径写进面板的工作区存储键。
     * 键名格式 hermes-chat-workspace:<profile>，profile 由服务端注入到
     * window.__HERMES_*_PROFILE__；把已知的和默认的都写上，保证无论当前
     * profile 是什么都能命中。
     */
    setWorkspace: function (p) {
      var prefix = "hermes-chat-workspace:";
      var keys = {};
      keys[prefix] = 1;
      try {
        for (var i = 0; i < localStorage.length; i++) {
          var k = localStorage.key(i);
          if (k && k.indexOf(prefix) === 0) keys[k] = 1;
        }
      } catch (e) { /* 隐私模式 */ }

      var candidates = [
        window.__HERMES_INITIAL_PROFILE__,
        window.__HERMES_DASHBOARD_PROFILE__,
        "default", "main", "primary"
      ];
      for (var j = 0; j < candidates.length; j++) {
        var v = (candidates[j] || "").trim();
        if (v) keys[prefix + v] = 1;
      }

      var n = 0;
      Object.keys(keys).forEach(function (k) {
        try { localStorage.setItem(k, p); n++; } catch (e) { /* ignore */ }
      });
      return n;
    },

    /** 读取面板当前选中的工作区，用于原生侧显示。 */
    workspace: function () {
      try {
        var prefix = "hermes-chat-workspace:";
        var profs = [
          window.__HERMES_INITIAL_PROFILE__,
          window.__HERMES_DASHBOARD_PROFILE__, "default", ""
        ];
        for (var i = 0; i < profs.length; i++) {
          var v = localStorage.getItem(prefix + (profs[i] || "").trim());
          if (v) return v;
        }
        for (var j = 0; j < localStorage.length; j++) {
          var k = localStorage.key(j);
          if (k && k.indexOf(prefix) === 0) {
            var x = localStorage.getItem(k);
            if (x) return x;
          }
        }
      } catch (e) { /* ignore */ }
      return "";
    }
  };

  window.HermesAndroid = HA;
})();
