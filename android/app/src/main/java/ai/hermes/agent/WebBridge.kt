package ai.hermes.agent

import android.content.Context
import android.webkit.WebView
import org.json.JSONObject

/**
 * 原生壳与网页面板之间的唯一注入口。
 *
 * 每次页面加载完成（以及 Application 层的低频轮询）都会调用 install()，
 * 脚本本身幂等：CSS 用 style#hermes-android-skin 复用，JS 用 __v 版本号去重。
 * 注入内容只有两件事：一层 CSS、一组挂在 window.HermesAndroid 上的辅助函数。
 * 不改 DOM 结构、不覆盖 React 组件、不拦截 fetch，面板原有功能全部保留。
 */
object WebBridge {

    private const val STYLE_ID = "hermes-android-skin"
    private const val ASSET_CSS = "hermes-skin.css"
    private const val ASSET_JS = "hermes-bridge.js"

    @Volatile private var cssCache: String? = null
    @Volatile private var jsCache: String? = null

    /** 幂等注入 CSS + 桥接脚本。 */
    fun install(webView: WebView, context: Context) {
        val js = asset(context, ASSET_JS, isJs = true)
        val css = asset(context, ASSET_CSS, isJs = false)
        val script = buildString {
            append(js)
            append("\n(function(){var id='").append(STYLE_ID).append("';")
            append("var e=document.getElementById(id);")
            if (css.isBlank()) {
                append("if(e)e.remove();")
            } else {
                append("if(!e){e=document.createElement('style');e.id=id;")
                append("(document.head||document.documentElement).appendChild(e);}")
                val quoted = JSONObject.quote(css)
                append("if(e.textContent!==").append(quoted).append(")e.textContent=")
                append(quoted).append(";")
            }
            append("})();")
        }
        runCatching { webView.evaluateJavascript(script, null) }
    }

    /** 点击底部 Tab：优先用面板自己的 <a href>，失败才整页跳转。 */
    fun navigate(webView: WebView, path: String) {
        val quoted = JSONObject.quote(path)
        runCatching {
            webView.evaluateJavascript(
                "window.HermesAndroid&&window.HermesAndroid.nav($quoted)", null,
            )
        }
        // 页面尚未注入（例如首次加载还没完成）时兜底。
        webView.postDelayed({
            if (!isLocal(webView)) return@postDelayed
            runCatching {
                webView.evaluateJavascript("location.pathname") { value ->
                    val current = value?.trim('"')?.trim().orEmpty()
                    val matches = current == path ||
                        current == "$path/" || current.startsWith("$path/")
                    if (current.isNotEmpty() && !matches) {
                        webView.loadUrl("http://127.0.0.1:${Runtime.port}$path")
                    }
                }
            }
        }, 700)
    }

    /** 把原生新建的工作区写进面板存储，返回写入的键数量。 */
    fun setWorkspace(webView: WebView, path: String, onDone: (Int) -> Unit = {}) {
        val quoted = JSONObject.quote(path)
        runCatching {
            webView.evaluateJavascript(
                "window.HermesAndroid?window.HermesAndroid.setWorkspace($quoted):0",
            ) { value -> onDone(value?.toIntOrNull() ?: 0) }
        }.onFailure { onDone(0) }
    }

    /**
     * 「写入工作区 + 跳到 /chat」必须是一次 JS 调用里的同步顺序：
     * 如果先 evaluateJavascript 再 loadUrl，导航会打断尚未执行完的脚本，
     * localStorage 就写不进去。这里把两步合成一个原子脚本，并内置一份
     * fallback（页面还没注入桥接时也能写）。
     */
    fun openWorkspace(webView: WebView, path: String) {
        val quoted = JSONObject.quote(path)
        val script = """
            (function(){
              var p=$quoted, prefix='hermes-chat-workspace:', keys={};
              keys[prefix]=1;
              try{
                for(var i=0;i<localStorage.length;i++){
                  var k=localStorage.key(i);
                  if(k&&k.indexOf(prefix)===0) keys[k]=1;
                }
                var cs=[window.__HERMES_INITIAL_PROFILE__,
                        window.__HERMES_DASHBOARD_PROFILE__,'default','main'];
                for(var j=0;j<cs.length;j++){
                  var v=(cs[j]||'').trim(); if(v) keys[prefix+v]=1;
                }
                Object.keys(keys).forEach(function(k){
                  try{localStorage.setItem(k,p);}catch(e){}
                });
              }catch(e){}
              var base=window.__HERMES_BASE_PATH__||'';
              if(base.charAt(base.length-1)==='/') base=base.slice(0,-1);
              location.href=base+'/chat';
              return Object.keys(keys).length;
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(script, null) }
    }

    fun currentRoute(webView: WebView, onResult: (String) -> Unit) {
        runCatching {
            webView.evaluateJavascript(
                "window.HermesAndroid?window.HermesAndroid.route():location.pathname",
            ) { value -> onResult(value?.trim('"')?.trim().orEmpty()) }
        }.onFailure { onResult("") }
    }

    fun reload(webView: WebView) {
        runCatching { webView.evaluateJavascript("location.reload()", null) }
    }

    private fun isLocal(webView: WebView): Boolean {
        val url = webView.url ?: return false
        return url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
    }

    private fun asset(context: Context, name: String, isJs: Boolean): String {
        val cache = if (isJs) jsCache else cssCache
        cache?.let { return it }
        val text = runCatching {
            context.assets.open(name).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        if (isJs) jsCache = text else cssCache = text
        return text
    }
}
