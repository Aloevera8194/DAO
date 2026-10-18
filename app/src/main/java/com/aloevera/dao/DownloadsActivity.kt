package com.aloevera.dao

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class DownloadsActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapter: TaskAdapter
    private lateinit var summary: TextView

    private val tick = object : Runnable {
        override fun run() {
            for (t in Downloader.tasks) t.sampleSpeed()
            adapter.notifyDataSetChanged()
            updateSummary()
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Downloader.init(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(14), dp(14), dp(14))
        root.setBackgroundColor(Color.WHITE)

        val title = TextView(this)
        title.text = "Downloads do DAO"
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.parseColor("#00796B"))
        root.addView(title)

        summary = TextView(this)
        summary.setPadding(0, dp(4), 0, dp(6))
        root.addView(summary)

        val btnClear = Button(this)
        btnClear.text = "Limpar concluídos"
        btnClear.setOnClickListener {
            Downloader.clearFinished()
            adapter.notifyDataSetChanged()
        }
        root.addView(btnClear)

        val list = ListView(this)
        adapter = TaskAdapter()
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun updateSummary() {
        val all = Downloader.tasks
        val running = all.count { it.state == State.RUNNING }
        val queued = all.count { it.state == State.QUEUED }
        val done = all.count { it.state == State.DONE }
        val err = all.count { it.state == State.ERROR }
        summary.text = "$running baixando · $queued na fila · $done concluídos · $err com erro"
    }

    private fun openFile(t: DlTask) {
        val uri = t.savedUri
        if (uri == null) {
            Toast.makeText(this, "Arquivo não encontrado", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, contentResolver.getType(uri))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Nenhum app para abrir este arquivo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildRow(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(4), dp(10), dp(4), dp(10))

        val name = TextView(this)
        name.textSize = 15f
        name.setTypeface(null, Typeface.BOLD)
        box.addView(name)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val st = TextView(this)
        st.textSize = 12f
        st.setTextColor(Color.DKGRAY)
        box.addView(st)

        val btns = LinearLayout(this)
        btns.orientation = LinearLayout.HORIZONTAL
        val bPause = Button(this)
        bPause.textSize = 12f
        btns.addView(bPause)
        val bCancel = Button(this)
        bCancel.textSize = 12f
        bCancel.text = "Cancelar"
        btns.addView(bCancel)
        val bOpen = Button(this)
        bOpen.textSize = 12f
        bOpen.text = "Abrir"
        btns.addView(bOpen)
        box.addView(btns)
        return box
    }

    private inner class TaskAdapter : BaseAdapter() {
        override fun getCount(): Int = Downloader.tasks.size
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val t = Downloader.tasks.getOrNull(position) ?: return convertView ?: View(this@DownloadsActivity)
            val box = (convertView as? LinearLayout) ?: buildRow()
            val name = box.getChildAt(0) as TextView
            val bar = box.getChildAt(1) as ProgressBar
            val st = box.getChildAt(2) as TextView
            val btns = box.getChildAt(3) as LinearLayout
            val bPause = btns.getChildAt(0) as Button
            val bCancel = btns.getChildAt(1) as Button
            val bOpen = btns.getChildAt(2) as Button

            name.text = t.name
            val pct = t.percent()
            val indeterminate = t.state == State.RUNNING && t.total <= 0
            bar.isIndeterminate = indeterminate
            if (!indeterminate) bar.progress = pct

            st.text = when (t.state) {
                State.QUEUED -> "Na fila"
                State.RUNNING -> {
                    val tot = if (t.total > 0) fmtBytes(t.total) else "?"
                    val conns = if (t.ranges) " · ${t.segStart.size} conexões" else ""
                    "$pct% · ${fmtBytes(t.downloaded())} de $tot · ${fmtBytes(t.speed)}/s$conns"
                }
                State.PAUSED -> "Pausado · $pct%"
                State.SAVING -> "Salvando no celular…"
                State.DONE -> "Concluído · ${fmtBytes(t.total)} · ${t.destLabel}"
                State.ERROR -> "Erro: ${t.error ?: "desconhecido"}"
                State.CANCELED -> "Cancelado"
            }

            val canAct = t.state == State.QUEUED || t.state == State.RUNNING ||
                t.state == State.PAUSED || t.state == State.ERROR
            bPause.visibility = if (canAct) View.VISIBLE else View.GONE
            bCancel.visibility = if (canAct) View.VISIBLE else View.GONE
            bOpen.visibility = if (t.state == State.DONE && t.savedUri != null) View.VISIBLE else View.GONE
            bPause.text = when (t.state) {
                State.PAUSED -> "Continuar"
                State.ERROR -> "Tentar de novo"
                else -> "Pausar"
            }
            bPause.setOnClickListener {
                if (t.state == State.PAUSED || t.state == State.ERROR) Downloader.resume(t) else Downloader.pause(t)
                notifyDataSetChanged()
            }
            bCancel.setOnClickListener {
                Downloader.cancel(t)
                notifyDataSetChanged()
            }
            bOpen.setOnClickListener { openFile(t) }
            return box
        }
    }
}
