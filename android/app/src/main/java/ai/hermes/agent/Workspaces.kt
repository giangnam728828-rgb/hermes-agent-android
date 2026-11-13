package ai.hermes.agent

import android.content.Context
import java.io.File

/**
 * 原生工作区管理。
 *
 * 为什么需要它：面板的「工作区」列表来自 /api/chat/workspaces，依赖桌面端的
 * projects.db 与仓库发现缓存。安卓这边是 headless proot，没有桌面端，这两份
 * 数据永远是空的，所以网页里的下拉框只剩「默认」和「自定义路径」；而后端
 * resolve_chat_cwd() 又要求路径必须是真实存在的目录，随手填一个不存在的名字
 * 会直接 400。结果就是「新建不了工作区」。
 *
 * 这里绕开桌面数据源：直接在手机共享存储里创建真实目录（proot 已经把
 * /storage/emulated/0 绑定进容器，后端 isdir 判断能通过），再把路径写进面板
 * 的 localStorage 工作区键。目录可见、可选、可切换，功能链是完整的。
 */
object Workspaces {

    private const val PREF = "hermes_workspaces"
    private const val KEY_CURRENT = "current"
    private const val KEY_LIST = "list"

    /** 工作区根目录，跟随设置里的「工作目录」。 */
    fun root(context: Context): File = File(Prefs.workspace(context))

    private fun sp(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 已创建过的工作区绝对路径。 */
    fun list(context: Context): List<String> {
        val saved = sp(context).getString(KEY_LIST, "") ?: ""
        val fromPrefs = saved.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        // 目录可能被用户在文件管理器里删掉，顺手做一次存在性过滤。
        val existing = fromPrefs.filter { File(it).isDirectory }
        val onDisk = runCatching {
            root(context).listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.map { it.absolutePath }
                .orEmpty()
        }.getOrDefault(emptyList())
        return (existing + onDisk).distinct().sortedBy { File(it).name.lowercase() }
    }

    fun current(context: Context): String {
        val value = sp(context).getString(KEY_CURRENT, "") ?: ""
        return if (value.isNotEmpty() && File(value).isDirectory) value else ""
    }

    fun setCurrent(context: Context, path: String) {
        sp(context).edit().putString(KEY_CURRENT, path).apply()
    }

    fun displayName(path: String): String = File(path).name.ifEmpty { path }

    /**
     * 新建一个工作区目录。名字只允许中文/字母/数字/空格/下划线/连字符，
     * 避免出现路径分隔符把文件写到别处。
     */
    fun create(context: Context, rawName: String): Result<String> {
        val name = rawName.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("名字不能为空"))
        if (name.contains('/') || name.contains('\\') || name == "." || name == "..") {
            return Result.failure(IllegalArgumentException("名字里不能带路径符号"))
        }
        val dir = File(root(context), name)
        if (dir.exists()) return Result.failure(IllegalStateException("「$name」已经存在"))
        val ok = runCatching { dir.mkdirs() }.getOrDefault(false) || dir.isDirectory
        if (!ok) {
            return Result.failure(IllegalStateException("创建失败，请检查存储权限"))
        }
        // 容器里的同名挂载点也补一份，避免 proot bind 被静默跳过。
        runCatching { File(Paths.rootfs(context), dir.absolutePath.trimStart('/')).mkdirs() }
        val all = (list(context) + dir.absolutePath).distinct()
        sp(context).edit()
            .putString(KEY_LIST, all.joinToString("\n"))
            .putString(KEY_CURRENT, dir.absolutePath)
            .apply()
        return Result.success(dir.absolutePath)
    }
}
