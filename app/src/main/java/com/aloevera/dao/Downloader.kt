package com.aloevera.dao

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLongArray

enum class State { QUEUED, RUNNING, PAUSED, SAVING, DONE, ERROR, CANCELED, CONFLICT, SKIPPED }

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
    @Volatile var decision: OnExists? = null
    @Volatile var conflictChecked = false

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

    private val storeLock = Any()

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        Settings.init(c)
        load(c)
    }

    private fun partsDir(c: Context): File {
        val dir = c.getExternalFilesDir("partes") ?: File(c.filesDir, "partes")
        dir.mkdirs()
        return dir
    }

    // ------------------------------------------------------------ persistência

    /** Grava a lista de downloads em disco, para sobreviver ao fechamento do app. */
    fun persist() {
        val c = app ?: return
        synchronized(storeLock) {
            try {
                val arr = JSONArray()
                for (t in tasks) {
                    if (t.state == State.CANCELED) continue
                    val o = JSONObject()
                    o.put("id", t.id)
                    o.put("url", t.url)
                    o.put("name", t.name)
                    o.put("relPath", t.relPath)
                    o.put("host", t.host)
                    o.put("referer", t.referer)
                    o.put("category", t.category)
                    o.put("state", t.state.name)
                    o.put("total", t.total)
                    o.put("finalUrl", t.finalUrl)
                    o.put("ranges", t.ranges)
                    o.put("error", t.error ?: JSONObject.NULL)
                    o.put("decision", t.decision?.name ?: JSONObject.NULL)
                    o.put("destLabel", t.destLabel)
                    o.put("savedUri", t.savedUri?.toString() ?: JSONObject.NULL)
                    val starts = t.segStart
                    val ends = t.segEnd
                    val done = t.segDone
                    val n = minOf(starts.size, ends.size, done.length())
                    if (t.ranges && n > 0) {
                        val ss = JSONArray()
                        val se = JSONArray()
                        val sd = JSONArray()
                        for (i in 0 until n) {
                            ss.put(starts[i])
                            se.put(ends[i])
                            sd.put(done.get(i))
                        }
                        o.put("segStart", ss)
                        o.put("segEnd", se)
                        o.put("segDone", sd)
                    }
                    arr.put(o)
                }
                val root = JSONObject()
                root.put("nextId", nextId)
                root.put("tasks", arr)
                val tmp = File(c.filesDir, "tasks.json.tmp")
                tmp.writeText(root.toString())
                tmp.renameTo(File(c.filesDir, "tasks.json"))
            } catch (e: Exception) {
                // não deixa um erro de gravação derrubar o download
            }
        }
    }

    private fun load(c: Context) {
        val f = File(c.filesDir, "tasks.json")
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            nextId = root.optInt("nextId", 1)
            val arr = root.optJSONArray("tasks") ?: return
            val dir = partsDir(c)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = DlTask(
                    o.getInt("id"), o.getString("url"), o.getString("name"),
                    o.getString("relPath"), o.getString("host"),
                    o.getString("referer"), o.getString("category")
                )
                var st = try {
                    State.valueOf(o.getString("state"))
                } catch (e: Exception) {
                    State.PAUSED
                }
                // O que estava em andamento quando o app fechou volta como "pausado".
                if (st == State.QUEUED || st == State.RUNNING || st == State.SAVING) st = State.PAUSED
                t.total = o.optLong("total", -1L)
                t.finalUrl = o.optString("finalUrl", t.url)
                t.ranges = o.optBoolean("ranges", false)
                t.error = if (o.isNull("error")) null else o.optString("error")
                t.decision = if (o.isNull("decision")) null else try {
                    OnExists.valueOf(o.getString("decision"))
                } catch (e: Exception) {
                    null
                }
                t.destLabel = o.optString("destLabel", "")
                t.savedUri = if (o.isNull("savedUri")) null else Uri.parse(o.getString("savedUri"))

                if (st != State.DONE && st != State.SKIPPED && t.ranges && o.has("segStart")) {
                    val ss = o.getJSONArray("segStart")
                    val se = o.getJSONArray("segEnd")
                    val sd = o.getJSONArray("segDone")
                    val n = ss.length()
                    val part = File(dir, "${t.id}.part")
                    // Só retoma de onde parou se o arquivo parcial ainda estiver íntegro.
                    if (n > 0 && se.length() == n && sd.length() == n && part.exists() && part.length() == t.total) {
                        t.segStart = LongArray(n) { ss.getLong(it) }
                        t.segEnd = LongArray(n) { se.getLong(it) }
                        val ad = AtomicLongArray(n)
                        for (k in 0 until n) ad.set(k, sd.getLong(k))
                        t.segDone = ad
                        t.tmp = part
                    }
                }
                t.state = st
                tasks.add(t)
                if (t.id >= nextId) nextId = t.id + 1
            }
        } catch (e: Exception) {
            // arquivo corrompido: começa com a lista vazia
        }
    }

    fun addAll(files: List<FoundFile>, host: String, referer: String) {
        synchronized(this) {
            for (f in files) {
                val t = DlTask(nextId++, f.url, f.name, f.relPath, host, referer, f.category)
                tasks.add(t)
                pool.execute { runTask(t) }
            }
        }
        persist()
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
        persist()
    }

    fun pauseAll() {
        for (t in tasks) pause(t)
    }

    fun resumeAll() {
        for (t in tasks) resume(t)
    }

    /** Resposta do usuário quando o arquivo já existe (substituir / manter os dois / pular). */
    fun resolve(t: DlTask, choice: OnExists) {
        synchronized(t) {
            if (t.state != State.CONFLICT) return
            if (choice == OnExists.SKIP || choice == OnExists.ASK) {
                t.tmp?.delete()
                t.tmp = null
                t.state = State.SKIPPED
                persist()
                return
            }
            t.decision = choice
            t.conflictChecked = true
            t.stopFlag = false
            t.state = State.QUEUED
        }
        pool.execute { runTask(t) }
        startService()
        persist()
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
        persist()
    }

    fun cancel(t: DlTask) {
        synchronized(t) {
            if (t.state == State.SAVING || t.state == State.DONE ||
                t.state == State.CANCELED || t.state == State.SKIPPED
            ) return
            t.cancelFlag = true
            if (t.state != State.RUNNING) {
                t.tmp?.delete()
                t.state = State.CANCELED
            }
        }
        persist()
    }

    fun clearFinished() {
        tasks.removeIf { it.state == State.DONE || it.state == State.CANCELED || it.state == State.SKIPPED }
        persist()
    }

    private fun finishCancel(t: DlTask) {
        t.tmp?.delete()
        t.state = State.CANCELED
    }

    private fun runTask(t: DlTask) {
        runTaskInner(t)
        persist()
    }

    /** Devolve true se a tarefa já foi resolvida (ignorada ou esperando resposta do usuário). */
    private fun checkConflict(t: DlTask): Boolean {
        val c = app ?: throw IOException("app não iniciado")
        if (Storage.exists(c, Storage.targetOf(t))) {
            when (val policy = t.decision ?: Settings.onExists) {
                OnExists.SKIP -> {
                    t.tmp?.delete()
                    t.tmp = null
                    t.state = State.SKIPPED
                    return true
                }
                OnExists.ASK -> {
                    t.state = State.CONFLICT
                    return true
                }
                OnExists.RENAME, OnExists.OVERWRITE -> t.decision = policy
            }
        }
        t.conflictChecked = true
        return false
    }

    private fun runTaskInner(t: DlTask) {
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
            if (!t.conflictChecked && checkConflict(t)) return
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
            val c = app ?: throw IOException("app não iniciado")
            val tmpFile = t.tmp ?: throw IOException("arquivo temporário perdido")
            val overwrite = (t.decision ?: Settings.onExists) == OnExists.OVERWRITE
            val saved = Storage.save(c, Storage.targetOf(t), tmpFile, overwrite)
            t.savedUri = saved.uri
            t.destLabel = saved.label
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
        val tmp = File(partsDir(c), "${t.id}.part")
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
}
