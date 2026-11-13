package ai.hermes.agent

import android.content.Context

/**
 * 轻量设置面板，全部落在 SharedPreferences 里。刻意不做「供应商/模型」这类
 * 复杂配置——Hermes 自带完整的 Web 设置页（/config、/env、/models），
 * 原生这层只保留「跑起来所必需」的开关。
 */
object Prefs {
    private const val FILE = "hermes_prefs"

    private const val KEY_PORT = "port"
    private const val KEY_WORKSPACE = "workspace"
    private const val KEY_LAN = "lan"
    private const val KEY_KEEP_AWAKE = "keep_awake"
    private const val KEY_YOLO = "yolo"
    private const val KEY_LAST_VERSION = "last_version"

    /** 手机共享存储里的工作区，用户在文件管理器里能直接看到 Agent 改了什么。 */
    const val DEFAULT_WORKSPACE = "/storage/emulated/0/HermesAgent"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun port(context: Context): Int =
        sp(context).getInt(KEY_PORT, Paths.DEFAULT_PORT).coerceIn(1024, 65535)

    fun setPort(context: Context, port: Int) {
        sp(context).edit().putInt(KEY_PORT, port.coerceIn(1024, 65535)).apply()
    }

    fun workspace(context: Context): String =
        sp(context).getString(KEY_WORKSPACE, DEFAULT_WORKSPACE) ?: DEFAULT_WORKSPACE

    fun setWorkspace(context: Context, path: String) {
        sp(context).edit().putString(KEY_WORKSPACE, path.trim().ifEmpty { DEFAULT_WORKSPACE }).apply()
    }

    fun lan(context: Context): Boolean = sp(context).getBoolean(KEY_LAN, false)

    fun setLan(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_LAN, enabled).apply()
    }

    fun keepAwake(context: Context): Boolean = sp(context).getBoolean(KEY_KEEP_AWAKE, true)

    fun setKeepAwake(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_KEEP_AWAKE, enabled).apply()
    }

    /** 全自主执行：等价于 hermes 的 --yolo，不再逐条等工具批准。 */
    fun yolo(context: Context): Boolean = sp(context).getBoolean(KEY_YOLO, true)

    fun setYolo(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_YOLO, enabled).apply()
    }

    fun lastVersion(context: Context): String =
        sp(context).getString(KEY_LAST_VERSION, "") ?: ""

    fun setLastVersion(context: Context, version: String) {
        sp(context).edit().putString(KEY_LAST_VERSION, version).apply()
    }
}
