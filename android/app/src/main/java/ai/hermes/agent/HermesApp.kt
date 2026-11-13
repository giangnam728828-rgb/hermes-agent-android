package ai.hermes.agent

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/**
 * 工作台皮肤的接线层。
 *
 * 刻意不改动 MainActivity（877 行的两页 UI 与 WebView 配置），改用 Application
 * 生命周期回调在视图树里找到 WebView，页面加载完成后注入一层 CSS。零侵入、
 * 零回归风险：MainActivity 的逻辑、路由、事件、WebViewClient 全都不被触碰。
 * 皮肤只覆盖视觉 token，聊天 / 会话 / 技能 / 定时 / MCP / 配置 / KEYS 全部保留。
 */
class HermesApp : Application() {

    private val handler = Handler(Looper.getMainLooper())
    private var resumed: Activity? = null
    private var injectedUrl: String? = null

    private val pump = object : Runnable {
        override fun run() {
            val activity = resumed
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val webView = findWebView(activity.window.decorView)
                val url = webView?.url
                if (webView != null && url != null &&
                    url.startsWith("http://127.0.0.1") && webView.progress >= 100
                ) {
                    // 同一个页面只注入一次；SPA 内部换路由不会移除 head 里的 style。
                    if (url != injectedUrl) {
                        ThemeSkin.apply(webView, this@HermesApp)
                        injectedUrl = url
                    }
                }
            }
            if (resumed != null) handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) {
                if (a is MainActivity) {
                    resumed = a
                    handler.removeCallbacks(pump)
                    handler.post(pump)
                }
            }
            override fun onActivityPaused(a: Activity) {
                if (a === resumed) {
                    resumed = null
                    handler.removeCallbacks(pump)
                }
            }
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findWebView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private companion object {
        const val POLL_MS = 1500L
    }
}
