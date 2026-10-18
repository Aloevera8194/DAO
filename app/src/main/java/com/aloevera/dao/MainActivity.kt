package com.aloevera.dao

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

sealed class Row {
    data class Header(val category: String, val items: List<FoundFile>) : Row()
    data class Item(val file: FoundFile) : Row()
}

class MainActivity : Activity() {

    private val accent = Color.parseColor("#00796B")
    private lateinit var urlInput: EditText
    private lateinit var filterInput: EditText
    private lateinit var btnScan: Button
    private lateinit var btnDownload: Button
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var adapter: RowAdapter

    private val rows = ArrayList<Row>()
    private val selected = HashSet<String>()
    private var allFiles: List<FoundFile> = emptyList()
    private var visibleFiles: List<FoundFile> = emptyList()
    private var crawler: Crawler? = null
    private var scanning = false
    private var currentHost = ""
    private var startUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Downloader.init(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(14), dp(14), dp(14))
        root.setBackgroundColor(Color.WHITE)

        val title = TextView(this)
        title.text = "DAO · Acelerador de downloads"
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(accent)
        root.addView(title)

        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        urlInput = EditText(this)
        urlInput.hint = "Cole o link do site ou da pasta"
        urlInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlInput.setSingleLine(true)
        inputRow.addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btnPaste = Button(this)
        btnPaste.text = "Colar"
        btnPaste.setOnClickListener { pasteFromClipboard() }
        inputRow.addView(btnPaste)
        root.addView(inputRow)

        btnScan = Button(this)
        btnScan.text = "Analisar"
        btnScan.setOnClickListener { startScan() }
        root.addView(btnScan)

        status = TextView(this)
        status.text = "Cole um link e toque em Analisar."
        status.setPadding(0, dp(6), 0, dp(6))
        root.addView(status)

        filterInput = EditText(this)
        filterInput.hint = "Filtrar por nome ou tipo (ex.: mp3, flac)"
        filterInput.setSingleLine(true)
        filterInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        root.addView(filterInput)

        val markRow = LinearLayout(this)
        markRow.orientation = LinearLayout.HORIZONTAL
        val btnMark = Button(this)
        btnMark.text = "Marcar visíveis"
        btnMark.setOnClickListener {
            visibleFiles.forEach { selected.add(it.url) }
            adapter.notifyDataSetChanged()
            updateButton()
        }
        markRow.addView(btnMark, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btnClearMarks = Button(this)
        btnClearMarks.text = "Limpar marcas"
        btnClearMarks.setOnClickListener {
            selected.clear()
            adapter.notifyDataSetChanged()
            updateButton()
        }
        markRow.addView(btnClearMarks, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(markRow)

        list = ListView(this)
        adapter = RowAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            when (val r = rows[position]) {
                is Row.Header -> {
                    val all = r.items.all { selected.contains(it.url) }
                    if (all) r.items.forEach { selected.remove(it.url) }
                    else r.items.forEach { selected.add(it.url) }
                }
                is Row.Item -> {
                    if (!selected.add(r.file.url)) selected.remove(r.file.url)
                }
            }
            adapter.notifyDataSetChanged()
            updateButton()
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        btnDownload = Button(this)
        btnDownload.setOnClickListener { downloadSelected() }
        bottom.addView(btnDownload, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val btnView = Button(this)
        btnView.text = "Downloads"
        btnView.setOnClickListener { startActivity(Intent(this, DownloadsActivity::class.java)) }
        bottom.addView(btnView)
        root.addView(bottom)

        setContentView(root)
        updateButton()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i != null && i.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) {
                val m = Regex("https?://\\S+").find(t)
                urlInput.setText(m?.value ?: t.trim())
            }
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrBlank()) toast("Nada copiado") else urlInput.setText(t.trim())
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun updateButton() {
        btnDownload.text = "Baixar selecionados (${selected.size})"
    }

    private fun startScan() {
        if (scanning) {
            crawler?.cancelled = true
            status.text = "Parando…"
            return
        }
        var u = urlInput.text.toString().trim()
        if (u.isEmpty()) {
            toast("Cole um link primeiro")
            return
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        startUrl = u

        val c = Crawler(u) { msg -> runOnUiThread { status.text = msg } }
        crawler = c
        scanning = true
        btnScan.text = "Parar"
        allFiles = emptyList()
        selected.clear()
        applyFilter()
        status.text = "Analisando…"

        Thread {
            var err: String? = null
            val result: List<FoundFile> = try {
                c.run()
            } catch (e: Exception) {
                err = e.message ?: "erro desconhecido"
                emptyList()
            }
            runOnUiThread {
                scanning = false
                btnScan.text = "Analisar"
                currentHost = c.host
                allFiles = result
                applyFilter()
                status.text = when {
                    err != null -> "Erro: $err"
                    result.isEmpty() -> "Nenhum arquivo encontrado. O site pode exigir login ou carregar tudo por JavaScript."
                    else -> "${result.size} arquivos encontrados. Toque para marcar."
                }
            }
        }.start()
    }

    private fun applyFilter() {
        val tokens = filterInput.text.toString().lowercase()
            .split(",")
            .map { it.trim().trimStart('.') }
            .filter { it.isNotEmpty() }
        val visible = if (tokens.isEmpty()) allFiles else allFiles.filter { f ->
            val n = f.relPath.lowercase()
            tokens.any { n.contains(it) }
        }
        visibleFiles = visible
        rows.clear()
        val grouped = visible.groupBy { it.category }
        for (cat in FileTypes.order) {
            val items = grouped[cat] ?: continue
            rows.add(Row.Header(cat, items))
            for (f in items.sortedBy { it.relPath.lowercase() }) rows.add(Row.Item(f))
        }
        adapter.notifyDataSetChanged()
        updateButton()
    }

    private fun downloadSelected() {
        val chosen = allFiles.filter { selected.contains(it.url) }
        if (chosen.isEmpty()) {
            toast("Marque ao menos um arquivo")
            return
        }
        Downloader.addAll(chosen, currentHost, startUrl)
        selected.clear()
        adapter.notifyDataSetChanged()
        updateButton()
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            return when (val row = rows[position]) {
                is Row.Header -> {
                    val cb = (convertView as? CheckBox) ?: CheckBox(this@MainActivity).also {
                        it.isClickable = false
                        it.isFocusable = false
                        it.textSize = 16f
                        it.setTypeface(null, Typeface.BOLD)
                        it.setBackgroundColor(Color.parseColor("#E0F2F1"))
                        it.setPadding(dp(8), dp(12), dp(8), dp(12))
                    }
                    cb.text = "${row.category} (${row.items.size})"
                    cb.isChecked = row.items.all { selected.contains(it.url) }
                    cb
                }
                is Row.Item -> {
                    val box = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).also {
                        it.orientation = LinearLayout.VERTICAL
                        it.setPadding(dp(8), dp(6), dp(8), dp(6))
                        val cb = CheckBox(this@MainActivity)
                        cb.isClickable = false
                        cb.isFocusable = false
                        cb.textSize = 15f
                        it.addView(cb)
                        val tv = TextView(this@MainActivity)
                        tv.textSize = 12f
                        tv.setTextColor(Color.GRAY)
                        tv.setPadding(dp(40), 0, 0, 0)
                        it.addView(tv)
                    }
                    val cb = box.getChildAt(0) as CheckBox
                    val tv = box.getChildAt(1) as TextView
                    cb.text = row.file.name
                    cb.isChecked = selected.contains(row.file.url)
                    tv.text = row.file.relPath
                    box
                }
            }
        }
    }
}
