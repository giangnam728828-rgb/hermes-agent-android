package ai.hermes.agent

import android.content.Context
import java.io.File

/**
 * 所有落盘路径的唯一出口。与 Kimi Code 那套完全独立：目录名、包名、端口、
 * 根文件系统内容都不共用，避免两个 App 互相干扰。
 */
object Paths {
    /** 默认端口。与 Hermes 官方 dashboard 一致，方便对照文档排查。 */
    const val DEFAULT_PORT = 9119

    fun rootfs(context: Context): File = File(context.filesDir, "rootfs")
    fun prootDir(context: Context): File = File(context.filesDir, "proot")
    fun tmpDir(context: Context): File = File(context.filesDir, "tmp")
    fun logsDir(context: Context): File = File(context.filesDir, "logs")

    fun prootBin(context: Context): File = File(prootDir(context), "proot")
    fun prootLoader(context: Context): File = File(prootDir(context), "loader")

    /** 这版镜像里 Hermes 自己的安装位置。 */
    fun hermesHome(context: Context): File = File(rootfs(context), "root/.hermes")

    fun isRuntimeInstalled(context: Context): Boolean {
        val marker = File(rootfs(context), ".installed")
        val entry = File(rootfs(context), "opt/hermes/start.sh")
        val dist = File(rootfs(context), "opt/hermes/lib/hermes_cli/main.py")
        return marker.isFile && entry.isFile && dist.isFile
    }

    fun ensureDirs(context: Context) {
        listOf(rootfs(context), prootDir(context), tmpDir(context), logsDir(context)).forEach {
            if (!it.exists()) it.mkdirs()
        }
    }
}
