package ai.hermes.agent

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 把 App 不能直接执行的两样东西铺到私有目录：proot 运行时和 Linux 根文件系统。
 * 发布包把 rootfs 打进 APK，首次启动不需要联网；下载分支只作为兜底。
 */
object Installer {

    const val ROOTFS_URL =
        "https://github.com/giangnam728828-rgb/hermes-agent-android/releases/download/runtime-v1/hermes-rootfs-arm64.tar.gz"

    private val BUNDLED_ROOTFS_ASSETS = listOf("hermes-rootfs-arm64.tar.gz", "hermes-rootfs-arm64.tar")

    private const val USER_AGENT = "HermesAgentAndroid/1.0"

    private val PROOT_ASSETS = listOf(
        Triple("proot", "proot", true),
        Triple("loader", "loader", true),
        Triple("libtalloc.so", "libtalloc.so.2", false),
        Triple("libtalloc.so", "libtalloc.so", false),
        Triple("libandroid-shmem.so", "libandroid-shmem.so", false),
    )

    fun installProotRuntime(context: Context) {
        val dir = Paths.prootDir(context)
        if (!dir.exists()) dir.mkdirs()
        for ((assetName, fileName, executable) in PROOT_ASSETS) {
            val target = File(dir, fileName)
            if (target.isFile && target.length() > 0) {
                target.setReadable(true, false)
                if (executable) target.setExecutable(true, false)
                continue
            }
            context.assets.open("proot/$assetName").use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 1 shl 16) }
            }
            target.setReadable(true, false)
            if (executable) target.setExecutable(true, false)
        }
        lockExecutables(context)
        Runtime.log("proot 运行时已就绪")
    }

    /**
     * Android 10+ 的 W^X 规则：可写的文件不能执行。私有目录本身可写，
     * 所以写完立刻去掉写位。
     */
    private fun lockExecutables(context: Context) {
        listOf(Paths.prootBin(context), Paths.prootLoader(context)).forEach { file ->
            runCatching {
                if (file.isFile && file.canWrite()) file.setWritable(false, false)
            }.onFailure { Runtime.log("无法锁定 ${file.name}: ${it.message}") }
        }
    }

    fun installRootfs(context: Context) {
        Paths.ensureDirs(context)
        if (Paths.isRuntimeInstalled(context)) {
            Runtime.log("运行时已安装，跳过安装")
            return
        }

        val bundled = bundledRootfsAsset(context)
        if (bundled != null) {
            Runtime.log("使用随 App 内置的运行环境")
            extract(context, bundled)
            return
        }

        val archive = File(Paths.tmpDir(context), "hermes-rootfs-arm64.tar.gz")
        if (!archive.isFile || archive.length() < 1024 * 1024) download(archive)
        else Runtime.log("复用已下载的安装包（${archive.length() / 1024 / 1024} MB）")
        extractFromFile(archive, context)
        archive.delete()
    }

    private fun extractFromFile(archive: File, context: Context) {
        val rootfs = prepareRootfs(context)
        var files = 0
        TarExtractor.extractGzip(archive, rootfs) { progress ->
            if (progress.files - files >= 150) {
                files = progress.files
                report(files)
            }
        }
        finish(rootfs)
    }

    private fun bundledRootfsAsset(context: Context): String? =
        BUNDLED_ROOTFS_ASSETS.firstOrNull { name ->
            runCatching { context.assets.open(name).use { }; true }.getOrDefault(false)
        }

    private fun extract(context: Context, assetName: String) {
        val rootfs = prepareRootfs(context)
        var files = 0
        context.assets.open(assetName).use { raw ->
            TarExtractor.extractGzip(raw, rootfs) { progress ->
                if (progress.files - files >= 150) {
                    files = progress.files
                    report(files)
                }
            }
        }
        finish(rootfs)
    }

    private fun prepareRootfs(context: Context): File {
        Runtime.update(Runtime.Phase.INSTALLING, "正在解压运行环境", "首次安装需要两到三分钟，请不要退出", 0)
        val rootfs = Paths.rootfs(context)
        if (rootfs.exists()) rootfs.deleteRecursively()
        rootfs.mkdirs()
        return rootfs
    }

    private fun report(files: Int) {
        Runtime.update(Runtime.Phase.INSTALLING, "正在解压运行环境", "已解压 $files 个文件", -1)
    }

    private fun finish(rootfs: File) {
        File(rootfs, ".installed").writeText(System.currentTimeMillis().toString())
        Runtime.log("运行环境安装完成")
        applyPermissions(rootfs)
    }

    private fun applyPermissions(rootfs: File) {
        listOf(
            "opt/hermes/start.sh",
            "opt/hermes/bin/hermes",
            "usr/bin/python3.12",
            "usr/bin/git",
            "usr/bin/curl",
            "usr/bin/rg",
            "usr/local/bin/rg",
            "bin/bash",
            "bin/sh",
            "usr/bin/env",
        ).forEach { relative ->
            val file = File(rootfs, relative)
            if (file.exists()) {
                file.setReadable(true, false)
                file.setWritable(true, false)
                file.setExecutable(true, false)
            }
        }
    }

    private fun download(target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        val partial = File(target.parentFile, target.name + ".part")
        if (partial.exists()) partial.delete()

        Runtime.update(Runtime.Phase.INSTALLING, "正在下载运行环境", "首次使用需要下载约 98 MB", 0)
        var connection: HttpURLConnection? = null
        try {
            connection = openFollowRedirects(URL(ROOTFS_URL))
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("下载失败：HTTP $code")
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastPercent = -1
            FileOutputStream(partial).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        output.write(buffer, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                Runtime.update(
                                    Runtime.Phase.INSTALLING,
                                    "正在下载运行环境",
                                    "${downloaded / 1024 / 1024} MB / ${total / 1024 / 1024} MB",
                                    percent,
                                )
                            }
                        }
                    }
                }
            }
            if (total > 0 && partial.length() != total) {
                throw IllegalStateException("下载不完整：${partial.length()} / $total")
            }
            if (!partial.renameTo(target)) throw IllegalStateException("无法保存安装包")
        } finally {
            connection?.disconnect()
        }
    }

    private fun openFollowRedirects(url: URL): HttpURLConnection {
        var current = url
        repeat(6) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrEmpty()) throw IllegalStateException("重定向地址缺失")
                current = URL(current, location)
                return@repeat
            }
            return connection
        }
        throw IllegalStateException("重定向次数过多")
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
