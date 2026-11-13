package ai.hermes.agent

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行时状态总线：Service 写，Activity 读。UI 只在主线程取快照。
 */
object Runtime {
    enum class Phase {
        IDLE,
        INSTALLING,
        STARTING,
        READY,
        ERROR,
        STOPPED,
    }

    @Volatile var phase: Phase = Phase.IDLE
    @Volatile var title: String = "尚未启动"
    @Volatile var detail: String = ""
    @Volatile var progress: Int = -1
    @Volatile var port: Int = 0
    @Volatile var workspaceUrl: String = ""

    /** 日志每变一行 +1，UI 用它跳过无意义的重绘。 */
    @Volatile var logVersion: Int = 0

    private const val MAX_LOG_LINES = 600
    private val logLines = ArrayDeque<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun snapshotLogs(): List<String> = synchronized(logLines) { logLines.toList() }

    fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
        logVersion++
        notifyChanged()
    }

    fun update(phase: Phase, title: String, detail: String = "", progress: Int = -1) {
        this.phase = phase
        this.title = title
        this.detail = detail
        this.progress = progress
        notifyChanged()
    }

    fun markReady(port: Int, url: String) {
        this.port = port
        this.workspaceUrl = url
        update(Phase.READY, "运行中", "Hermes 已就绪（端口 $port）")
    }

    // 解压/启动时日志可能每秒几百行，合并成每帧一次通知。
    private val notifyPending = AtomicBoolean(false)
    private val notifyRunnable = Runnable {
        notifyPending.set(false)
        listeners.forEach { it() }
    }

    fun notifyChanged() {
        if (listeners.isEmpty()) return
        if (notifyPending.compareAndSet(false, true)) {
            mainHandler.post(notifyRunnable)
        }
    }
}
