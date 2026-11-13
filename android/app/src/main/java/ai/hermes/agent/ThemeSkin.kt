package ai.hermes.agent

import android.content.Context
import android.webkit.WebView

/**
 * 兼容入口：皮肤 + 桥接脚本的注入已统一收敛到 [WebBridge]。
 * 保留这个名字是为了让外部旧调用点（若有）仍然可用，行为完全一致。
 *
 * 想恢复原版界面：把 assets/hermes-skin.css 清空即可（空内容会移除注入的
 * style），桥接脚本与功能不受影响。
 */
object ThemeSkin {
    fun apply(webView: WebView, context: Context) = WebBridge.install(webView, context)
}
