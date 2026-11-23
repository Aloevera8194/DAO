package com.aloevera.dao

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import java.io.File
import java.io.IOException
import java.util.Properties

/** Para onde vai um arquivo, já com os nomes limpos. */
class Target(
    val dirs: List<String>,
    val fileName: String,
    val mime: String,
    val category: String,
    val host: String
)

class Saved(val uri: Uri?, val label: String)

class SftpConn(val session: Session, val ch: ChannelSftp) {
    fun close() {
        try {
            ch.disconnect()
        } catch (e: Exception) {
        }
        try {
            session.disconnect()
        } catch (e: Exception) {
        }
    }
}

object Storage {

    fun safe(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)

    fun targetOf(t: DlTask): Target {
        val parts = t.relPath.split("/")
            .map { safe(it) }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        val fileName = parts.lastOrNull() ?: safe(t.name).ifEmpty { "arquivo" }
        val dirs = if (parts.size > 1) parts.dropLast(1) else emptyList()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        return Target(dirs, fileName, mime, t.category, t.host)
    }

    private fun hostDirs(tg: Target): List<String> =
        if (Settings.hostFolder) listOf(safe(tg.host)).filter { it.isNotEmpty() } else emptyList()

    private fun uniqueName(name: String, taken: (String) -> Boolean): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val cand = "$stem ($i)$ext"
            if (!taken(cand)) return cand
            i++
        }
    }

    // ---------------------------------------------------------------- API

    fun exists(c: Context, tg: Target): Boolean = when (Settings.mode) {
        DestMode.PHONE -> mediaFindAny(c, tg, tg.fileName) != null
        DestMode.FOLDER -> folderHas(c, tg, tg.fileName)
        DestMode.SFTP -> withSftp { ch -> sftpHas(ch, sftpDir(tg) + "/" + tg.fileName) }
    }

    fun save(c: Context, tg: Target, src: File, overwrite: Boolean): Saved = when (Settings.mode) {
        DestMode.PHONE -> savePhone(c, tg, src, overwrite)
        DestMode.FOLDER -> saveFolder(c, tg, src, overwrite)
        DestMode.SFTP -> saveSftp(tg, src, overwrite)
    }

    // ---------------------------------------------------------------- celular (MediaStore)

    private fun mediaTarget(category: String, mime: String): Pair<Uri, String> {
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

    private fun relPathOf(base: String, tg: Target): String =
        (listOf(base, "DAO") + hostDirs(tg) + tg.dirs).joinToString("/") + "/"

    private fun mediaPlaces(tg: Target): List<Pair<Uri, String>> {
        val (col, base) = mediaTarget(tg.category, tg.mime)
        val list = arrayListOf(Pair(col, relPathOf(base, tg)))
        val dl = Pair(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            relPathOf(Environment.DIRECTORY_DOWNLOADS, tg)
        )
        if (dl != list[0]) list.add(dl)
        return list
    }

    private fun mediaFind(c: Context, collection: Uri, rel: String, name: String): Uri? {
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        c.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID), sel, arrayOf(rel, name), null
        )?.use {
            if (it.moveToFirst()) return ContentUris.withAppendedId(collection, it.getLong(0))
        }
        return null
    }

    private fun mediaFindAny(c: Context, tg: Target, name: String): Uri? {
        for ((col, rel) in mediaPlaces(tg)) {
            val u = mediaFind(c, col, rel, name)
            if (u != null) return u
        }
        return null
    }

    private fun insertEntry(c: Context, place: Pair<Uri, String>, name: String, mime: String): Uri? {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, place.second)
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        return c.contentResolver.insert(place.first, values)
    }

    private fun savePhone(c: Context, tg: Target, src: File, overwrite: Boolean): Saved {
        val resolver = c.contentResolver
        var name = tg.fileName
        val existing = mediaFindAny(c, tg, name)
        if (existing != null) {
            var removed = 0
            if (overwrite) {
                removed = try {
                    resolver.delete(existing, null, null)
                } catch (e: Exception) {
                    0
                }
            }
            // Não deu para substituir (ou o usuário quer manter os dois): usa nome novo.
            if (removed == 0) name = uniqueName(name) { mediaFindAny(c, tg, it) != null }
        }

        var used = mediaPlaces(tg)[0]
        var uri: Uri? = try {
            insertEntry(c, used, name, tg.mime)
        } catch (e: Exception) {
            null
        }
        if (uri == null) {
            used = Pair(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                relPathOf(Environment.DIRECTORY_DOWNLOADS, tg)
            )
            uri = insertEntry(c, used, name, tg.mime)
        }
        val finalUri = uri ?: throw IOException("Não consegui criar o arquivo no celular")
        try {
            val out = resolver.openOutputStream(finalUri) ?: throw IOException("Não consegui gravar o arquivo")
            out.use { o -> src.inputStream().use { it.copyTo(o, 65536) } }
            val done = ContentValues()
            done.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(finalUri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(finalUri, null, null)
            throw e
        }
        return Saved(finalUri, used.second.trimEnd('/'))
    }

    // ---------------------------------------------------------------- pasta escolhida (SAF)

    private fun folderRoot(c: Context): DocumentFile {
        val s = Settings.folderUri
        if (s.isEmpty()) throw IOException("Nenhuma pasta escolhida. Abra as Configurações e escolha uma.")
        val root = DocumentFile.fromTreeUri(c, Uri.parse(s))
            ?: throw IOException("Pasta escolhida inválida")
        if (!root.exists() || !root.canWrite()) {
            throw IOException("Sem acesso à pasta escolhida. Escolha a pasta de novo nas Configurações.")
        }
        return root
    }

    private fun folderDir(root: DocumentFile, dirs: List<String>, create: Boolean): DocumentFile? {
        var d = root
        for (p in dirs) {
            val f = d.findFile(p)
            if (f != null && f.isDirectory) {
                d = f
            } else if (create) {
                d = d.createDirectory(p) ?: throw IOException("Não consegui criar a pasta $p")
            } else {
                return null
            }
        }
        return d
    }

    private fun folderHas(c: Context, tg: Target, name: String): Boolean {
        val root = folderRoot(c)
        val dir = folderDir(root, hostDirs(tg) + tg.dirs, false) ?: return false
        return dir.findFile(name) != null
    }

    private fun saveFolder(c: Context, tg: Target, src: File, overwrite: Boolean): Saved {
        val root = folderRoot(c)
        val dirs = hostDirs(tg) + tg.dirs
        val dir = folderDir(root, dirs, true) ?: throw IOException("Pasta de destino indisponível")
        var name = tg.fileName
        val existing = dir.findFile(name)
        if (existing != null) {
            if (overwrite && existing.delete()) {
                // substituído
            } else {
                name = uniqueName(name) { dir.findFile(it) != null }
            }
        }
        val f = dir.createFile(tg.mime, name) ?: throw IOException("Não consegui criar o arquivo na pasta")
        try {
            val out = c.contentResolver.openOutputStream(f.uri, "wt")
                ?: throw IOException("Não consegui gravar o arquivo")
            out.use { o -> src.inputStream().use { it.copyTo(o, 65536) } }
        } catch (e: Exception) {
            f.delete()
            throw e
        }
        val label = (listOf(Settings.folderName.ifEmpty { "Pasta escolhida" }) + dirs).joinToString("/")
        return Saved(f.uri, label)
    }

    // ---------------------------------------------------------------- servidor SFTP (Raspberry Pi)

    fun openSftp(): SftpConn {
        val host = Settings.sftpHost
        if (host.isEmpty()) throw IOException("Configure o servidor (SFTP) nas Configurações")
        val jsch = JSch()
        val s = jsch.getSession(Settings.sftpUser, host, Settings.sftpPort)
        s.setPassword(Settings.sftpPass)
        val cfg = Properties()
        // Rede doméstica: não exige confirmar a "impressão digital" do servidor.
        cfg.setProperty("StrictHostKeyChecking", "no")
        s.setConfig(cfg)
        try {
            s.connect(10000)
            val ch = s.openChannel("sftp") as ChannelSftp
            ch.connect(10000)
            return SftpConn(s, ch)
        } catch (e: Exception) {
            try {
                s.disconnect()
            } catch (e2: Exception) {
            }
            val msg = e.message ?: e.javaClass.simpleName
            throw IOException(
                if (msg.contains("Auth fail", true)) "Usuário ou senha do servidor incorretos"
                else "Servidor: $msg"
            )
        }
    }

    fun <T> withSftp(block: (ChannelSftp) -> T): T {
        val conn = openSftp()
        try {
            return block(conn.ch)
        } finally {
            conn.close()
        }
    }

    private fun sftpDir(tg: Target): String {
        val base = Settings.sftpPath.ifEmpty { "." }.trimEnd('/')
        val sub = (hostDirs(tg) + tg.dirs).joinToString("/")
        return if (sub.isEmpty()) base.ifEmpty { "/" } else "$base/$sub"
    }

    private fun sftpHas(ch: ChannelSftp, path: String): Boolean {
        return try {
            ch.stat(path)
            true
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
        }
    }

    private fun sftpMkdirs(ch: ChannelSftp, path: String) {
        val abs = path.startsWith("/")
        var cur = ""
        for (p in path.split("/").filter { it.isNotEmpty() }) {
            cur = if (cur.isEmpty()) (if (abs) "/$p" else p) else "$cur/$p"
            if (!sftpHas(ch, cur)) ch.mkdir(cur)
        }
    }

    private fun saveSftp(tg: Target, src: File, overwrite: Boolean): Saved = withSftp { ch ->
        val dir = sftpDir(tg)
        sftpMkdirs(ch, dir)
        var name = tg.fileName
        if (sftpHas(ch, "$dir/$name")) {
            if (overwrite) {
                ch.rm("$dir/$name")
            } else {
                name = uniqueName(name) { sftpHas(ch, "$dir/$it") }
            }
        }
        val finalPath = "$dir/$name"
        val part = "$finalPath.dao-part"
        // Envia com nome temporário e só renomeia no fim: nunca sobra arquivo pela metade.
        src.inputStream().use { ch.put(it, part, ChannelSftp.OVERWRITE) }
        ch.rename(part, finalPath)
        Saved(null, "${Settings.sftpUser}@${Settings.sftpHost}:$dir")
    }

    /** Usado pelo botão "Testar conexão". */
    fun testSftp(): String = withSftp { ch ->
        val path = Settings.sftpPath.ifEmpty { "." }
        sftpMkdirs(ch, path)
        "Conectado! Pasta pronta: $path"
    }
}
