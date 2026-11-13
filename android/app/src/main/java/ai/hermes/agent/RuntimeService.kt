package ai.hermes.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 拥有整套本地栈：首次解压运行环境、拉起 proot、等面板就绪、常驻前台。
 *
 * 重启是最容易出问题的地方：WebView 的 localStorage 按 `host:port` 隔离，
 * 一旦换了端口，登录态和会话列表看起来就"清空"了。所以重启必须复用同一个
 * 端口，见 [RestartPolicy]。stop/restart 的等待全部放在后台线程，onStartCommand
 * 跑在主线程，阻塞几秒就会让整个界面卡住。
 */
class RuntimeService : Service() {

    companion object {
        const val ACTION_START = "ai.hermes.agent.START"
        const val ACTION_STOP = "ai.hermes.agent.STOP"
        const val ACTION_RESTART = "ai.hermes.agent.RESTART"

        private const val CHANNEL_ID = "hermes_runtime"
        private const val NOTIFICATION_ID = 73
        private const val PORT_RELEASE_TIMEOUT_MS = 3_000L
        private const val STOP_TIMEOUT_MS = 2_000L
        private const val READY_TIMEOUT_MS = 180_000L

        fun start(context: Context) {
            val intent = Intent(context, RuntimeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RuntimeService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        fun restart(context: Context) {
            Runtime.update(Runtime.Phase.STARTING, "正在重启本地服务", "正在停止旧进程…", -1)
            val intent = Intent(context, RuntimeService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @Volatile private var wantStop = false
    @Volatile private var worker: Thread? = null

    /** 每次 stop/restart 自增，旧 worker 发不现自己的世代就自行退出。 */
    @Volatile private var generation = 0L
    private var port = Paths.DEFAULT_PORT

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在准备运行环境…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val epoch = ++generation
                wantStop = true
                Runtime.update(Runtime.Phase.STOPPED, "正在停止", "")
                Thread({
                    ProotRuntime.stopAndWait(STOP_TIMEOUT_MS)
                    worker?.let { runCatching { it.interrupt(); it.join(1000) } }
                    if (epoch == generation) {
                        Runtime.update(Runtime.Phase.STOPPED, "已停止", "点击启动重新运行")
                        stopForegroundCompat()
                        stopSelf()
                    }
                }, "hermes-stop").apply { isDaemon = true }.start()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                val epoch = ++generation
                wantStop = true
                Runtime.update(Runtime.Phase.STARTING, "正在重启本地服务", "正在停止旧进程…", -1)
                Thread({
                    ProotRuntime.stopAndWait(STOP_TIMEOUT_MS)
                    worker?.let { runCatching { it.interrupt(); it.join(1000) } }
                    if (epoch == generation) startWorker(epoch)
                }, "hermes-restart").apply { isDaemon = true }.start()
                return START_STICKY
            }
            else -> Unit
        }

        if (worker?.isAlive != true) startWorker()
        return START_STICKY
    }

    private fun startWorker(expectedEpoch: Long = generation) {
        if (expectedEpoch != generation) return
        wantStop = false
        val epoch = generation
        worker = Thread({ runLoop(epoch) }, "hermes-runtime").apply {
            isDaemon = true
            start()
        }
    }

    private fun runLoop(epoch: Long) {
        var consecutiveFailures = 0
        while (!wantStop && epoch == generation) {
            try {
                if (!Paths.isRuntimeInstalled(this)) {
                    Runtime.update(Runtime.Phase.INSTALLING, "首次安装运行环境", "解压内置镜像，约需两三分钟", 0)
                    notify("首次安装运行环境")
                    Installer.installProotRuntime(this)
                    Installer.installRootfs(this)
                } else {
                    Installer.installProotRuntime(this)
                }

                Runtime.update(Runtime.Phase.STARTING, "正在启动 Hermes", "首次启动要预热 Python，请稍候", -1)
                notify("正在启动 Hermes")
                port = pickPort()
                ProotRuntime.start(this, port)

                if (!ProotRuntime.awaitReady(port, READY_TIMEOUT_MS)) {
                    throw IllegalStateException(explainLaunchFailure())
                }
                val url = "http://127.0.0.1:$port/"
                Runtime.markReady(port, url)
                Runtime.log("Hermes 面板已就绪：$url")
                notify("运行中 · 端口 $port")
                consecutiveFailures = 0

                while (!wantStop && epoch == generation && ProotRuntime.isRunning()) {
                    Thread.sleep(1500)
                }
                if (wantStop || epoch != generation) break
                Runtime.log("服务已退出，3 秒后自动重启")
                Thread.sleep(3000)
            } catch (error: InterruptedException) {
                break
            } catch (error: Throwable) {
                if (epoch != generation) break
                consecutiveFailures++
                val message = error.message ?: error.javaClass.simpleName
                Runtime.log("启动失败：$message")
                Runtime.update(Runtime.Phase.ERROR, "启动失败", message)
                notify("启动失败：$message")
                if (consecutiveFailures >= 3) {
                    Runtime.log("连续失败 3 次，已停止重试")
                    wantStop = true
                    break
                }
                runCatching { Thread.sleep(4000) }
            }
        }
        if (epoch == generation) ProotRuntime.stop()
    }

    /** 把 proot 的原始死法翻译成用户能处理的话。 */
    private fun explainLaunchFailure(): String {
        val logs = Runtime.snapshotLogs().joinToString("\n")
        val code = ProotRuntime.lastExitCode()
        return when {
            logs.contains("ModuleNotFoundError") ->
                "缺少 Python 模块，运行环境可能没解压完整，请重新安装"
            logs.contains("library") && logs.contains("not found") ->
                "缺少运行库（proot 无法加载），请重新安装"
            logs.contains("Permission denied") ->
                "proot 无法执行：请检查存储权限或重新安装"
            code == 126 || code == 127 -> "proot 无法执行（退出码 $code）"
            code == 139 || code == 11 -> "proot 崩溃（段错误），此机型暂不兼容"
            code != null -> "服务未能启动（proot 退出码 $code）"
            else -> "启动超时。首次启动较慢，可点重试；仍失败请导出日志"
        }
    }

    private fun pickPort(): Int {
        val pinned = Prefs.port(this)
        val chosen = RestartPolicy.portToUse(pinned, Paths.DEFAULT_PORT) { candidate ->
            !ProotRuntime.isPortOpen(candidate)
        }
        if (chosen != pinned) Prefs.setPort(this, chosen)
        if (!waitForPortFree(chosen, PORT_RELEASE_TIMEOUT_MS)) {
            Runtime.log("端口 $chosen 仍被占用，保持同一端口重试（换端口会清空聊天记录）")
        }
        return chosen
    }

    private fun waitForPortFree(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!ProotRuntime.isPortOpen(port)) return true
            runCatching { Thread.sleep(100) }
        }
        return !ProotRuntime.isPortOpen(port)
    }

    private fun notify(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Hermes Agent 本地运行时状态"
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        generation++
        wantStop = true
        ProotRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
