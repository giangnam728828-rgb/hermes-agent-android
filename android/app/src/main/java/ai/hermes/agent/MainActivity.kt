package ai.hermes.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 两页结构：启动页（状态 + 日志 + 入口）和全屏工作台（Hermes 自带 Web 面板）。
 * 刻意不自动跳进工作台——用户先看到「是否真的起来了」，再自己点进去。
 */
class MainActivity : Activity() {

    private lateinit var container: FrameLayout
    private lateinit var launcherPage: ScrollView
    private lateinit var webHost: FrameLayout
    private lateinit var webView: WebView

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var portChip: TextView
    private lateinit var primaryButton: TextView
    private lateinit var secondaryRow: LinearLayout
    private lateinit var logPanel: LinearLayout
    private lateinit var logText: TextView
    private lateinit var logToggle: TextView
    private lateinit var progressTrack: View
    private lateinit var progressFill: View

    private var workspaceVisible = false
    private var logsExpanded = false
    private var lastLogVersion = -1
    private var lastPhase: Runtime.Phase? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            mainHandler.postDelayed(this, 1200)
        }
    }

    private val runtimeListener: () -> Unit = {
        runOnUiThread { render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        Paths.ensureDirs(this)
        buildUi()
        Runtime.addListener(runtimeListener)
        requestNotificationPermission()
        maybeStartRuntime()
        render()
    }

    // ---------------------------------------------------------------- UI 构建

    private fun buildUi() {
        container = FrameLayout(this)
        container.setBackgroundColor(C.BG)

        launcherPage = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(38), dp(22), dp(28))
        }
        launcherPage.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        column.addView(buildBrand())
        column.addView(space(30))
        column.addView(buildStatusCard())
        column.addView(space(20))
        column.addView(buildPrimaryButton())
        column.addView(space(10))
        column.addView(buildSecondaryRow())
        column.addView(space(22))
        column.addView(buildLogSection())
        column.addView(space(18))
        column.addView(buildFooter())

        webHost = FrameLayout(this).apply {
            setBackgroundColor(C.BG)
            visibility = View.GONE
        }
        webView = WebView(this)
        configureWebView()
        webHost.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        container.addView(launcherPage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        container.addView(webHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        setContentView(container)
    }

    private fun buildBrand(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(TextView(this).apply {
            text = "NOUS  RESEARCH"
            textSize = 10f
            letterSpacing = 0.32f
            setTextColor(C.ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        wrap.addView(space(8))
        wrap.addView(TextView(this).apply {
            text = "Hermes Agent"
            textSize = 31f
            letterSpacing = -0.02f
            setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        wrap.addView(space(6))
        wrap.addView(TextView(this).apply {
            text = "会自己长出技能的智能体 · 手机上直接跑"
            textSize = 13f
            setTextColor(C.MUTED)
        })
        return wrap
    }

    private fun buildStatusCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, 20)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = circle(C.MUTED, 8)
        }
        header.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)))
        header.addView(spaceH(10))
        statusTitle = TextView(this).apply {
            text = "尚未启动"
            textSize = 17f
            setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        header.addView(statusTitle)
        header.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        portChip = TextView(this).apply {
            text = "—"
            textSize = 11f
            setTextColor(C.MUTED)
            background = rounded(C.SURFACE_HI, 8)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        header.addView(portChip)
        card.addView(header)

        card.addView(space(10))
        statusDetail = TextView(this).apply {
            text = "点下面的按钮启动本地服务"
            textSize = 12.5f
            setTextColor(C.MUTED)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        card.addView(statusDetail)

        val track = FrameLayout(this).apply {
            background = rounded(C.TRACK, 3)
            visibility = View.GONE
        }
        progressFill = View(this).apply {
            background = rounded(C.ACCENT, 3)
        }
        track.addView(progressFill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        progressTrack = track
        card.addView(space(14))
        card.addView(track, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(4),
        ))
        return card
    }

    private fun buildPrimaryButton(): View {
        primaryButton = TextView(this).apply {
            text = "启动 Hermes"
            textSize = 15.5f
            gravity = Gravity.CENTER
            setTextColor(C.BG)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(C.ACCENT, 16)
            isClickable = true
            setPadding(0, dp(15), 0, dp(15))
            setOnClickListener { onPrimary() }
            EtaMotion.press(this@MainActivity, this)
        }
        return primaryButton
    }

    private fun buildSecondaryRow(): View {
        secondaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        secondaryRow.addView(secondaryAction("设置") { showSettings() }, weight())
        secondaryRow.addView(spaceH(10))
        secondaryRow.addView(secondaryAction("日志") { toggleLogs() }, weight())
        secondaryRow.addView(spaceH(10))
        secondaryRow.addView(secondaryAction("重启") {
            if (Runtime.phase == Runtime.Phase.READY) {
                toast("正在重启本地服务")
                RuntimeService.restart(this)
            } else {
                toast("服务还没跑起来")
            }
        }, weight())
        return secondaryRow
    }

    private fun secondaryAction(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(C.TEXT)
            background = rounded(C.SURFACE, 13)
            isClickable = true
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { onClick() }
            EtaMotion.press(this@MainActivity, this)
        }

    private fun buildLogSection(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logToggle = TextView(this).apply {
            text = "运行日志  ›"
            textSize = 12.5f
            setTextColor(C.MUTED)
            setPadding(dp(2), dp(4), dp(2), dp(10))
            setOnClickListener { toggleLogs() }
        }
        wrap.addView(logToggle)

        logPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        logText = TextView(this).apply {
            textSize = 10.5f
            setTextColor(C.LOG_TEXT)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
            text = "（暂无日志）"
        }
        val logScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(logText)
        }
        logPanel.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(190),
        ))
        wrap.addView(logPanel)
        return wrap
    }

    private fun buildFooter(): View =
        TextView(this).apply {
            text = "内置 Ubuntu 根环境 · Python 3.12 · 数据只留在本机"
            textSize = 10.5f
            setTextColor(C.FAINT)
            gravity = Gravity.CENTER
        }

    // ------------------------------------------------------------ WebView 配置

    private fun configureWebView() {
        webView.setBackgroundColor(C.BG)
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(
                consoleMessage: android.webkit.ConsoleMessage?,
            ): Boolean {
                consoleMessage?.let { Runtime.log("[web] ${it.message()}") }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                if (isLocal(uri)) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Runtime.log("页面已加载：${url ?: ""}")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    Runtime.log("页面加载失败：${error?.description ?: ""}")
                }
            }
        }
    }

    private fun isLocal(uri: Uri): Boolean =
        uri.host == "127.0.0.1" || uri.host == "localhost"

    // ---------------------------------------------------------------- 页面切换

    private fun onPrimary() {
        when (Runtime.phase) {
            Runtime.Phase.READY -> enterWorkspace()
            Runtime.Phase.INSTALLING, Runtime.Phase.STARTING -> toast("正在准备，请稍候")
            else -> {
                RuntimeService.start(this)
                toast("正在启动…")
            }
        }
    }

    private fun enterWorkspace() {
        val url = Runtime.workspaceUrl.ifBlank { "http://127.0.0.1:${Runtime.port}/" }
        if (webView.url == null || !webView.url!!.startsWith("http://127.0.0.1")) {
            webView.loadUrl(url)
        }
        workspaceVisible = true
        webHost.visibility = View.VISIBLE
        EtaMotion.enter(this, webHost, fromDp = 18f)
        launcherPage.animate().alpha(0f).setDuration(180).start()
        mainHandler.postDelayed({ launcherPage.visibility = View.GONE }, 180)
        applyImmersive(true)
    }

    private fun exitWorkspace() {
        workspaceVisible = false
        applyImmersive(false)
        launcherPage.visibility = View.VISIBLE
        EtaMotion.enter(this, launcherPage, fromDp = 0f)
        webHost.animate().alpha(0f).setDuration(180)
            .withEndAction { webHost.visibility = View.GONE }.start()
        render()
    }

    private fun applyImmersive(on: Boolean) {
        if (on) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            window.statusBarColor = C.BG
            window.navigationBarColor = C.BG
        } else {
            window.statusBarColor = C.BG
            window.navigationBarColor = C.SURFACE
        }
    }

    // -------------------------------------------------------------------- 渲染

    private fun render() {
        val phase = Runtime.phase
        statusTitle.text = Runtime.title
        statusDetail.text = Runtime.detail.ifBlank {
            when (phase) {
                Runtime.Phase.IDLE -> "点下面的按钮启动本地服务"
                Runtime.Phase.STOPPED -> "服务已停止"
                Runtime.Phase.READY -> "Hermes 面板已就绪"
                else -> ""
            }
        }
        statusDot.background = circle(
            when (phase) {
                Runtime.Phase.READY -> C.OK
                Runtime.Phase.ERROR -> C.ERR
                Runtime.Phase.INSTALLING, Runtime.Phase.STARTING -> C.WARN
                else -> C.MUTED
            },
            8,
        )

        if (phase == Runtime.Phase.READY && Runtime.port > 0) {
            portChip.visibility = View.VISIBLE
            portChip.text = "127.0.0.1:${Runtime.port}"
        } else {
            portChip.visibility = View.GONE
        }

        val progress = Runtime.progress
        if (progress in 0..100) {
            progressTrack.visibility = View.VISIBLE
            val width = (progressTrack.width * progress / 100f).toInt()
            progressFill.layoutParams = FrameLayout.LayoutParams(
                if (width > 0) width else 0, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            progressFill.requestLayout()
        } else {
            progressTrack.visibility = View.GONE
        }

        primaryButton.text = when (phase) {
            Runtime.Phase.READY -> "进入工作台"
            Runtime.Phase.INSTALLING -> "正在安装…"
            Runtime.Phase.STARTING -> "正在启动…"
            Runtime.Phase.ERROR -> "重试"
            else -> "启动 Hermes"
        }
        primaryButton.background = rounded(
            if (phase == Runtime.Phase.READY) C.ACCENT else C.ACCENT_DIM,
            16,
        )

        if (logsExpanded && lastLogVersion != Runtime.logVersion) {
            lastLogVersion = Runtime.logVersion
            val lines = Runtime.snapshotLogs()
            logText.text = if (lines.isEmpty()) "（暂无日志）" else lines.takeLast(120).joinToString("\n")
            (logText.parent as? View)?.post {
                (logText.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
            }
        }

        if (lastPhase != phase) {
            lastPhase = phase
            if (phase == Runtime.Phase.READY) EtaMotion.pop(this, statusDot)
        }
    }

    private fun toggleLogs() {
        logsExpanded = !logsExpanded
        logPanel.visibility = if (logsExpanded) View.VISIBLE else View.GONE
        logToggle.text = if (logsExpanded) "运行日志  ⌄" else "运行日志  ›"
        lastLogVersion = -1
        if (logsExpanded) {
            EtaMotion.enter(this, logPanel, fromDp = 8f)
            render()
        }
    }

    // -------------------------------------------------------------------- 设置

    private fun showSettings() {
        EtaSheet.show(this, getString(R.string.settings_title), tall = true) { sheet ->
            sheet.body.addView(sectionLabel("连接"))
            sheet.body.addView(space(10))
            sheet.body.addView(portRow(sheet))
            sheet.body.addView(space(10))
            sheet.body.addView(workspaceRow(sheet))

            sheet.body.addView(space(22))
            sheet.body.addView(sectionLabel("运行"))
            sheet.body.addView(space(10))
            sheet.body.addView(toggleRow("局域网访问", "让同一 Wi-Fi 下的电脑也能打开面板", Prefs.lan(this)) {
                Prefs.setLan(this, it)
                if (Runtime.phase == Runtime.Phase.READY) {
                    toast("重启后生效")
                }
            })
            sheet.body.addView(space(10))
            sheet.body.addView(toggleRow("全自主执行", "读写文件、跑命令不再逐条询问", Prefs.yolo(this)) {
                Prefs.setYolo(this, it)
                toast("重启后生效")
            })
            sheet.body.addView(space(10))
            sheet.body.addView(toggleRow("保持唤醒", "前台服务运行时不锁屏休眠", Prefs.keepAwake(this)) {
                Prefs.setKeepAwake(this, it)
                applyKeepAwake()
            })

            sheet.body.addView(space(22))
            sheet.body.addView(sectionLabel("维护"))
            sheet.body.addView(space(10))
            sheet.body.addView(actionRow("导出日志") { exportLogs() })
            sheet.body.addView(space(10))
            sheet.body.addView(actionRow("重新安装运行环境", danger = true) {
                sheet.dismiss()
                confirmReinstall()
            })

            val (row, _) = EtaSheet.dualButtons(
                this, getString(R.string.action_cancel), "完成",
                onCancel = { sheet.dismiss() },
                onConfirm = { sheet.dismiss() },
            )
            sheet.footer.addView(row)
        }
    }

    private fun portRow(sheet: EtaSheet): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        wrap.addView(rowTitle("面板端口"))
        wrap.addView(space(4))
        wrap.addView(rowCaption("改端口会重置网页端的本地缓存，建议保持不变"))
        wrap.addView(space(10))
        val input = EditText(this).apply {
            setText(Prefs.port(this@MainActivity).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            setTextColor(C.TEXT)
            setHintTextColor(C.FAINT)
            background = rounded(C.BG, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        wrap.addView(input)
        wrap.addView(space(10))
        val save = TextView(this).apply {
            text = "保存端口"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(C.ACCENT)
            background = rounded(C.SURFACE_HI, 10)
            setPadding(0, dp(9), 0, dp(9))
            setOnClickListener {
                val value = input.text.toString().toIntOrNull()
                if (value == null || value !in 1024..65535) {
                    toast("端口要在 1024–65535 之间")
                } else {
                    Prefs.setPort(this@MainActivity, value)
                    toast("已保存，重启后生效")
                }
            }
        }
        wrap.addView(save)
        return wrap
    }

    private fun workspaceRow(sheet: EtaSheet): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        wrap.addView(rowTitle("工作目录"))
        wrap.addView(space(4))
        wrap.addView(rowCaption("Agent 读写的真实目录，放在手机存储里才能在文件管理器看到"))
        wrap.addView(space(10))
        val input = EditText(this).apply {
            setText(Prefs.workspace(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 13f
            setTextColor(C.TEXT)
            background = rounded(C.BG, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        wrap.addView(input)
        wrap.addView(space(10))
        wrap.addView(TextView(this).apply {
            text = "保存目录"
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(C.ACCENT)
            background = rounded(C.SURFACE_HI, 10)
            setPadding(0, dp(9), 0, dp(9))
            setOnClickListener {
                Prefs.setWorkspace(this@MainActivity, input.text.toString())
                toast("已保存，重启后生效")
            }
        })
        return wrap
    }

    private fun toggleRow(title: String, caption: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.SURFACE, 14)
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(rowTitle(title))
        texts.addView(space(3))
        texts.addView(rowCaption(caption))
        wrap.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        var value = initial
        val switch = FrameLayout(this).apply {
            background = rounded(if (value) C.ACCENT else C.TRACK, 16)
            isClickable = true
            EtaMotion.press(this@MainActivity, this)
        }
        val dot = View(this).apply {
            background = circle(if (value) C.BG else C.MUTED, 11)
        }
        switch.addView(dot, FrameLayout.LayoutParams(dp(18), dp(18)).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = if (value) dp(26) else dp(4)
        })
        switch.setOnClickListener {
            value = !value
            (it as FrameLayout).background = rounded(if (value) C.ACCENT else C.TRACK, 16)
            (dot.layoutParams as FrameLayout.LayoutParams).leftMargin = if (value) dp(26) else dp(4)
            dot.requestLayout()
            EtaMotion.pop(this@MainActivity, switch)
            onChange(value)
        }
        wrap.addView(switch, LinearLayout.LayoutParams(dp(48), dp(26)))

        return wrap
    }

    private fun actionRow(title: String, danger: Boolean = false, onClick: () -> Unit): View =
        TextView(this).apply {
            text = title
            textSize = 13.5f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(if (danger) C.ERR else C.TEXT)
            background = rounded(C.SURFACE, 14)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            isClickable = true
            setOnClickListener { onClick() }
            EtaMotion.press(this@MainActivity, this)
        }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 10.5f
        letterSpacing = 0.18f
        setTextColor(C.ACCENT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(4), 0, 0, 0)
    }

    private fun rowTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14.5f
        setTextColor(C.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun rowCaption(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(C.MUTED)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun confirmReinstall() {
        EtaSheet.show(this, "重新安装运行环境", tall = false) { sheet ->
            sheet.body.addView(TextView(this).apply {
                text = "会清空容器内的 Python 环境和已装的技能，但保留手机存储里的工作目录。" +
                    "下次启动重新解压，约两三分钟。"
                textSize = 13f
                setTextColor(C.MUTED)
                setLineSpacing(dp(3).toFloat(), 1f)
            })
            val (row, confirm) = EtaSheet.dualButtons(
                this, getString(R.string.action_cancel), "重新安装",
                onCancel = { sheet.dismiss() },
                onConfirm = {
                    sheet.dismiss()
                    RuntimeService.stop(this)
                    Thread {
                        runCatching { Thread.sleep(1200) }
                        runCatching { Paths.rootfs(this).deleteRecursively() }
                        runCatching { File(Paths.rootfs(this), ".installed").delete() }
                        runOnUiThread {
                            toast("已清理，正在重装")
                            RuntimeService.start(this)
                        }
                    }.apply { isDaemon = true }.start()
                },
            )
            confirm.setTextColor(C.ERR)
            sheet.footer.addView(row)
        }
    }

    private fun exportLogs() {
        runCatching {
            val file = File(Paths.logsDir(this), "hermes-log-${System.currentTimeMillis()}.txt")
            file.parentFile?.mkdirs()
            file.writeText(buildString {
                appendLine("Hermes Agent Android")
                appendLine("phase=${Runtime.phase} port=${Runtime.port}")
                appendLine("workspace=${Prefs.workspace(this@MainActivity)}")
                appendLine("---")
                Runtime.snapshotLogs().forEach { appendLine(it) }
            })
            toast("日志已保存：${file.absolutePath}")
        }.onFailure { toast("导出失败：${it.message}") }
    }

    // -------------------------------------------------------------------- 生命周期

    private fun maybeStartRuntime() {
        if (Runtime.phase == Runtime.Phase.IDLE || Runtime.phase == Runtime.Phase.STOPPED) {
            RuntimeService.start(this)
        }
    }

    private fun applyKeepAwake() {
        if (Prefs.keepAwake(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                runCatching {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
                }
            }
        }
        if (Build.VERSION.SDK_INT <= 29) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                runCatching {
                    requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 72)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyKeepAwake()
        Runtime.addListener(runtimeListener)
        mainHandler.post(ticker)
        render()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        Runtime.removeListener(runtimeListener)
        mainHandler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (workspaceVisible) {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    exitWorkspace()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // -------------------------------------------------------------------- 工具

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun space(value: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(value))
    }

    private fun spaceH(value: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(value), 1)
    }

    private fun weight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun circle(color: Int, size: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(dp(size), dp(size))
        }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /** 与 Hermes 面板一致的深色配色。 */
    private object C {
        val BG = Color.parseColor("#0A0A0F")
        val SURFACE = Color.parseColor("#14141C")
        val SURFACE_HI = Color.parseColor("#1E1E28")
        val TRACK = Color.parseColor("#26262F")
        val ACCENT = Color.parseColor("#E8B44A")
        val ACCENT_DIM = Color.parseColor("#8A6A2C")
        val TEXT = Color.parseColor("#F2F2F5")
        val MUTED = Color.parseColor("#8B8B96")
        val FAINT = Color.parseColor("#5C5C66")
        val OK = Color.parseColor("#4ADE80")
        val WARN = Color.parseColor("#FBBF24")
        val ERR = Color.parseColor("#F87171")
        val LOG_TEXT = Color.parseColor("#A8A8B4")
    }
}
