package com.aloevera.dao

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLongArray

enum class State { QUEUED, RUNNING, PAUSED, SAVING, DONE, ERROR, CANCELED }

class DlTask(
    val id: Int,
    val url: String,
    val name: String,
    val relPath: String,
    val host: String,
    val referer: String,
    val category: String
) {
    @Volatile var state = State.QUEUED
    @Volatile var total = -1L
    @Volatile var finalUrl = url
    @Volatile var ranges = false
    @Volatile var error: String? = null
    @Volatile var segError: String? = null
    @Volatile var stopFlag = false
    @Volatile var cancelFlag = false
    @Volatile var tmp: File? = null
    @Volatile var segStart = LongArray(0)
    @Volatile var segEnd = LongArray(0)
    @Volatile var segDone = AtomicLongArray(0)
    @Volatile var plainDone = 0L
    @Volatile var plainFinished = false
    @Volatile var lastBytes = 0L
    @Volatile var lastTime = 0L
    @Volatile var speed = 0L
    @Volatile var destLabel = ""
    @Volatile var savedUri: Uri? = null
    @Volatile var savedUri: android.net.Uri? = null
    @Volatile var savedMime: String? = null
    @Volatile var savedLabel: String = ""

    fun downloaded(): Long {
        if (!ranges) return plainDone
        var s = 0L
        val arr = segDone
        for (i in 0 until arr.length()) s += arr.get(i)
        return s
    }

    fun percent(): Int {
        val t = total
        if (state == State.DONE) return 100
        return if (t > 0) ((downloaded() * 100) / t).toInt().coerceIn(0, 100) else 0
    }

    fun sampleSpeed() {
        val now = System.currentTimeMillis()
        val b = downloaded()
        if (lastTime == 0L) {
            lastTime = now
            lastBytes = b
            return
        }
        val dt = now - lastTime
        if (dt >= 800) {
            speed = ((b - lastBytes) * 1000 / dt).coerceAtLeast(0)
            lastBytes = b
            lastTime = now
        }
    }
}

object Downloader {
    val tasks = CopyOnWriteArrayList<DlTask>()
    var connections = 4

    private const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) DAO/3.0"
    private val pool = Executors.newFixedThreadPool(2)
    private var nextId = 1
    private var app: Context? = null

    fun init(c: Context) {
        app = c.applicationContext
    }

    fun addAll(files: List<FoundFile>, host: String, referer: String) {
        synchronized(this) {
            for (f in files) {
                val t = DlTask(nextId++, f.url, f.name, f.relPath, host, referer, f.category)
                tasks.add(t)
                pool.execute { runTask(t) }
            }
        }
        startService()
    }

    private fun startService() {
        val c = app ?: return
        c.startForegroundService(Intent(c, DownloadService::class.java))
    }

    fun pause(t: DlTask) {
        synchronized(t) {
            when (t.state) {
                State.QUEUED -> {
                    t.stopFlag = true
                    t.state = State.PAUSED
                }
                State.RUNNING -> t.stopFlag = true
                else -> {}
            }
        }
    }

    fun resume(t: DlTask) {
        synchronized(t) {
            if (t.state == State.PAUSED || t.state == State.ERROR) {
                t.stopFlag = false
                t.error = null
                t.state = State.QUEUED
            } else {
                return
            }
        }
        pool.execute { runTask(t) }
        startService()
    }

    fun cancel(t: DlTask) {
        synchronized(t) {
            if (t.state == State.SAVING || t.state == State.DONE || t.state == State.CANCELED) return
            t.cancelFlag = true
            if (t.state != State.RUNNING) {
                t.tmp?.delete()
                t.state = State.CANCELED
            }
        }
    }

    fun clearFinished() {
        tasks.removeIf { it.state == State.DONE || it.state == State.CANCELED }
    }

    private fun finishCancel(t: DlTask) {
        t.tmp?.delete()
        t.state = State.CANCELED
    }

    private fun runTask(t: DlTask) {
        synchronized(t) {
            if (t.cancelFlag) {
                t.state = State.CANCELED
                return
            }
            if (t.stopFlag) {
                if (t.state == State.QUEUED) t.state = State.PAUSED
                return
            }
            if (t.state != State.QUEUED) return
            t.state = State.RUNNING
        }
        t.segError = null
        try {
            if (t.tmp == null) prepare(t)
            if (t.cancelFlag) {
                finishCancel(t)
                return
            }
            if (t.ranges) transferSegments(t) else transferPlain(t)
            if (t.cancelFlag) {
                finishCancel(t)
                return
            }
            val complete = if (t.ranges) t.downloaded() >= t.total else t.plainFinished
            if (!complete) {
                if (t.stopFlag) {
                    t.state = State.PAUSED
                    return
                }
                throw IOException(t.segError ?: "download incompleto")
            }
            if (!t.ranges) t.total = t.plainDone
            t.state = State.SAVING
            saveToDownloads(t)
            t.tmp?.delete()
            t.state = State.DONE
        } catch (e: Exception) {
            if (t.cancelFlag) {
                finishCancel(t)
            } else {
                t.error = e.message ?: e.javaClass.simpleName
                t.state = State.ERROR
            }
        }
    }

    private class Opened(val conn: HttpURLConnection, val finalUrl: String)

    private fun open(url: String, referer: String, from: Long, to: Long): Opened {
        var current = url
        for (i in 0 until 6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Encoding", "identity")
            if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
            if (from >= 0) {
                c.setRequestProperty("Range", if (to >= 0) "bytes=$from-$to" else "bytes=$from-")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("Redirecionamento sem destino")
                current = URL(URL(current), loc).toString()
                continue
            }
            return Opened(c, current)
        }
        throw IOException("Redirecionamentos demais")
    }

    private fun prepare(t: DlTask) {
        val probe = open(t.url, t.referer, 0, 0)
        try {
            t.finalUrl = probe.finalUrl
            val code = probe.conn.responseCode
            if (code == 206) {
                val cr = probe.conn.getHeaderField("Content-Range") ?: ""
                val total = cr.substringAfterLast('/').trim().toLongOrNull() ?: -1L
                if (total > 0) {
                    t.total = total
                    t.ranges = true
                } else {
                    t.ranges = false
                }
            } else if (code in 200..299) {
                t.total = probe.conn.contentLengthLong
                t.ranges = false
            } else {
                throw IOException("HTTP $code")
            }
        } finally {
            probe.conn.disconnect()
        }

        val c = app ?: throw IOException("app não iniciado")
        val dir = c.getExternalFilesDir("partes") ?: File(c.filesDir, "partes")
        dir.mkdirs()
        val tmp = File(dir, "${t.id}.part")
        if (t.ranges) {
            RandomAccessFile(tmp, "rw").use { it.setLength(t.total) }
            val n = if (t.total < 2_000_000L) 1 else connections
            val chunk = (t.total + n - 1) / n
            val starts = ArrayList<Long>()
            val ends = ArrayList<Long>()
            for (i in 0 until n) {
                val s = i * chunk
                val e = minOf(t.total - 1, (i + 1) * chunk - 1)
                if (s <= e) {
                    starts.add(s)
                    ends.add(e)
                }
            }
            t.segStart = starts.toLongArray()
            t.segEnd = ends.toLongArray()
            t.segDone = AtomicLongArray(starts.size)
        } else {
            FileOutputStream(tmp).use { }
        }
        t.tmp = tmp
    }

    private fun transferSegments(t: DlTask) {
        val tmp = t.tmp ?: throw IOException("arquivo temporário perdido")
        val threads = ArrayList<Thread>()
        for (i in 0 until t.segStart.size) {
            val th = Thread { segmentLoop(t, i, tmp) }
            threads.add(th)
            th.start()
        }
        for (th in threads) th.join()
    }

    private fun segmentLoop(t: DlTask, i: Int, tmp: File) {
        val end = t.segEnd[i]
        var attempts = 0
        while (!t.stopFlag && !t.cancelFlag) {
            val pos = t.segStart[i] + t.segDone.get(i)
            if (pos > end) return
            try {
                val o = open(t.finalUrl, t.referer, pos, end)
                try {
                    val code = o.conn.responseCode
                    if (code != 206) throw IOException("O servidor recusou o trecho (HTTP $code)")
                    RandomAccessFile(tmp, "rw").use { raf ->
                        raf.seek(pos)
                        val buf = ByteArray(32768)
                        o.conn.inputStream.use { ins ->
                            while (!t.stopFlag && !t.cancelFlag) {
                                val remaining = end - (t.segStart[i] + t.segDone.get(i)) + 1
                                if (remaining <= 0) break
                                val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                t.segDone.addAndGet(i, n.toLong())
                            }
                        }
                    }
                } finally {
                    o.conn.disconnect()
                }
                attempts = 0
            } catch (e: Exception) {
                attempts++
                if (attempts >= 4) {
                    t.segError = e.message ?: e.javaClass.simpleName
                    return
                }
                try {
                    Thread.sleep(1500)
                } catch (ie: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun transferPlain(t: DlTask) {
        t.plainDone = 0L
        t.plainFinished = false
        val tmp = t.tmp ?: throw IOException("arquivo temporário perdido")
        val o = open(t.finalUrl, t.referer, -1, -1)
        try {
            val code = o.conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            FileOutputStream(tmp).use { out ->
                o.conn.inputStream.use { ins ->
                    val buf = ByteArray(32768)
                    while (!t.stopFlag && !t.cancelFlag) {
                        val n = ins.read(buf)
                        if (n < 0) {
                            t.plainFinished = true
                            break
                        }
                        out.write(buf, 0, n)
                        t.plainDone += n
                    }
                }
            }
        } finally {
            o.conn.disconnect()
        }
    }

    private fun safe(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)

    private fun target(category: String, mime: String): Pair<Uri, String> {
        return when {
            category == "Áudio" && mime.startsWith("audio/") ->
                Pair(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC)
            category == "Vídeo" && mime.startsWith("video/") ->
                Pair(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES)
            category == "Imagens" && mime.startsWith("image/") ->
                Pair(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES)
            category == "Documentos" ->
                Pair(MediaStore.Files.getContentUri("external"), Environment.DIRECTORY_DOCUMENTS)
            else ->
                Pair(MediaStore.Downloads.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DOWNLOADS)
        }
    }

    private fun insertEntry(
        c: Context, collection: Uri, base: String, host: String,
        dirs: String, fileName: String, mime: String
    ): Uri? {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, base + "/DAO/" + safe(host) + "/" + dirs)
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        return c.contentResolver.insert(collection, values)
    }

    private fun saveToDownloads(t: DlTask) {
        val c = app ?: throw IOException("app não iniciado")
        val tmp = t.tmp ?: throw IOException("arquivo temporário perdido")
        val parts = t.relPath.split("/")
            .map { safe(it) }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        val fileName = parts.lastOrNull() ?: safe(t.name)
        val dirs = if (parts.size > 1) parts.dropLast(1).joinToString("/") + "/" else ""
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"

        val (collection, base) = target(t.category, mime)
        var usedBase = base
        var uri: Uri? = try {
            insertEntry(c, collection, base, t.host, dirs, fileName, mime)
        } catch (e: Exception) {
            null
        }
        if (uri == null) {
            usedBase = Environment.DIRECTORY_DOWNLOADS
            uri = insertEntry(
                c, MediaStore.Downloads.EXTERNAL_CONTENT_URI, usedBase,
                t.host, dirs, fileName, mime
            )
        }
        val finalUri = uri ?: throw IOException("Não consegui criar o arquivo no celular")
        val resolver = c.contentResolver
        try {
            val out = resolver.openOutputStream(finalUri) ?: throw IOException("Não consegui gravar o arquivo")
            out.use { o -> tmp.inputStream().use { it.copyTo(o, 65536) } }
            val done = ContentValues()
            done.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(finalUri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(finalUri, null, null)
            throw e
        }
        t.savedUri = finalUri
        t.destLabel = usedBase + "/DAO/" + safe(t.host)
    }
}
