package ai.hermes.agent

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream

/**
 * Minimal streaming tar extractor for the Kimi Code rootfs archive.
 *
 * Supports the formats GNU tar actually produces for our image: ustar, GNU
 * long name / long link entries, and pax extended headers. Device nodes and
 * fifos are ignored. Paths are validated so an archive entry can never escape
 * the destination directory.
 */
object TarExtractor {

    class Progress(val files: Int, val bytes: Long)


    private const val BLOCK = 512

    fun extractGzip(archive: File, destination: File, onProgress: (Progress) -> Unit) {
        archive.inputStream().use { extractGzip(it, destination, onProgress) }
    }

    /**
     * Accepts either a gzip-compressed tar or a plain tar. AGP un-gzips
     * `*.tar.gz` assets while packaging the APK, so the bundled archive is a
     * raw tar stream even though the source file is gzipped.
     */
    fun extractGzip(source: InputStream, destination: File, onProgress: (Progress) -> Unit) {
        val buffered = BufferedInputStream(source, 1 shl 16)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val gzipped = first == 0x1f && second == 0x8b
        if (gzipped) {
            GZIPInputStream(buffered, 1 shl 16).use { extract(it, destination, onProgress) }
        } else {
            extract(buffered, destination, onProgress)
        }
    }

    private fun extract(input: InputStream, destination: File, onProgress: (Progress) -> Unit) {
        val header = ByteArray(BLOCK)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var pendingPaxPath: String? = null
        var pendingPaxLink: String? = null
        var files = 0
        var bytes = 0L

        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break

            val name = readString(header, 0, 100)
            val mode = readOctal(header, 100, 8)
            val size = readSize(header, 124, 12)
            val type = header[156].toInt().toChar().toString()
            val linkName = readString(header, 157, 100)
            val magic = readString(header, 257, 6)
            val prefix = if (magic.startsWith("ustar")) readString(header, 345, 155) else ""

            val content = if (type == "L" || type == "K" || type == "x" || type == "g") {
                readEntryData(input, size)
            } else {
                null
            }

            when (type) {
                "L" -> {
                    pendingLongName = content?.toString(Charsets.UTF_8)?.trimEnd('\u0000')
                    skipPadding(input, size)
                    continue
                }
                "K" -> {
                    pendingLongLink = content?.toString(Charsets.UTF_8)?.trimEnd('\u0000')
                    skipPadding(input, size)
                    continue
                }
                "x", "g" -> {
                    content?.let { pax ->
                        val text = pax.toString(Charsets.UTF_8)
                        pendingPaxPath = parsePax(text, "path") ?: pendingPaxPath
                        pendingPaxLink = parsePax(text, "linkpath") ?: pendingPaxLink
                    }
                    skipPadding(input, size)
                    continue
                }
            }

            val fullName = pendingPaxPath
                ?: pendingLongName
                ?: if (prefix.isNotEmpty()) "$prefix/$name" else name
            val fullLink = pendingPaxLink ?: pendingLongLink ?: linkName
            pendingPaxPath = null
            pendingPaxLink = null
            pendingLongName = null
            pendingLongLink = null

            if (fullName.isEmpty()) {
                skipData(input, size)
                continue
            }

            val target = resolveSafe(destination, fullName)
            if (target == null) {
                Runtime.log("跳过非法路径条目: $fullName")
                skipData(input, size)
                continue
            }

            when (type) {
                "5" -> {
                    target.mkdirs()
                    skipData(input, size)
                }
                "2" -> {
                    target.parentFile?.mkdirs()
                    deleteIfExists(target)
                    val linkTarget = File(fullLink)
                    // Symlink targets are stored relative to the archive root.
                    val resolved = if (linkTarget.isAbsolute) {
                        File(destination, fullLink)
                    } else {
                        File(target.parentFile, fullLink)
                    }
                    val normalized = resolved.canonicalFile
                    if (!isInside(destination, normalized)) {
                        Runtime.log("跳过越界符号链接: $fullName -> $fullLink")
                        skipData(input, size)
                        continue
                    }
                    runCatching {
                        Files.createSymbolicLink(target.toPath(), Paths.get(fullLink))
                    }.onFailure { createCopyFallback(destination, fullLink, target) }
                    skipData(input, size)
                }
                "1" -> {
                    target.parentFile?.mkdirs()
                    deleteIfExists(target)
                    val source = resolveSafe(destination, fullLink)
                    if (source != null && source.isFile) {
                        runCatching {
                            Files.createLink(target.toPath(), source.toPath())
                        }.onFailure {
                            runCatching { Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                        }
                    }
                    skipData(input, size)
                }
                "0", "\u0000", "7" -> {
                    target.parentFile?.mkdirs()
                    deleteIfExists(target)
                    FileOutputStream(target).use { output ->
                        copyExactly(input, output, size)
                    }
                    applyMode(target, mode)
                    files++
                    bytes += size
                    if (files % 25 == 0) onProgress(Progress(files, bytes))
                    skipPadding(input, size)
                }
                else -> skipData(input, size)
            }
        }
        onProgress(Progress(files, bytes))
    }

    private fun createCopyFallback(destination: File, linkName: String, target: File) {
        val source = resolveSafe(destination, linkName)
        if (source != null && source.exists()) {
            runCatching {
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun deleteIfExists(target: File) {
        runCatching {
            if (Files.isSymbolicLink(target.toPath()) || target.exists()) {
                Files.deleteIfExists(target.toPath())
            }
        }
    }

    private fun isInside(root: File, candidate: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        return candidate.canonicalFile.toPath().startsWith(rootPath)
    }

    /** Resolve an archive entry path, returning null when it would escape [root]. */
    private fun resolveSafe(root: File, entryName: String): File? {
        val cleaned = entryName.removePrefix("./").removePrefix("/")
        if (cleaned.isEmpty()) return null
        val candidate = File(root, cleaned)
        return if (isInside(root, candidate)) candidate else null
    }

    private fun applyMode(target: File, mode: Int) {
        // Archive modes assume a real owner; the app always owns what it
        // extracted, so only the executable bit carries real meaning here.
        val executable = (mode and 0b001_001_001) != 0
        if (executable) {
            target.setExecutable(true, false)
        } else if (target.canExecute()) {
            target.setExecutable(false, false)
        }
    }

    private fun readEntryData(input: InputStream, size: Long): ByteArray {
        if (size <= 0L) return ByteArray(0)
        require(size <= Int.MAX_VALUE) { "archive entry too large: $size" }
        val data = ByteArray(size.toInt())
        var read = 0
        while (read < size.toInt()) {
            val n = input.read(data, read, size.toInt() - read)
            if (n <= 0) break
            read += n
        }
        return data
    }

    private fun skipData(input: InputStream, size: Long) {
        if (size <= 0L) return
        var remaining = size
        val buffer = ByteArray(1 shl 16)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n <= 0) break
            remaining -= n
        }
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        if (size <= 0L) return
        val padding = (BLOCK - (size % BLOCK)) % BLOCK
        if (padding > 0) {
            var remaining = padding
            while (remaining > 0) {
                val n = input.skip(remaining)
                if (n <= 0) {
                    if (input.read() < 0) return
                    remaining -= 1
                } else {
                    remaining -= n
                }
            }
        }
    }

    private fun copyExactly(input: InputStream, output: FileOutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(1 shl 16)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n <= 0) break
            output.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Int {
        return readSize(header, offset, length).toInt()
    }

    private fun readSize(header: ByteArray, offset: Int, length: Int): Long {
        // GNU base-256 encoding for values that do not fit in the octal field.
        if ((header[offset].toInt() and 0x80) != 0) {
            var value = 0L
            value = (header[offset].toLong() and 0x7f)
            for (i in 1 until length) {
                value = (value shl 8) or (header[offset + i].toLong() and 0xFF)
            }
            return value
        }
        var value = 0L
        var seen = false
        for (i in 0 until length) {
            val b = header[offset + i].toInt()
            if (b == 0 || b == ' '.code) {
                if (seen) break else continue
            }
            if (b < '0'.code || b > '7'.code) continue
            seen = true
            value = value * 8 + (b - '0'.code)
        }
        return value
    }

    private fun parsePax(text: String, key: String): String? {
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) return null
            val length = text.substring(index, space).toIntOrNull() ?: return null
            if (length <= 0 || index + length > text.length) return null
            val record = text.substring(space + 1, index + length - 1)
            val equals = record.indexOf('=')
            if (equals > 0) {
                val recordKey = record.substring(0, equals)
                if (recordKey == key) return record.substring(equals + 1)
            }
            index += length
        }
        return null
    }
}
