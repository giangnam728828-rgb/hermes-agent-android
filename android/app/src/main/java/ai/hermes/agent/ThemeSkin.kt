package ai.hermes.agent

import android.content.Context
import android.webkit.WebView

/**
 * 只往页面注入一段 CSS（或移除它），不触碰 DOM 结构、事件、路由、网络请求。
 *
 * 上游面板是 token 化样式：--background / --midground / --theme-* / --radius
 * 等变量驱动整体视觉，覆盖这些 token 即可换肤，功能一个不少。
 * 想恢复原版界面：把 assets/hermes-skin.css 清空即可（空内容会移除注入的 style）。
 */
object ThemeSkin {

    private const val STYLE_ID = "hermes-android-skin"
    private const val ASSET = "hermes-skin.css"

    @Volatile
    private var cachedCss: String? = null

    fun apply(webView: WebView, context: Context) {
        val css = loadCss(context)
        val script = if (css.isBlank()) {
            "(function(){var e=document.getElementById('$STYLE_ID');if(e)e.remove();})();"
        } else {
            val quoted = org.json.JSONObject.quote(css)
            "(function(){var id='$STYLE_ID';var e=document.getElementById(id);" +
                "if(!e){e=document.createElement('style');e.id=id;" +
                "(document.head||document.documentElement).appendChild(e);}" +
                "e.textContent=$quoted;})();"
        }
        runCatching { webView.evaluateJavascript(script, null) }
    }

    private fun loadCss(context: Context): String {
        cachedCss?.let { return it }
        val css = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("")
        cachedCss = css
        return css
    }
}
