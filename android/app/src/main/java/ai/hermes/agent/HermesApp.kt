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
 * 皮肤 / 桥接脚本的兜底注入层。
 *
 * 主注入点在 MainActivity 的 WebViewClient.onPageFinished，这里再用低频轮询
 * 补一次：SPA 整页跳转、页面被系统回收后重建、或者首帧注入时机过早时，
 * 都能自动恢复。install() 是幂等的（脚本带版本号、CSS 内容相同就不重写），
 * 重复执行不会叠加监听器或触发样式重排。
 *
 * 零侵入：不触碰 MainActivity 的视图结构、路由、事件与 WebViewClient。
 */
class HermesApp : Application() {

    private val handler = Handler(Looper.getMainLooper())
    private var resumed: Activity? = null

    private val pump = object : Runnable {
        override fun run() {
            val activity = resumed
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                val webView = findWebView(activity.window.decorView)
                val url = webView?.url
                if (webView != null && url != null &&
                    (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) &&
                    webView.progress >= 100
                ) {
                    WebBridge.install(webView, this@HermesApp)
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
        const val POLL_MS = 2000L
    }
}
