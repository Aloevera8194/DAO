package com.aloevera.dao

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object Preview {
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) DAO/3.0"

    fun canPreview(category: String): Boolean =
        category == "Áudio" || category == "Vídeo" || category == "Imagens"

    fun show(a: Activity, f: FoundFile, referer: String) {
        when (f.category) {
            "Áudio" -> audio(a, f, referer)
            "Vídeo" -> video(a, f, referer)
            "Imagens" -> image(a, f, referer)
        }
    }

    private fun headers(referer: String): HashMap<String, String> {
        val h = HashMap<String, String>()
        h["User-Agent"] = UA
        if (referer.isNotEmpty()) h["Referer"] = referer
        return h
    }

    private fun newBox(a: Activity, name: String): LinearLayout {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(a.dp(20), a.dp(16), a.dp(20), a.dp(8))
        val title = TextView(a)
        title.text = name
        title.textSize = 16f
        box.addView(title)
        return box
    }

    private fun audio(a: Activity, f: FoundFile, referer: String) {
        val box = newBox(a, f.name)
        val info = TextView(a)
        info.text = "Carregando prévia…"
        info.setPadding(0, a.dp(8), 0, a.dp(8))
        box.addView(info)
        val seek = SeekBar(a)
        box.addView(seek)
        val btn = Button(a)
        btn.text = "▶"
        btn.isEnabled = false
        box.addView(btn)

        val mp = MediaPlayer()
        val handler = Handler(Looper.getMainLooper())
        var released = false
        val updater = object : Runnable {
            override fun run() {
                if (released) return
                try {
                    if (mp.isPlaying) seek.progress = mp.currentPosition
                } catch (e: Exception) {
                }
                handler.postDelayed(this, 500)
            }
        }
        try {
            mp.setDataSource(a, Uri.parse(f.url), headers(referer))
            mp.setOnPreparedListener {
                seek.max = it.duration
                info.text = "Tocando prévia"
                btn.isEnabled = true
                btn.text = "⏸"
                it.start()
                handler.post(updater)
            }
            mp.setOnCompletionListener {
                btn.text = "▶"
                seek.progress = 0
            }
            mp.setOnErrorListener { _, _, _ ->
                info.text = "Não foi possível tocar esta prévia."
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            info.text = "Não foi possível tocar esta prévia."
        }
        btn.setOnClickListener {
            try {
                if (mp.isPlaying) {
                    mp.pause()
                    btn.text = "▶"
                } else {
                    mp.start()
                    btn.text = "⏸"
                }
            } catch (e: Exception) {
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    try {
                        mp.seekTo(p)
                    } catch (e: Exception) {
                    }
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        AlertDialog.Builder(a)
            .setView(box)
            .setNegativeButton("Fechar", null)
            .setOnDismissListener {
                released = true
                handler.removeCallbacks(updater)
                try {
                    mp.release()
                } catch (e: Exception) {
                }
            }
            .show()
    }

    private fun video(a: Activity, f: FoundFile, referer: String) {
        val box = newBox(a, f.name)
        val info = TextView(a)
        info.text = "Carregando prévia…"
        info.setPadding(0, a.dp(8), 0, a.dp(8))
        val vv = VideoView(a)
        box.addView(vv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(240)))
        box.addView(info)
        val mc = MediaController(a)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setOnPreparedListener {
            info.text = ""
            it.start()
        }
        vv.setOnErrorListener { _, _, _ ->
            info.text = "Não foi possível reproduzir esta prévia."
            true
        }
        try {
            vv.setVideoURI(Uri.parse(f.url), headers(referer))
        } catch (e: Exception) {
            info.text = "Não foi possível reproduzir esta prévia."
        }
        AlertDialog.Builder(a)
            .setView(box)
            .setNegativeButton("Fechar", null)
            .setOnDismissListener {
                try {
                    vv.stopPlayback()
                } catch (e: Exception) {
                }
            }
            .show()
    }

    private fun image(a: Activity, f: FoundFile, referer: String) {
        val box = newBox(a, f.name)
        val iv = ImageView(a)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        box.addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(380)))
        val info = TextView(a)
        info.text = "Carregando imagem…"
        box.addView(info)
        var dismissed = false
        Thread {
            val bmp = try {
                loadBitmap(f.url, referer)
            } catch (e: Exception) {
                null
            }
            a.runOnUiThread {
                if (!dismissed) {
                    if (bmp != null) {
                        iv.setImageBitmap(bmp)
                        info.text = ""
                    } else {
                        info.text = "Não foi possível carregar a imagem."
                    }
                }
            }
        }.start()
        AlertDialog.Builder(a)
            .setView(box)
            .setNegativeButton("Fechar", null)
            .setOnDismissListener { dismissed = true }
            .show()
    }

    private fun loadBitmap(first: String, referer: String): Bitmap? {
        var current = first
        for (i in 0 until 6) {
            val c = URL(current).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", UA)
                if (referer.isNotEmpty()) c.setRequestProperty("Referer", referer)
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return null
                    current = URL(URL(current), loc).toString()
                    continue
                }
                if (code !in 200..299) return null
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                c.inputStream.use { ins ->
                    while (total < 12_000_000) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                }
                val bytes = out.toByteArray()
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / s > 1600 || o.outHeight / s > 1600) s *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = s
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
            } finally {
                c.disconnect()
            }
        }
        return null
    }
}
