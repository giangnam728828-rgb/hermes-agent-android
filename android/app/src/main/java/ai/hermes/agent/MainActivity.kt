package ai.hermes.agent

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File

/**
 * 单页 App 壳：打开即是工作台，没有「进入工作台」这一步。
 *
 * 2.0 之前这里是「启动页 + 工作台」两页结构，注释里还写着「刻意不自动跳进
 * 工作台」，用户明确要求去掉那一层。现在服务在后台自动拉起，启动过程只是一张
 * 会自动淡出的覆盖卡片；页面加载完直接落在面板里。
 *
 * 新增的原生能力（都是 1.0 缺失、导致「点了没反应」的硬伤）：
 *  - WebChromeClient.onShowFileChooser：网页里隐藏的 <input type=file> 终于能
 *    弹出系统文件选择器，文件/配置/会话导入与上传可用；
 *  - 下载监听：data: 与 http(s) 下载落到手机存储；
 *  - 工作区管理：绕开 headless 环境缺失的桌面 projects.db，直接在手机存储
 *    建目录并写进面板存储，新建/切换工作区可用；
 *  - 顶部栏 + 底部 Tab + 悬浮按钮，用面板自己的 <a href> 切页，不重载、
 *    不破坏 SPA 状态。
 *
 * 原有功能一个不少：端口/工作目录/局域网/全自主/保持唤醒/导出日志/重装/重启/
 * 运行日志/返回键，全部仍可从顶栏与「更多」进入。
 */
class MainActivity : Activity() {

    private data class Tab(val label: String, val icon: String, val path: String)

    private lateinit var root: FrameLayout
    private lateinit var webLayer: LinearLayout
    private lateinit var homeLayer: View
    private var todayHome: TodayHome? = null
    private lateinit var webHost: FrameLayout
    private lateinit var webView: WebView
    private lateinit var bootOverlay: FrameLayout
    private lateinit var bootTitle: TextView
    private lateinit var bootDetail: TextView
    private lateinit var bootTrack: View
    private lateinit var bootFill: View
    private lateinit var bootLogToggle: TextView
    private lateinit var bootLogPanel: LinearLayout
    private lateinit var bootLogText: TextView
    private lateinit var statusDot: View
    private lateinit var headerSub: TextView
    private lateinit var tabRow: LinearLayout

    private val tabs = mutableListOf<LinearLayout>()
    private var activeTab = 0

    private var bootVisible = true
    private var pageLoaded = false
    private var logsExpanded = false
    private var lastLogVersion = -1
    private var backPressedAt = 0L
    private var tickCount = 0

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var importTarget: File? = null
    private var pendingDraft = ""
    private var draftTries = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtimeListener: () -> Unit = { runOnUiThread { render() } }
    private val ticker = object : Runnable {
        override fun run() {
            render()
            tickCount++
            // 首页是纯原生 View，没必要每几秒往 WebView 里打一次 JS。
            if (tickCount % 2 == 0 && !bootVisible &&
                ::webLayer.isInitialized && webLayer.visibility == View.VISIBLE
            ) syncRouteTabs()
            mainHandler.postDelayed(this, 2000)
        }
    }

    private companion object {
        const val REQ_FILE_CHOOSER = 1001
        const val REQ_IMPORT = 1002
        val TABS = listOf(
            Tab("会话", "☰", "/sessions"),
            Tab("终端", "▸", "/chat"),
            Tab("文件", "▤", "/files"),
            Tab("技能", "✦", "/skills"),
            Tab("设置", "⚙", ""),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        Paths.ensureDirs(this)
        buildUi()
        Runtime.addListener(runtimeListener)
        requestNotificationPermission()
        applyImmersive()
        maybeStartRuntime()
        render()
        // 3.0：WebView 懒加载，只有用户从 Today 首页点进工作台时才载入，
        // 首屏不再背着一个桌面级 SPA，也不再一启动就吃满内存。
        todayHome?.onRuntimeChanged()
    }

    // ---------------------------------------------------------------- UI 构建

    private fun buildUi() {
        root = FrameLayout(this).apply { background = bgGradient() }

        // 工作台层（完整上游 SPA + 2.0 修好的文件选择 / 工作区 / 设置），按需显示。
        webLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        webLayer.addView(buildTopBar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(60),
        ))

        webHost = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        webView = WebView(this)
        configureWebView()
        webHost.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        webHost.addView(buildFab(), FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            bottomMargin = dp(18)
            rightMargin = dp(18)
        })

        webLayer.addView(webHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        webLayer.addView(buildTabBar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        root.addView(webLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // 默认首屏：Today 风格纯原生主屏，不加载 WebView，秒开且不卡。
        todayHome = TodayHome(this) { path, draft -> openWeb(path, draft) }
        homeLayer = todayHome!!.build()
        root.addView(homeLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        root.addView(buildBootOverlay(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        setContentView(root)
    }

    /**
     * 从 Today 首页进入完整工作台。draft 非空时会尽力注入 /chat 的 xterm，
     * 让首页输入框里打的那句话真的送进 PTY，而不是只有一个假的回显。
     */
    internal fun openWeb(path: String, draft: String = "") {
        if (Runtime.phase != Runtime.Phase.READY || Runtime.port <= 0) {
            toast("运行环境还在启动，稍等一下")
            return
        }
        pendingDraft = draft
        draftTries = 0
        homeLayer.visibility = View.GONE
        webLayer.visibility = View.VISIBLE
        val target = if (path.isBlank() || path == "/") {
            Runtime.workspaceUrl.ifBlank { "http://127.0.0.1:${Runtime.port}/" }
        } else {
            "http://127.0.0.1:${Runtime.port}$path"
        }
        if (webView.url == null || webView.url != target) webView.loadUrl(target)
        if (draft.isNotEmpty()) mainHandler.postDelayed({ injectDraft() }, 900)
    }

    /** 返回 Today 首页，只隐藏工作台层，不销毁 WebView，终端会话不丢。 */
    internal fun backToHome() {
        pendingDraft = ""
        webLayer.visibility = View.GONE
        homeLayer.visibility = View.VISIBLE
        todayHome?.onRuntimeChanged()
    }

    /**
     * 把首页输入框的内容送进 /chat 内嵌 xterm 的隐藏 textarea，触发 xterm 的
     * input 采集（onData → PTY WebSocket），等于替用户敲了一次回车。
     * 页面还没渲染出 xterm 时会重试；实在拿不到就复制到剪贴板兜底。
     */
    private fun injectDraft() {
        val text = pendingDraft
        if (text.isEmpty() || draftTries > 12 || webLayer.visibility != View.VISIBLE) return
        draftTries++
        val q = JSONObject.quote(text)
        val script = """
            (function(){
              var t=document.querySelector('.xterm-helper-textarea');
              if(!t) return '0';
              try{
                t.focus();
                t.value=$q;
                t.dispatchEvent(new Event('input',{bubbles:true}));
                t.value='';
                setTimeout(function(){
                  t.value='\\r';
                  t.dispatchEvent(new Event('input',{bubbles:true}));
                  t.value='';
                },160);
                return '1';
              }catch(e){ return '0'; }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { value ->
            if (value?.trim('"')?.trim() == "1") {
                pendingDraft = ""
                toast("已送进终端")
            } else if (draftTries <= 12) {
                mainHandler.postDelayed({ injectDraft() }, 500)
            } else {
                pendingDraft = ""
                runCatching {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Hermes", text))
                }
                toast("已复制内容，长按终端粘贴即可发送")
            }
        }
    }

    /** 顶栏：圆形设置键 + 胶囊标题（带状态点/当前工作区）+ 圆形更多键。 */
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        // 工作台层左上角是「回 Today 首页」；设置入口在「⋯」更多里，功能不丢。
        bar.addView(circleButton("←") { backToHome() })

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(C.SURFACE, 22)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            elevation = dp(3).toFloat()
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply { background = circle(C.MUTED, 7) }
        titleRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)))
        titleRow.addView(spaceH(7))
        titleRow.addView(TextView(this).apply {
            text = "Hermes Agent"
            textSize = 14.5f
            setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        pill.addView(titleRow)
        headerSub = TextView(this).apply {
            text = "本机 · 默认工作区"
            textSize = 10.5f
            setTextColor(C.MUTED)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        pill.addView(headerSub)
        bar.addView(pill, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ).apply { leftMargin = dp(12); rightMargin = dp(12) })
        bar.addView(circleButton("⋯") { showMore() })
        return bar
    }

    /** 悬浮主按钮：新建/切换工作区 + 常用快捷操作。 */
    private fun buildFab(): View = TextView(this).apply {
        text = "+"
        textSize = 27f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        background = rounded(C.ACCENT, 28)
        elevation = dp(8).toFloat()
        isClickable = true
        setOnClickListener { showWorkspaceSheet() }
        EtaMotion.press(this@MainActivity, this)
    }

    /** 底部 Tab：点击走面板自己的 SPA 路由，不重载页面。 */
    private fun buildTabBar(): View {
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C.SURFACE, 0)
            setPadding(dp(6), dp(8), dp(6), dp(10))
            elevation = dp(6).toFloat()
        }
        TABS.forEachIndexed { index, tab ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(4), dp(2), dp(2))
                isClickable = true
                setOnClickListener { onTabClicked(index) }
                EtaMotion.press(this@MainActivity, this)
            }
            val icon = TextView(this).apply {
                text = tab.icon
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(C.MUTED)
            }
            val label = TextView(this).apply {
                text = tab.label
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(C.MUTED)
            }
            item.addView(icon)
            item.addView(label)
            item.tag = icon to label
            tabs.add(item)
            tabRow.addView(item, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ))
        }
        paintTabs()
        return tabRow
    }

    private fun onTabClicked(index: Int) {
        val tab = TABS[index]
        if (tab.path.isEmpty()) {
            showSettings()
            return
        }
        setActiveTab(index)
        if (Runtime.phase == Runtime.Phase.READY) {
            WebBridge.navigate(webView, tab.path)
        } else {
            toast("服务还在启动，稍等一下")
        }
    }

    private fun setActiveTab(index: Int) {
        activeTab = index
        paintTabs()
    }

    private fun paintTabs() {
        tabs.forEachIndexed { i, item ->
            val pair = item.tag as? Pair<*, *>
            val icon = pair?.first as? TextView
            val label = pair?.second as? TextView
            val color = if (i == activeTab) C.ACCENT else C.MUTED
            icon?.setTextColor(color)
            label?.setTextColor(color)
            label?.typeface = if (i == activeTab) {
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            } else {
                Typeface.DEFAULT
            }
        }
    }

    /** 启动态覆盖卡片：不需要点任何按钮，服务与页面就绪后自动淡出。 */
    private fun buildBootOverlay(): View {
        bootOverlay = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#F2E2E9FB"),
                    Color.parseColor("#F6F0F3FC"),
                    Color.parseColor("#FAF8F7FD"),
                ),
            )
            isClickable = true
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, 26)
            elevation = dp(10).toFloat()
            setPadding(dp(24), dp(24), dp(24), dp(22))
        }
        card.addView(TextView(this).apply {
            text = "HERMES  AGENT"
            textSize = 10.5f
            letterSpacing = 0.22f
            setTextColor(C.ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        })
        card.addView(space(14))
        bootTitle = TextView(this).apply {
            text = "正在准备本地服务"
            textSize = 19f
            setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        card.addView(bootTitle)
        card.addView(space(8))
        bootDetail = TextView(this).apply {
            text = "第一次启动需要解压运行环境，约 1–3 分钟"
            textSize = 12.5f
            setTextColor(C.MUTED)
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        card.addView(bootDetail)

        card.addView(space(18))
        val track = FrameLayout(this).apply {
            background = rounded(C.TRACK, 3)
            visibility = View.GONE
        }
        bootFill = View(this).apply { background = rounded(C.ACCENT, 3) }
        track.addView(bootFill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        bootTrack = track
        card.addView(track, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(4),
        ))

        card.addView(space(16))
        bootLogToggle = TextView(this).apply {
            text = "运行日志  ›"
            textSize = 12f
            setTextColor(C.ACCENT)
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(6), dp(2), dp(6))
            setOnClickListener { toggleBootLogs() }
        }
        card.addView(bootLogToggle)

        bootLogPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.BG, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        bootLogText = TextView(this).apply {
            textSize = 10f
            setTextColor(C.LOG_TEXT)
            typeface = Typeface.MONOSPACE
            text = "（暂无日志）"
        }
        val logScroll = ScrollView(this).apply { addView(bootLogText) }
        bootLogPanel.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(150),
        ))
        card.addView(bootLogPanel)

        card.addView(space(12))
        card.addView(TextView(this).apply {
            text = "准备好后会自动进入工作台"
            textSize = 10.5f
            setTextColor(C.FAINT)
            gravity = Gravity.CENTER
        })

        bootOverlay.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = dp(28)
            rightMargin = dp(28)
        })
        return bootOverlay
    }

    // ------------------------------------------------------------ WebView 配置

    private fun configureWebView() {
        webView.setBackgroundColor(Color.parseColor("#F3F5FC"))
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        // 文件选择器拿到的是 content:// URI，必须允许内容访问，否则选中后读不到内容。
        settings.allowContentAccess = true
        settings.allowFileAccess = false
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

            /**
             * 1.0 缺的就是这个：网页里的隐藏 <input type=file> 被 .click() 之后，
             * WebView 默认不弹系统文件选择器，表现就是「点上传没有任何反应」。
             */
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?,
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = runCatching {
                    (params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT)).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (type.isNullOrBlank()) "*/*" else type
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }.getOrElse {
                    filePathCallback = null
                    return false
                }
                return runCatching {
                    startActivityForResult(intent, REQ_FILE_CHOOSER)
                    true
                }.getOrElse {
                    filePathCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener { url, _, _, mimeType, _ ->
            handleDownload(url, mimeType)
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
                WebBridge.install(webView, this@MainActivity)
                if (url != null && (url.startsWith("http://127.0.0.1") ||
                        url.startsWith("http://localhost"))
                ) {
                    Runtime.log("页面已加载：$url")
                    pageLoaded = true
                    syncRouteTabs()
                    maybeHideBoot()
                }
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

    private fun loadWorkspace() {
        val url = Runtime.workspaceUrl.ifBlank { "http://127.0.0.1:${Runtime.port}/" }
        if (webView.url == null || !webView.url!!.startsWith("http://127.0.0.1")) {
            webView.loadUrl(url)
        }
    }

    private fun maybeHideBoot() {
        // 3.0 首屏是原生 View，不需要等 WebView onPageFinished 才放行。
        if (!bootVisible || Runtime.phase != Runtime.Phase.READY) return
        bootVisible = false
        bootOverlay.animate().alpha(0f).setDuration(320)
            .withEndAction { bootOverlay.visibility = View.GONE }.start()
    }

    // ------------------------------------------------------------------ 文件能力

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE_CHOOSER) {
            val callback = filePathCallback
            filePathCallback = null
            val uris: Array<Uri>? = if (resultCode != RESULT_OK) null else {
                val clip: ClipData? = data?.clipData
                when {
                    clip != null && clip.itemCount > 0 ->
                        (0 until clip.itemCount).map { clip.getItemAt(it).uri }.toTypedArray()
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            }
            callback?.onReceiveValue(uris)
            return
        }
        if (requestCode == REQ_IMPORT) {
            val dir = importTarget
            importTarget = null
            if (resultCode == RESULT_OK && dir != null) {
                val uris = mutableListOf<Uri>()
                data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                }
                if (uris.isEmpty()) data?.data?.let { uris.add(it) }
                copyIntoWorkspace(dir, uris)
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** 系统文件选择器选中的文件真正落到手机存储的工作区目录。 */
    private fun copyIntoWorkspace(dir: File, uris: List<Uri>) {
        if (uris.isEmpty()) return
        var ok = 0
        runCatching { dir.mkdirs() }
        uris.forEach { uri ->
            val name = runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
            }.getOrNull() ?: "file-${System.currentTimeMillis()}"
            val safe = name.replace(Regex("[/\\\\]"), "_")
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    File(dir, safe).outputStream().use { output -> input.copyTo(output) }
                }
                ok++
            }
        }
        toast(if (ok > 0) "已导入 $ok 个文件到工作区" else "导入失败，请重试")
        if (ok > 0 && Runtime.phase == Runtime.Phase.READY) WebBridge.reload(webView)
    }

    /** 网页里的 <a download> / data: 下载在 WebView 里不会自动落盘。 */
    private fun handleDownload(url: String, mimeType: String?) {
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0) return
            val meta = url.substring(5, comma)
            val payload = url.substring(comma + 1)
            val bytes = runCatching {
                if (meta.contains(";base64")) Base64.decode(payload, Base64.DEFAULT)
                else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
            }.getOrElse { return }
            val ext = when {
                (mimeType ?: "").contains("json") -> "json"
                (mimeType ?: "").contains("pdf") -> "pdf"
                (mimeType ?: "").contains("image/png") -> "png"
                else -> "txt"
            }
            val dir = runCatching {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }.getOrNull() ?: cacheDir
            val file = File(dir, "hermes-${System.currentTimeMillis()}.$ext")
            runCatching {
                dir.mkdirs()
                file.writeBytes(bytes)
                toast("已保存到 ${file.absolutePath}")
            }.onFailure { toast("保存失败：${it.message}") }
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("无法打开下载链接") }
    }

    // ------------------------------------------------------------------ 路由同步

    private fun syncRouteTabs() {
        if (Runtime.phase != Runtime.Phase.READY) return
        runCatching {
            WebBridge.currentRoute(webView) { path ->
                runOnUiThread {
                    val index = when {
                        path.startsWith("/chat") -> 1
                        path.startsWith("/files") -> 2
                        path.startsWith("/skills") -> 3
                        path.startsWith("/sessions") -> 0
                        path == "/" -> 0
                        else -> -1
                    }
                    if (index >= 0 && index != activeTab) setActiveTab(index)
                }
            }
        }
    }

    // -------------------------------------------------------------------- 渲染

    private fun render() {
        val phase = Runtime.phase
        // 只有在工作台层真正可见时才保活 WebView，首页状态一律不碰它。
        if (phase == Runtime.Phase.READY && Runtime.port > 0 &&
            ::webLayer.isInitialized && webLayer.visibility == View.VISIBLE
        ) loadWorkspace()
        todayHome?.onRuntimeChanged()

        statusDot.background = circle(
            when (phase) {
                Runtime.Phase.READY -> C.OK
                Runtime.Phase.ERROR -> C.ERR
                Runtime.Phase.INSTALLING, Runtime.Phase.STARTING -> C.WARN
                else -> C.MUTED
            },
            7,
        )
        val current = Workspaces.current(this)
        headerSub.text = if (current.isNotEmpty()) {
            "本机 · ${Workspaces.displayName(current)}"
        } else {
            "本机 · 默认工作区"
        }

        if (!bootVisible) return
        bootTitle.text = when (phase) {
            Runtime.Phase.READY -> "工作台已就绪"
            Runtime.Phase.ERROR -> "启动遇到问题"
            Runtime.Phase.INSTALLING -> "正在解压运行环境"
            Runtime.Phase.STARTING -> "正在启动本地服务"
            Runtime.Phase.STOPPED -> "服务已停止"
            else -> "正在准备本地服务"
        }
        bootDetail.text = Runtime.detail.ifBlank {
            when (phase) {
                Runtime.Phase.READY -> "正在载入面板…"
                Runtime.Phase.ERROR -> "展开日志看看具体报错，或在设置里重新安装"
                Runtime.Phase.INSTALLING -> "第一次启动需要解压运行环境，约 1–3 分钟"
                Runtime.Phase.STARTING -> "Python 服务启动中，请稍候"
                Runtime.Phase.STOPPED -> "正在重新拉起服务"
                else -> "准备好后会自动进入工作台"
            }
        }

        val progress = Runtime.progress
        if (progress in 0..100) {
            bootTrack.visibility = View.VISIBLE
            val width = (bootTrack.width * progress / 100f).toInt()
            bootFill.layoutParams = FrameLayout.LayoutParams(
                if (width > 0) width else 0, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            bootFill.requestLayout()
        } else {
            bootTrack.visibility = View.GONE
        }

        if (logsExpanded && lastLogVersion != Runtime.logVersion) {
            lastLogVersion = Runtime.logVersion
            val lines = Runtime.snapshotLogs()
            bootLogText.text = if (lines.isEmpty()) "（暂无日志）"
            else lines.takeLast(120).joinToString("\n")
            (bootLogText.parent as? View)?.post {
                (bootLogText.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
            }
        }
        maybeHideBoot()
    }

    private fun toggleBootLogs() {
        logsExpanded = !logsExpanded
        bootLogPanel.visibility = if (logsExpanded) View.VISIBLE else View.GONE
        bootLogToggle.text = if (logsExpanded) "运行日志  ⌄" else "运行日志  ›"
        lastLogVersion = -1
        if (logsExpanded) render()
    }

    // ------------------------------------------------------------ 工作区管理弹层

    internal fun showWorkspaceSheet() {
        EtaSheet.show(this, "工作区", tall = false) { sheet ->
            sheet.body.addView(TextView(this).apply {
                text = "工作区是 Agent 读写的真实目录。新建后会自动切到该目录开一个新会话。"
                textSize = 12.5f
                setTextColor(C.MUTED)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(2), 0, dp(12))
            })

            sheet.body.addView(sectionLabel("当前"))
            sheet.body.addView(space(6))
            val current = Workspaces.current(this)
            sheet.body.addView(TextView(this).apply {
                text = if (current.isNotEmpty()) current else "默认工作目录（未选择）"
                textSize = 12.5f
                setTextColor(C.TEXT)
                typeface = Typeface.MONOSPACE
                background = rounded(C.SURFACE_HI, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            })

            val existing = Workspaces.list(this)
            sheet.body.addView(space(16))
            sheet.body.addView(sectionLabel("已创建 (${existing.size})"))
            sheet.body.addView(space(6))
            if (existing.isEmpty()) {
                sheet.body.addView(TextView(this).apply {
                    text = "还没有工作区，在下面输入名字新建一个"
                    textSize = 12f
                    setTextColor(C.FAINT)
                    setPadding(dp(4), dp(4), dp(4), dp(8))
                })
            } else {
                existing.forEach { path ->
                    val row = actionRow(Workspaces.displayName(path)) {
                        sheet.dismiss()
                        openWorkspace(path)
                    }
                    sheet.body.addView(row)
                    sheet.body.addView(space(6))
                }
            }

            sheet.body.addView(space(14))
            sheet.body.addView(sectionLabel("新建"))
            sheet.body.addView(space(6))
            val input = EditText(this).apply {
                hint = "例如：我的项目 / demo / 工作"
                textSize = 15f
                inputType = InputType.TYPE_CLASS_TEXT
                setTextColor(C.TEXT)
                setHintTextColor(C.FAINT)
                background = rounded(C.SURFACE_HI, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            sheet.body.addView(input)
            sheet.body.addView(space(12))
            sheet.body.addView(TextView(this).apply {
                text = "目录会建在：${Workspaces.root(this@MainActivity).absolutePath}"
                textSize = 11f
                setTextColor(C.FAINT)
                setPadding(dp(2), 0, dp(2), dp(4))
            })

            // 快捷操作：手机 App 常用动作，不需要回到网页里翻菜单
            sheet.body.addView(space(16))
            sheet.body.addView(sectionLabel("快捷操作"))
            sheet.body.addView(space(6))
            sheet.body.addView(actionRow("上传文件到当前工作区") {
                sheet.dismiss()
                pickFilesForWorkspace()
            })
            sheet.body.addView(space(6))
            sheet.body.addView(actionRow("打开终端 / 新建会话") {
                sheet.dismiss()
                setActiveTab(1)
                if (Runtime.phase == Runtime.Phase.READY) WebBridge.navigate(webView, "/chat")
            })
            sheet.body.addView(space(6))
            sheet.body.addView(actionRow("刷新当前页面") {
                sheet.dismiss()
                if (Runtime.phase == Runtime.Phase.READY) WebBridge.reload(webView)
            })

            val (row, _) = EtaSheet.dualButtons(
                this, getString(R.string.action_cancel), "新建并进入",
                onCancel = { sheet.dismiss() },
                onConfirm = {
                    val result = Workspaces.create(this, input.text.toString())
                    result.fold(
                        onSuccess = { path ->
                            sheet.dismiss()
                            openWorkspace(path)
                        },
                        onFailure = { toast(it.message ?: "创建失败") },
                    )
                },
            )
            sheet.footer.addView(row)
        }
    }

    private fun openWorkspace(path: String) {
        Workspaces.setCurrent(this, path)
        headerSub.text = "本机 · ${Workspaces.displayName(path)}"
        if (Runtime.phase != Runtime.Phase.READY) {
            toast("工作区已保存，服务就绪后进入终端生效")
            return
        }
        // 一次原子脚本完成「写入面板存储 + 整页进入 /chat」，保证 ChatPage
        // 重新挂载时读到的是新工作区，不会被异步导航打断。
        WebBridge.openWorkspace(webView, path)
        toast("已切换到 ${Workspaces.displayName(path)}")
        setActiveTab(1)
    }

    internal fun pickFilesForWorkspace() {
        val target = Workspaces.current(this).ifEmpty { Prefs.workspace(this) }
        val dir = File(target)
        val created = dir.isDirectory || runCatching { dir.mkdirs() }.getOrDefault(false)
        if (!created) {
            toast("工作目录不可写，请到设置里更换")
            return
        }
        importTarget = dir
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching { startActivityForResult(intent, REQ_IMPORT) }
            .onFailure { toast("无法打开文件选择器") }
    }

    // ------------------------------------------------------------ 更多 / 设置

    internal fun showMore() {
        EtaSheet.show(this, "更多", tall = false) { sheet ->
            sheet.body.addView(actionRow("运行日志") {
                sheet.dismiss()
                EtaSheet.show(this@MainActivity, "运行日志", tall = true) { logSheet ->
                    val logText = TextView(this@MainActivity).apply {
                        text = Runtime.snapshotLogs().takeLast(300).joinToString("\n")
                            .ifEmpty { "（暂无日志）" }
                        textSize = 10.5f
                        setTextColor(C.LOG_TEXT)
                        typeface = Typeface.MONOSPACE
                        setLineSpacing(dp(2).toFloat(), 1f)
                    }
                    logSheet.body.addView(ScrollView(this@MainActivity).apply { addView(logText) })
                    val (row, _) = EtaSheet.dualButtons(
                        this@MainActivity, "关闭", "导出日志",
                        onCancel = { logSheet.dismiss() },
                        onConfirm = { logSheet.dismiss(); exportLogs() },
                    )
                    logSheet.footer.addView(row)
                }
            })
            sheet.body.addView(space(8))
            sheet.body.addView(actionRow("刷新页面") {
                sheet.dismiss()
                if (Runtime.phase == Runtime.Phase.READY) WebBridge.reload(webView)
            })
            sheet.body.addView(space(8))
            sheet.body.addView(actionRow("重启本地服务") {
                sheet.dismiss()
                if (Runtime.phase == Runtime.Phase.READY) {
                    toast("正在重启本地服务")
                    RuntimeService.restart(this)
                } else {
                    toast("服务还没跑起来")
                }
            })
            sheet.body.addView(space(8))
            sheet.body.addView(actionRow("导出日志") { sheet.dismiss(); exportLogs() })
            sheet.body.addView(space(8))
            sheet.body.addView(TextView(this).apply {
                text = "Hermes Agent · 本机运行 · 数据只留在这台手机"
                textSize = 11f
                setTextColor(C.FAINT)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(4))
            })
            val (row, _) = EtaSheet.dualButtons(
                this, "关闭", "设置",
                onCancel = { sheet.dismiss() },
                onConfirm = { sheet.dismiss(); showSettings() },
            )
            sheet.footer.addView(row)
        }
    }

    internal fun showSettings() {
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
                if (Runtime.phase == Runtime.Phase.READY) toast("重启后生效")
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
            sheet.body.addView(TextView(this).apply {
                text = "重新安装运行环境"
                textSize = 13.5f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(C.ERR)
                background = rounded(C.SURFACE, 14)
                setPadding(dp(16), dp(15), dp(16), dp(15))
                isClickable = true
                setOnClickListener { sheet.dismiss(); confirmReinstall() }
                EtaMotion.press(this@MainActivity, this)
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
        wrap.addView(TextView(this).apply {
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
        })
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
        val dot = View(this).apply { background = circle(if (value) C.BG else C.MUTED, 11) }
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

    internal fun confirmReinstall() {
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

    internal fun exportLogs() {
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

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.statusBarColor = Color.parseColor("#E2E9FB")
        window.navigationBarColor = C.SURFACE
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
            if (::webLayer.isInitialized && webLayer.visibility == View.VISIBLE) {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    backToHome()
                }
                return true
            }
            val now = System.currentTimeMillis()
            if (now - backPressedAt < 2000) {
                finish()
            } else {
                backPressedAt = now
                toast("再按一次退出")
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // -------------------------------------------------------------------- 工具

    private fun circleButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(C.TEXT)
            background = rounded(C.SURFACE, 24)
            elevation = dp(3).toFloat()
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            EtaMotion.press(this@MainActivity, this)
        }

    /** 对齐 Today 截图：顶部蓝紫 → 中部近白 → 底部暖米桃，全屏柔和渐变。 */
    private fun bgGradient(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            Color.parseColor("#B9CDEA"),
            Color.parseColor("#DCE8F6"),
            Color.parseColor("#F3F7FC"),
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#F5E9DE"),
            Color.parseColor("#F8DFBE"),
        ),
    )

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    private fun space(value: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(value))
    }

    private fun spaceH(value: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(value), 1)
    }

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

    /** DSH-Go 原生界面风格：淡紫蓝渐变 + 白色圆角卡片 + 蓝紫主色。 */
    private object C {
        val BG = Color.parseColor("#EFF2FB")
        val SURFACE = Color.parseColor("#FFFFFF")
        val SURFACE_HI = Color.parseColor("#EEF1F9")
        val TRACK = Color.parseColor("#E4E8F3")
        val ACCENT = Color.parseColor("#5266AF")
        val TEXT = Color.parseColor("#1B2235")
        val MUTED = Color.parseColor("#6B7288")
        val FAINT = Color.parseColor("#9AA2B6")
        val OK = Color.parseColor("#1F9D6B")
        val WARN = Color.parseColor("#E08A2B")
        val ERR = Color.parseColor("#D9534F")
        val LOG_TEXT = Color.parseColor("#4A5266")
    }
}
