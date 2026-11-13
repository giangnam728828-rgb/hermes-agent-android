package ai.hermes.agent

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Today 风格原生主屏（3.0）。
 *
 * 与 2.0 最大的不同：2.0 的主内容区仍然是完整的桌面 SPA，只是外面套了原生
 * 顶栏/底栏，所以「看起来还是一样」并且 WebView 常驻导致卡顿。3.0 把主屏整体
 * 换成纯原生 View：渐变背景、圆形悬浮按钮、白色聊天气泡流、胶囊输入栏、
 * 胶囊底部 Tab、卡片式设置页。完整 SPA 退到按需加载的「工作台」层，只在用户
 * 真正点进去时才加载，首屏不再背着一个桌面级 Web 应用。
 *
 * 这里的所有卡片都是真入口：要么跳到面板的真实路由，要么调用 MainActivity
 * 里已有的原生能力（工作区、文件选择、设置、日志、导出、重装）。数据不是
 * 写死的假内容，来自本机 /api/status 与 /api/sessions。
 */
class TodayHome(
    private val act: MainActivity,
    private val openWeb: (String, String) -> Unit,
) {

    private data class Sess(
        val id: String,
        val title: String,
        val snippet: String,
        val count: Int,
        val model: String,
    )

    private val H = Handler(Looper.getMainLooper())
    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT

    private var sessions: List<Sess> = emptyList()
    private var modelName = ""
    private var activeTab = 0
    private var wasReady = false
    private val mine = mutableListOf<String>()

    private lateinit var host: FrameLayout
    private lateinit var feed: LinearLayout
    private lateinit var inputBar: View
    private lateinit var input: EditText
    private lateinit var tabBar: LinearLayout
    private val tabItems = mutableListOf<LinearLayout>()

    // ---------------------------------------------------------------- 构建

    fun build(): View {
        val root = FrameLayout(act)
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        col.addView(topBar(), LinearLayout.LayoutParams(M, dp(64)))

        host = FrameLayout(act)
        col.addView(host, LinearLayout.LayoutParams(M, 0, 1f))

        inputBar = inputPill()
        col.addView(inputBar, LinearLayout.LayoutParams(M, dp(54)).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(8)
        })

        tabBar = tabPill()
        col.addView(tabBar, LinearLayout.LayoutParams(M, W).apply {
            leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(10)
        })

        root.addView(col, FrameLayout.LayoutParams(M, M))

        // 右下「灵感」浮动胶囊，位置在输入栏之上
        val sparkle = pill("✨ 灵感", P.GLASS, P.TEXT, 13f) {
            openWeb("/chat", "给我三条今天值得留意的事，简短一点")
        }
        root.addView(sparkle, FrameLayout.LayoutParams(W, dp(40)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(20); bottomMargin = dp(132)
        })

        showPage(0)
        return root
    }

    /** 运行时状态变化：只在「刚就绪」那一刻拉一次数据，避免轮询拖慢 UI。 */
    fun onRuntimeChanged() {
        val ready = Runtime.phase == Runtime.Phase.READY && Runtime.port > 0
        if (ready && !wasReady) {
            wasReady = true
            fetch()
        } else if (!ready && wasReady) {
            // 只在「刚掉线」那一帧重画，避免启动解压的几分钟里每 2 秒重建气泡。
            wasReady = false
            if (activeTab == 0) renderFeed()
        }
    }

    /** 搜索键 / 手动刷新。 */
    fun refresh() {
        fetch()
        toast("正在刷新本机数据")
    }

    // ---------------------------------------------------------------- 顶部 / 输入 / Tab

    private fun topBar(): View {
        val bar = FrameLayout(act).apply { setPadding(dp(18), dp(10), dp(18), dp(6)) }

        val settings = roundButton("⚙", 42) { act.showSettings() }
        bar.addView(settings, FrameLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })

        // 居中头像：紫粉渐变圆 + 文字，形态对齐 Today 的悬浮头像
        val avatar = TextView(act).apply {
            text = "芽"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#8E7BC7"), Color.parseColor("#C58FB0")),
            ).apply {
                shape = GradientDrawable.OVAL
                cornerRadius = dp(26).toFloat()
            }
            elevation = dp(3).toFloat()
        }
        bar.addView(avatar, FrameLayout.LayoutParams(dp(52), dp(52)).apply {
            gravity = Gravity.CENTER
        })

        val search = roundButton("⌕", 42) { refresh() }
        bar.addView(search, FrameLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        })
        return bar
    }

    private fun inputPill(): View {
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(P.GLASS, 27)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            elevation = dp(2).toFloat()
        }

        bar.addView(roundButton("+", 38) { act.showWorkspaceSheet() },
            LinearLayout.LayoutParams(dp(38), dp(38)))

        input = EditText(act).apply {
            hint = "点击输入或按住说话…"
            setHintTextColor(P.SUB)
            setTextColor(P.TEXT)
            textSize = 15f
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(12), 0, dp(8), 0)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                send(); true
            } else false
        }
        bar.addView(input, LinearLayout.LayoutParams(0, M, 1f))

        val send = roundButton("↑", 38, bg = P.BLACK, fg = Color.WHITE) { send() }
        bar.addView(send, LinearLayout.LayoutParams(dp(38), dp(38)))
        return bar
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            toast("输入点什么再发")
            return
        }
        if (Runtime.phase != Runtime.Phase.READY) {
            toast("运行环境还在启动，稍等一下")
            return
        }
        input.setText("")
        mine.add(text)
        renderFeed()
        // 切到完整工作台的 /chat，并把草稿注入 xterm，让这条消息真的送进 PTY。
        openWeb("/chat", text)
    }

    private fun tabPill(): View {
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(P.TAB_BG, 30)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            elevation = dp(3).toFloat()
        }
        val labels = listOf("聊天", "Today", "任务", "记忆", "我")
        val icons = listOf("💬", "📅", "✅", "✨", "👤")
        labels.forEachIndexed { i, label ->
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(4), dp(2), dp(3))
                isClickable = true
                setOnClickListener { showPage(i) }
            }
            val icon = TextView(act).apply {
                text = icons[i]; textSize = 17f; gravity = Gravity.CENTER
            }
            val tv = TextView(act).apply {
                text = label; textSize = 10.5f; gravity = Gravity.CENTER
                setTextColor(P.SUB)
            }
            item.addView(icon)
            item.addView(tv)
            item.tag = icon to tv
            tabItems.add(item)
            bar.addView(item, LinearLayout.LayoutParams(0, W, 1f))
        }
        return bar
    }

    private fun paintTabs() {
        tabItems.forEachIndexed { i, item ->
            val pair = item.tag as? Pair<*, *>
            val tv = pair?.second as? TextView
            tv?.setTextColor(if (i == activeTab) P.TEXT else P.SUB)
            tv?.typeface = if (i == activeTab) {
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            } else Typeface.DEFAULT
            item.background = if (i == activeTab) rounded(P.TAB_ON, 22) else null
        }
    }

    // ---------------------------------------------------------------- 页面切换

    private fun showPage(index: Int) {
        activeTab = index
        host.removeAllViews()
        val page: View = when (index) {
            0 -> chatPage()
            1 -> todayPage()
            2 -> tasksPage()
            3 -> memoryPage()
            else -> mePage()
        }
        host.addView(page, FrameLayout.LayoutParams(M, M))
        inputBar.visibility = if (index == 0) View.VISIBLE else View.GONE
        paintTabs()
    }

    private fun scroller(body: (LinearLayout) -> Unit): View {
        val sv = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(24))
        }
        body(box)
        sv.addView(box, FrameLayout.LayoutParams(M, W))
        return sv
    }

    /** Tab 0：白色聊天气泡流，最接近截图一。 */
    private fun chatPage(): View {
        val sv = ScrollView(act).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        feed = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(24))
        }
        sv.addView(feed, FrameLayout.LayoutParams(M, W))
        renderFeed()
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        return sv
    }

    private fun renderFeed() {
        if (!::feed.isInitialized || activeTab != 0) return
        feed.removeAllViews()

        // 早间简报胶囊
        val date = SimpleDateFormat("M月d日", Locale.CHINA).format(Date())
        val brief = pill("📄  早间简报 · $date  ›", Color.WHITE, P.TEXT, 13f) {
            openWeb("/sessions", "")
        }
        brief.elevation = dp(2).toFloat()
        feed.addView(brief, LinearLayout.LayoutParams(W, dp(42)).apply { bottomMargin = dp(16) })

        feed.addView(timeLabel("今天 " +
            SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())))

        val ready = Runtime.phase == Runtime.Phase.READY
        if (!ready) {
            feed.addView(bubbleCard(
                "运行环境正在准备",
                "${Runtime.title}\n${Runtime.detail.ifBlank { "首次启动需要解压运行环境，约 1–3 分钟。" }}",
                "启动完成后这里会自动出现本机会话",
            ) { act.showMore() }, cardLp())
            feed.addView(footerNote())
            return
        }

        if (sessions.isEmpty()) {
            feed.addView(bubbleCard(
                "还没有会话",
                "直接在下面输入框打一句话，就能开一轮新对话；也可以点左下角「+」新建工作区、导入文件。",
                "本机 Hermes · 数据只留在这台手机",
            ) { openWeb("/chat", "") }, cardLp())
        } else {
            sessions.take(6).forEach { s ->
                val caption = buildString {
                    if (s.count > 0) append("共 ${s.count} 条消息")
                    if (s.model.isNotBlank()) {
                        if (isNotEmpty()) append("  ·  ")
                        append(s.model)
                    }
                }.ifBlank { "点击打开会话" }
                feed.addView(bubbleCard(
                    s.title,
                    s.snippet.ifBlank { "点开继续这轮对话，完整终端与工具都在工作台里。" },
                    caption,
                ) { openWeb("/sessions", "") }, cardLp())
            }
        }

        mine.takeLast(6).forEach { text ->
            feed.addView(mineBubble(text), LinearLayout.LayoutParams(W, W).apply {
                gravity = Gravity.END
                topMargin = dp(12); bottomMargin = dp(12)
                leftMargin = dp(42)
            })
        }

        feed.addView(footerNote())
    }

    /** Tab 1：今日卡片页。 */
    private fun todayPage(): View = scroller { box ->
        box.addView(pageTitle("Today"))
        box.addView(subTitle(dateLine()))

        val brief = card()
        brief.addView(rowTitle("早间简报"))
        brief.addView(rowCaption("本机模型：${modelName.ifBlank { "跟随配置" }}"))
        brief.addView(rowCaption("运行状态：${Runtime.title}"))
        brief.addView(space(12))
        brief.addView(blackButton("打开会话列表") { openWeb("/sessions", "") })
        box.addView(brief, cardLp())

        val usage = card()
        usage.addView(rowTitle("用量"))
        usage.addView(rowCaption("按会话统计 token、成本与调用次数"))
        usage.addView(space(10))
        usage.addView(progressRow())
        usage.addView(linkRow("查看完整统计") { openWeb("/analytics", "") })
        box.addView(usage, cardLp())

        val quick = card()
        quick.addView(rowTitle("快捷开始"))
        quick.addView(rowCaption("这些按钮都直接落到面板的真实功能"))
        quick.addView(space(10))
        quick.addView(tripleRow(
            "新建工作区" to { act.showWorkspaceSheet() },
            "导入文件" to { act.pickFilesForWorkspace() },
            "打开终端" to { openWeb("/chat", "") },
        ))
        box.addView(quick, cardLp())

        val ideas = card()
        ideas.addView(rowTitle("✨ 灵感"))
        ideas.addView(rowCaption("点一条就直接送进终端开聊"))
        ideas.addView(space(8))
        listOf(
            "帮我整理一下这台手机上的工作区",
            "看看最近有哪些会话值得继续",
            "给我三条今天值得留意的事，简短一点",
        ).forEach { text ->
            ideas.addView(linkRow(text) { openWeb("/chat", text) })
        }
        box.addView(ideas, cardLp())
        box.addView(footerNote())
    }

    /** Tab 2：真实会话列表。 */
    private fun tasksPage(): View = scroller { box ->
        box.addView(pageTitle("任务"))
        box.addView(subTitle("本机会话 · 共 ${sessions.size} 条"))
        val start = card()
        start.addView(rowTitle("开始一轮新对话"))
        start.addView(rowCaption("进入终端 /chat，工具与技能全部可用"))
        start.addView(space(10))
        start.addView(blackButton("新建会话") { openWeb("/chat", "") })
        box.addView(start, cardLp())

        if (sessions.isEmpty()) {
            val empty = card()
            empty.addView(rowTitle("暂时没有会话"))
            empty.addView(rowCaption("服务刚启动时会是空的，聊一轮之后这里就有了。"))
            box.addView(empty, cardLp())
        } else {
            sessions.forEach { s ->
                val c = card()
                c.addView(rowTitle(s.title))
                c.addView(rowCaption(s.snippet.ifBlank { "共 ${s.count} 条消息" }))
                c.addView(linkRow("继续这轮对话 ›") { openWeb("/sessions", "") })
                box.addView(c, cardLp())
            }
        }
        box.addView(linkRow("打开完整会话管理") { openWeb("/sessions", "") })
        box.addView(footerNote())
    }

    /** Tab 3：能力与连接入口，全部是真实路由。 */
    private fun memoryPage(): View = scroller { box ->
        box.addView(pageTitle("记忆"))
        box.addView(subTitle("技能、模型、连接与配置"))
        box.addView(sectionLabel("能力"))
        listOf(
            "技能" to "/skills",
            "MCP 服务" to "/mcp",
            "插件" to "/plugins",
            "模型" to "/models",
            "环境变量与 KEYS" to "/env",
            "定时任务" to "/cron",
        ).forEach { (label, path) ->
            val c = card()
            c.addView(linkRow("$label  ›") { openWeb(path, "") })
            box.addView(c, cardLp())
        }
        box.addView(sectionLabel("配置与连接"))
        listOf(
            "配置" to "/config",
            "配置档" to "/profiles",
            "通道与设备" to "/channels",
            "Webhooks" to "/webhooks",
            "运行日志" to "/logs",
            "系统状态" to "/system",
        ).forEach { (label, path) ->
            val c = card()
            c.addView(linkRow("$label  ›") { openWeb(path, "") })
            box.addView(c, cardLp())
        }
        box.addView(footerNote())
    }

    /** Tab 4：截图二那种卡片式设置页。 */
    private fun mePage(): View = scroller { box ->
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(14))
        }
        head.addView(TextView(act).apply {
            text = "←"; textSize = 22f; setTextColor(P.TEXT)
            setPadding(0, 0, dp(14), 0)
            isClickable = true
            setOnClickListener { showPage(0) }
        })
        head.addView(TextView(act).apply {
            text = "设置"; textSize = 25f; setTextColor(P.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        box.addView(head)

        // Pro 卡
        val pro = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#DFEAFB"), Color.parseColor("#FCE9DA")),
            ).apply { cornerRadius = dp(22).toFloat() }
            setPadding(dp(20), dp(18), dp(20), dp(18))
            elevation = dp(2).toFloat()
        }
        pro.addView(TextView(act).apply {
            text = "✦  Hermes"; textSize = 17f; setTextColor(P.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        pro.addView(space(6))
        pro.addView(TextView(act).apply {
            text = "本机运行 · 数据只留在这台手机"; textSize = 12f; setTextColor(P.SUB)
        })
        pro.addView(space(14))
        pro.addView(TextView(act).apply {
            text = "Pro"; textSize = 12f; setTextColor(P.TEXT)
            background = rounded(Color.argb(190, 255, 255, 255), 12)
            setPadding(dp(12), dp(4), dp(12), dp(4))
        })
        box.addView(pro, cardLp())

        val main = card()
        main.addView(settingRow("模型", modelName.ifBlank { "跟随本机配置" }) {
            openWeb("/models", "")
        })
        main.addView(divider())
        main.addView(settingRow("用量", "查看统计与重置") { openWeb("/analytics", "") })
        main.addView(space(14))
        main.addView(blackButton("立即重置本地服务") {
            RuntimeService.restart(act)
            toast("正在重启本地服务")
        })
        box.addView(main, cardLp())

        box.addView(sectionLabel("能力与连接"))
        val links = card()
        listOf(
            "连接应用" to "/config",
            "连接设备与文件" to "/files",
            "技能" to "/skills",
            "记忆与配置档" to "/profiles",
            "定时任务" to "/cron",
        ).forEach { (label, path) ->
            links.addView(settingRow(label, "让 Hermes 获取信息、执行操作") {
                openWeb(path, "")
            })
            links.addView(divider())
        }
        box.addView(links, cardLp())

        box.addView(sectionLabel("本机与维护"))
        val local = card()
        local.addView(settingRow("端口与工作区", "${Runtime.port} · 点击调整") {
            act.showSettings()
        })
        local.addView(divider())
        local.addView(settingRow("运行日志", "查看 / 导出") { act.showMore() })
        local.addView(divider())
        local.addView(settingRow("导出日志", "保存到手机存储") { act.exportLogs() })
        local.addView(divider())
        local.addView(settingRow("重新安装运行环境", "出问题时用") { act.confirmReinstall() })
        box.addView(local, cardLp())

        val full = card()
        full.addView(rowTitle("完整工作台"))
        full.addView(rowCaption("上游面板的全部页面，功能一个不少"))
        full.addView(space(10))
        full.addView(blackButton("进入工作台") { openWeb("/", "") })
        box.addView(full, cardLp())

        box.addView(footerNote())
    }

    // ---------------------------------------------------------------- 数据

    private fun fetch() {
        Thread {
            val port = Runtime.port
            if (port <= 0) return@Thread
            var model = ""
            val list = mutableListOf<Sess>()
            try {
                model = firstString(http("http://127.0.0.1:$port/api/status"), "model") ?: ""
            } catch (_: Exception) { }
            try {
                val raw = http("http://127.0.0.1:$port/api/sessions?limit=8&order=created")
                val arr = JSONObject(raw).optJSONArray("sessions")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        list.add(Sess(
                            id = o.optString("id"),
                            title = o.optString("title").ifBlank { "未命名会话" },
                            snippet = o.optString("preview", o.optString("snippet", "")),
                            count = o.optInt("message_count", 0),
                            model = o.optString("model", ""),
                        ))
                    }
                }
            } catch (_: Exception) { }
            H.post {
                sessions = list
                modelName = model
                if (::host.isInitialized) showPage(activeTab)
            }
        }.start()
    }

    private fun http(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3500
        conn.readTimeout = 3500
        conn.requestMethod = "GET"
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun firstString(raw: String?, key: String): String? {
        if (raw.isNullOrBlank()) return null
        return try { walk(JSONObject(raw), key) } catch (_: Exception) { null }
    }

    private fun walk(o: JSONObject, key: String): String? {
        val v = o.opt(key)
        if (v is String && v.isNotBlank()) return v
        val keys = o.keys()
        while (keys.hasNext()) {
            val child = o.opt(keys.next())
            if (child is JSONObject) {
                val found = walk(child, key)
                if (found != null) return found
            } else if (child is JSONArray) {
                for (i in 0 until child.length()) {
                    val e = child.opt(i)
                    if (e is JSONObject) {
                        val found = walk(e, key)
                        if (found != null) return found
                    }
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 小组件

    private fun bubbleCard(title: String, body: String, caption: String, onClick: () -> Unit): View {
        val c = card()
        c.addView(TextView(act).apply {
            text = title; textSize = 16f; setTextColor(P.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        c.addView(space(8))
        c.addView(TextView(act).apply {
            text = body; textSize = 14.5f; setTextColor(P.TEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        c.addView(space(12))
        c.addView(TextView(act).apply {
            text = caption; textSize = 11.5f; setTextColor(P.SUB)
        })
        c.isClickable = true
        c.setOnClickListener { onClick() }
        return c
    }

    private fun mineBubble(text: String): View = TextView(act).apply {
        this.text = text
        textSize = 14.5f
        setTextColor(P.TEXT)
        background = rounded(P.BUBBLE_ME, 20)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun card(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Color.WHITE, 22)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        elevation = dp(2).toFloat()
    }

    private fun cardLp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(M, W).apply {
        bottomMargin = dp(14)
    }

    private fun pageTitle(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 27f
        setTextColor(P.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun subTitle(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(P.SUB)
        setPadding(0, 0, 0, dp(16))
    }

    private fun sectionLabel(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(P.FAINT)
        setPadding(dp(4), dp(10), 0, dp(10))
    }

    private fun rowTitle(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 16f
        setTextColor(P.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun rowCaption(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(P.SUB)
        setPadding(0, dp(4), 0, 0)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun timeLabel(text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(P.FAINT)
        gravity = Gravity.CENTER
        setPadding(0, dp(4), 0, dp(14))
    }

    private fun footerNote(): TextView = TextView(act).apply {
        text = "数据源：本机 Hermes 面板 · 点任意卡片进入完整工作台"
        textSize = 11f
        setTextColor(P.FAINT)
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun linkRow(text: String, onClick: () -> Unit): TextView = TextView(act).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(P.TEXT)
        setPadding(0, dp(11), 0, dp(11))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun settingRow(title: String, value: String, onClick: () -> Unit): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(act).apply {
            text = title; textSize = 15.5f; setTextColor(P.TEXT)
        }, LinearLayout.LayoutParams(0, W, 1f))
        row.addView(TextView(act).apply {
            text = "$value  ›"; textSize = 12.5f; setTextColor(P.SUB)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(W, W))
        return row
    }

    private fun divider(): View = View(act).apply {
        background = ColorDrawable(P.DIVIDER)
        layoutParams = LinearLayout.LayoutParams(M, dp(1))
    }

    private fun progressRow(): View {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(View(act).apply {
            background = rounded(P.TRACK, 4)
            layoutParams = LinearLayout.LayoutParams(0, dp(7), 1f)
        })
        row.addView(View(act).apply {
            background = rounded(P.TEXT, 4)
            layoutParams = LinearLayout.LayoutParams(0, dp(7), 0.35f)
        })
        return row
    }

    private fun blackButton(text: String, onClick: () -> Unit): TextView = TextView(act).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rounded(P.BLACK, 22)
        setPadding(dp(16), dp(11), dp(16), dp(11))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun tripleRow(vararg items: Pair<String, () -> Unit>): View {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (label, action) ->
            val b = TextView(act).apply {
                text = label
                textSize = 12.5f
                setTextColor(P.TEXT)
                gravity = Gravity.CENTER
                background = rounded(P.SOFT, 16)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isClickable = true
                setOnClickListener { action() }
            }
            row.addView(b, LinearLayout.LayoutParams(0, W, 1f).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
        }
        return row
    }

    private fun pill(text: String, bg: Int, fg: Int, size: Float, onClick: () -> Unit): TextView =
        TextView(act).apply {
            this.text = text
            textSize = size
            setTextColor(fg)
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(bg, 21)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun roundButton(label: String, size: Int, bg: Int = P.SOFT, fg: Int = P.TEXT,
                            onClick: () -> Unit): TextView = TextView(act).apply {
        text = label
        textSize = if (label == "+") 23f else 17f
        gravity = Gravity.CENTER
        setTextColor(fg)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bg)
        }
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun space(v: Int): View = View(act).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(v))
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dateLine(): String =
        SimpleDateFormat("yyyy年M月d日  EEEE", Locale.CHINA).format(Date()) +
            "  ·  ${Runtime.title}"

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics,
    ).toInt()

    private fun toast(text: String) {
        Toast.makeText(act, text, Toast.LENGTH_SHORT).show()
    }

    /** 截图取色：顶部蓝紫 → 中部白 → 底部暖米。 */
    private object P {
        val CARD = Color.WHITE
        val GLASS = Color.argb(225, 255, 255, 255)
        val TAB_BG = Color.argb(205, 255, 255, 255)
        val TAB_ON = Color.parseColor("#DDE5F2")
        val SOFT = Color.parseColor("#F1F4FA")
        val BUBBLE_ME = Color.parseColor("#DCE7F8")
        val TRACK = Color.parseColor("#E7EBF3")
        val DIVIDER = Color.parseColor("#EEF1F7")
        val TEXT = Color.parseColor("#1B2235")
        val SUB = Color.parseColor("#8B92A6")
        val FAINT = Color.parseColor("#A6ACBA")
        val BLACK = Color.parseColor("#111318")
    }
}
