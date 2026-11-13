package ai.hermes.agent

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import android.system.OsConstants
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 承载 Hermes Agent 的 proot 进程。
 *
 * 与 Kimi Code 那套的区别：Hermes 自己的 Web 面板就是完整客户端（聊天、会话、
 * 技能、定时、配置全在里面），所以这里只负责「把 Python 服务拉起来」，
 * 不做任何配置注入。
 */
object ProotRuntime {

    private var process: Process? = null

    @Volatile private var stopping = false

    @Volatile private var lastExit: Int? = null

    @Volatile private var activeWorkdir: String? = null

    fun lastExitCode(): Int? = lastExit

    fun isRunning(): Boolean = process?.isAlive == true

    fun currentWorkdir(context: Context): String = activeWorkdir ?: Prefs.workspace(context)

    fun start(context: Context, port: Int): Process {
        stop()

        require(Paths.prootBin(context).isFile) { "proot 未安装" }
        require(Paths.prootLoader(context).isFile) { "proot loader 未安装" }
        require(File(Paths.rootfs(context), "opt/hermes/start.sh").isFile) { "运行环境未安装" }

        writeResolvConf(context)
        val workdir = prepareWorkspace(context)
        val host = if (Prefs.lan(context)) "0.0.0.0" else "127.0.0.1"
        val extra = buildList {
            add("HERMES_PORT=$port")
            add("HERMES_BIND=$host")
            add("HERMES_WORKDIR=$workdir")
            add("HERMES_YOLO=${if (Prefs.yolo(context)) "1" else "0"}")
        }

        Runtime.log("启动 proot：端口 $port（监听 $host）")
        stopping = false
        lastExit = null
        val started = runCatching {
            proot(context, listOf("/bin/bash", "/opt/hermes/start.sh"), extra)
        }.getOrElse { error ->
            throw IllegalStateException("无法启动 proot：${error.message}", error)
        }
        process = started
        pumpOutput(started)
        return started
    }

    /**
     * 准备宿主机工作目录，并在 rootfs 里补出同名的 bind 挂载点。少了挂载点，
     * proot 的绑定会被静默跳过，表现是 Agent 看不到手机上的文件。
     */
    private fun prepareWorkspace(context: Context): String {
        val preferred = Prefs.workspace(context)
        if (preferred.startsWith("/storage/")) {
            val real = File(preferred)
            val usable = real.isDirectory || runCatching { real.mkdirs() }.getOrDefault(false)
            if (usable) {
                ensureGuestDir(context, preferred)
                activeWorkdir = preferred
                return preferred
            }
            Runtime.log("无法创建 $preferred（存储权限未授予？），本次回退到 /root/workspace")
        }
        ensureGuestDir(context, "/root/workspace")
        activeWorkdir = "/root/workspace"
        return "/root/workspace"
    }

    private fun ensureGuestDir(context: Context, guestPath: String) {
        runCatching { File(Paths.rootfs(context), guestPath.trimStart('/')).mkdirs() }
    }

    private fun proot(context: Context, entry: List<String>, env: List<String>): Process {
        val prootDir = Paths.prootDir(context)
        val rootfs = Paths.rootfs(context)

        val command = buildList {
            add(Paths.prootBin(context).absolutePath)
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            val external = File("/storage/emulated/0")
            if (external.isDirectory) {
                add("-b")
                add(external.absolutePath)
            }
            add("-w")
            add("/root")
            add("/usr/bin/env")
            add("-i")
            add("HOME=/root")
            add("HERMES_HOME=/root/.hermes")
            add("PYTHONPATH=/opt/hermes/lib")
            add("PATH=/opt/hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("LC_ALL=C.UTF-8")
            add("PYTHONUNBUFFERED=1")
            add("PYTHONDONTWRITEBYTECODE=1")
            addAll(env)
            addAll(entry)
        }

        val builder = ProcessBuilder(command)
        builder.directory(prootDir)
        builder.redirectErrorStream(true)
        val environment = builder.environment()
        environment.clear()
        environment["LD_LIBRARY_PATH"] = prootDir.absolutePath
        environment["PROOT_LOADER"] = Paths.prootLoader(context).absolutePath
        environment["PROOT_TMP_DIR"] = Paths.tmpDir(context).absolutePath
        environment["PROOT_NO_SECCOMP"] = "1"
        environment["HOME"] = prootDir.absolutePath
        environment["TMPDIR"] = Paths.tmpDir(context).absolutePath
        return builder.start()
    }

    private fun pidOf(target: Process): Int {
        val viaMethod = runCatching {
            (target.javaClass.getMethod("pid").invoke(target) as? Number)?.toInt()
        }.getOrNull()
        if (viaMethod != null && viaMethod > 0) return viaMethod
        return runCatching {
            val field = target.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(target)
        }.getOrDefault(-1)
    }

    /** proot 用 ptrace 带子进程；父进程死了子进程会被孤儿化并继续占着端口。 */
    private fun descendantPids(rootPid: Int): Set<Int> {
        if (rootPid <= 0) return emptySet()
        val found = LinkedHashSet<Int>()
        val queue = java.util.ArrayDeque<Int>()
        queue.add(rootPid)
        var guard = 0
        while (!queue.isEmpty() && guard++ < 256) {
            val pid = queue.poll() ?: break
            val taskDir = File("/proc/$pid/task")
            val tids = taskDir.list() ?: continue
            for (tid in tids) {
                val children = runCatching { File(taskDir, "$tid/children").readText().trim() }
                    .getOrNull() ?: continue
                if (children.isEmpty()) continue
                for (token in children.split(Regex("\\s+"))) {
                    val child = token.toIntOrNull() ?: continue
                    if (child <= 1 || child == android.os.Process.myPid()) continue
                    if (found.add(child)) queue.add(child)
                }
            }
        }
        return found
    }

    private fun killTree(pids: Set<Int>) {
        pids.forEach { pid -> runCatching { Os.kill(pid, OsConstants.SIGKILL) } }
    }

    fun stop() {
        val current = process ?: return
        stopping = true
        Thread {
            val deadline = System.currentTimeMillis() + 900
            while (current.isAlive && System.currentTimeMillis() < deadline) {
                runCatching { Thread.sleep(80) }
            }
            killTree(descendantPids(pidOf(current)))
            runCatching { current.destroy() }
            runCatching { Thread.sleep(300) }
            runCatching { if (current.isAlive) current.destroyForcibly() }
        }.apply { isDaemon = true }.start()
        process = null
    }

    /** 同步变体，重启前用。先回收整棵进程树，端口才能立刻释放。 */
    fun stopAndWait(timeoutMs: Long) {
        stopping = true
        val current = process
        process = null
        if (current == null) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (current.isAlive && System.currentTimeMillis() < deadline) {
            runCatching { Thread.sleep(50) }
        }
        killTree(descendantPids(pidOf(current)))
        runCatching { current.destroy() }
        val hardDeadline = System.currentTimeMillis() + 600
        while (current.isAlive && System.currentTimeMillis() < hardDeadline) {
            runCatching { Thread.sleep(30) }
        }
        if (current.isAlive) runCatching { current.destroyForcibly() }
        runCatching { Thread.sleep(150) }
    }

    fun shouldRestart(): Boolean = !stopping

    private fun pumpOutput(target: Process) {
        Thread {
            runCatching {
                BufferedReader(InputStreamReader(target.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) Runtime.log(line)
                    }
                }
            }
            val code = runCatching { target.waitFor() }.getOrDefault(-1)
            lastExit = code
            Runtime.log("proot 进程退出，退出码 $code")
        }.apply { isDaemon = true }.start()
    }

    /** Android 没有 /etc/resolv.conf，按当前网络的 DNS 生成一份。 */
    fun writeResolvConf(context: Context) {
        val servers = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.activeNetwork?.let { network ->
                manager.getLinkProperties(network)?.dnsServers
                    ?.mapNotNull { it.hostAddress }
                    ?.filter { it.isNotEmpty() }
            }
        }.getOrNull().orEmpty()

        val effective = if (servers.isNullOrEmpty()) listOf("8.8.8.8", "1.1.1.1") else servers
        val content = buildString {
            effective.forEach { appendLine("nameserver $it") }
            appendLine("nameserver 8.8.8.8")
            appendLine("nameserver 223.5.5.5")
        }
        runCatching {
            val file = File(Paths.rootfs(context), "etc/resolv.conf")
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }

    fun isPortOpen(port: Int, host: String = "127.0.0.1", timeoutMs: Int = 500): Boolean {
        return runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            }
            true
        }.getOrDefault(false)
    }

    /** 轮询到面板返回 200 为止；手机上冷启动 Python 可能要十几秒。 */
    fun awaitReady(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) return false
            if (httpOk(port)) return true
            runCatching { Thread.sleep(600) }
        }
        return false
    }

    private fun httpOk(port: Int): Boolean = runCatching {
        val connection = java.net.URL("http://127.0.0.1:$port/").openConnection()
            as java.net.HttpURLConnection
        connection.connectTimeout = 1500
        connection.readTimeout = 2500
        connection.requestMethod = "GET"
        val code = connection.responseCode
        connection.disconnect()
        code in 200..399
    }.getOrDefault(false)
}
