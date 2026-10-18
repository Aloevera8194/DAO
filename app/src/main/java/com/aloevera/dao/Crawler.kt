package com.aloevera.dao

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset

data class FoundFile(
    val url: String,
    val name: String,
    val relPath: String,
    val category: String
)

object FileTypes {
    val categories: Map<String, Set<String>> = linkedMapOf(
        "Documentos" to setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "odp", "epub", "rtf", "csv"),
        "Áudio" to setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "wma", "opus"),
        "Vídeo" to setOf("mp4", "mkv", "avi", "mov", "webm", "wmv", "flv", "3gp", "m4v"),
        "Imagens" to setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff", "heic"),
        "Compactados" to setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz"),
        "Programas" to setOf("apk", "exe", "msi", "iso", "deb", "dmg", "jar")
    )
    val order = listOf("Documentos", "Áudio", "Vídeo", "Imagens", "Compactados", "Programas", "Outros")
    val pages = setOf("html", "htm", "php", "asp", "aspx", "jsp", "shtml", "cgi", "xhtml")
    val ignore = setOf("css", "js", "ico", "svg", "woff", "woff2", "ttf", "eot", "map", "json", "xml", "rss", "atom")

    fun categoryOf(ext: String): String? {
        for ((name, exts) in categories) {
            if (ext in exts) return name
        }
        return null
    }
}

class Crawler(
    private val startUrl: String,
    private val onProgress: (String) -> Unit
) {
    @Volatile
    var cancelled = false
    var host: String = ""

    private val maxPages = 300
    private val maxDepth = 4
    private val maxBytes = 3_000_000
    private val ua = "Mozilla/5.0 (Linux; Android 13; Mobile) DAO/1.0"
    private val linkRegex = Regex("""(?i)(?:href|src)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    fun run(): List<FoundFile> {
        val start = URL(startUrl)
        host = start.host
        val startPath = if (start.path.isNullOrEmpty()) "/" else start.path
        val scopeDir = if (startPath.endsWith("/")) startPath else startPath.substringBeforeLast('/') + "/"
        val files = LinkedHashMap<String, FoundFile>()

        // Link direto para um arquivo
        val startExt = extOf(startPath)
        if (startExt != null && startExt !in FileTypes.pages && startExt !in FileTypes.ignore) {
            addFile(files, start, scopeDir, startExt)
            return files.values.toList()
        }

        val queue = java.util.ArrayDeque<Pair<String, Int>>()
        val visited = HashSet<String>()
        queue.add(Pair(startUrl, 0))
        visited.add(clean(startUrl))
        var pagesRead = 0

        while (queue.isNotEmpty() && !cancelled && pagesRead < maxPages) {
            val item = queue.poll() ?: break
            val pageUrl = item.first
            val depth = item.second
            pagesRead++
            onProgress("Lendo página $pagesRead… (${files.size} arquivos achados)")
            val html = fetch(pageUrl) ?: continue
            val base = try { URL(pageUrl) } catch (e: Exception) { continue }

            for (m in linkRegex.findAll(html)) {
                val raw = (m.groups[1]?.value ?: m.groups[2]?.value ?: "").trim().replace("&amp;", "&")
                if (raw.isEmpty() || raw.startsWith("#")) continue
                val link = try { URL(base, raw) } catch (e: Exception) { continue }
                if (link.protocol != "http" && link.protocol != "https") continue
                if (link.query != null && link.query.startsWith("C=")) continue // ordenação de pastas do Apache
                val path = link.path ?: ""
                val ext = extOf(path)

                if (path.endsWith("/") || ext == null || ext in FileTypes.pages) {
                    if (link.host == host && path.startsWith(scopeDir) && depth < maxDepth) {
                        val key = clean(link.toString())
                        if (visited.add(key)) queue.add(Pair(link.toString(), depth + 1))
                    }
                } else if (ext in FileTypes.ignore) {
                    // ignora css, js, fontes...
                } else {
                    addFile(files, link, scopeDir, ext)
                }
            }
        }
        return files.values.toList()
    }

    private fun addFile(files: MutableMap<String, FoundFile>, link: URL, scopeDir: String, ext: String) {
        val category = FileTypes.categoryOf(ext)
            ?: if (ext.length <= 5 && ext.all { it.isLetterOrDigit() }) "Outros" else return
        val key = clean(link.toString())
        if (files.containsKey(key)) return
        val path = link.path ?: return
        val name = decode(path.substringAfterLast('/'))
        if (name.isEmpty()) return
        val rel = if (link.host == host && path.startsWith(scopeDir)) {
            decode(path.substring(scopeDir.length))
        } else {
            "externos/" + link.host + decode(path)
        }
        files[key] = FoundFile(link.toString(), name, rel, category)
    }

    private fun clean(u: String): String = u.substringBefore('#')

    private fun extOf(path: String): String? {
        val name = path.substringAfterLast('/')
        if (!name.contains('.')) return null
        val ext = name.substringAfterLast('.').lowercase()
        return if (ext.isEmpty()) null else ext
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun fetch(first: String): String? {
        var current = first
        for (i in 0 until 6) {
            try {
                val c = URL(current).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 15000
                    c.readTimeout = 20000
                    c.instanceFollowRedirects = false
                    c.setRequestProperty("User-Agent", ua)
                    c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    val code = c.responseCode
                    if (code in 300..399) {
                        val loc = c.getHeaderField("Location") ?: return null
                        current = URL(URL(current), loc).toString()
                        continue
                    }
                    if (code !in 200..299) return null
                    val type = c.contentType ?: ""
                    if (type.isNotEmpty() && !type.contains("html", true) && !type.contains("text", true)) return null
                    val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
                    val charset: Charset = try {
                        if (cs != null) Charset.forName(cs) else Charsets.UTF_8
                    } catch (e: Exception) {
                        Charsets.UTF_8
                    }
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    c.inputStream.use { ins ->
                        while (out.size() < maxBytes) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    return String(out.toByteArray(), charset)
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }
}
