package com.aloevera.dao

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile

class SettingsActivity : Activity() {

    private val accent = Color.parseColor("#00796B")
    private val reqFolder = 41

    private lateinit var folderBox: LinearLayout
    private lateinit var sftpBox: LinearLayout
    private lateinit var folderInfo: TextView
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etPath: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.WHITE)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(14), dp(14), dp(24))
        scroll.addView(root)

        val title = TextView(this)
        title.text = "Configurações"
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(accent)
        root.addView(title)

        // ---------------- onde salvar
        root.addView(sectionTitle("Onde salvar"))
        val modeGroup = RadioGroup(this)
        modeGroup.orientation = RadioGroup.VERTICAL
        addRadio(modeGroup, "Pasta padrão do celular (Downloads, Música…)", Settings.mode == DestMode.PHONE) {
            Settings.mode = DestMode.PHONE
            updateVisibility()
        }
        addRadio(modeGroup, "Escolher uma pasta", Settings.mode == DestMode.FOLDER) {
            Settings.mode = DestMode.FOLDER
            updateVisibility()
        }
        addRadio(modeGroup, "Servidor na rede (Raspberry Pi, via SFTP)", Settings.mode == DestMode.SFTP) {
            Settings.mode = DestMode.SFTP
            updateVisibility()
        }
        root.addView(modeGroup)

        // ---- pasta escolhida
        folderBox = LinearLayout(this)
        folderBox.orientation = LinearLayout.VERTICAL
        val btnPick = Button(this)
        btnPick.text = "Escolher pasta…"
        btnPick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            i.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            startActivityForResult(i, reqFolder)
        }
        folderBox.addView(btnPick)
        folderInfo = TextView(this)
        folderInfo.setPadding(dp(4), 0, 0, dp(6))
        folderBox.addView(folderInfo)
        root.addView(folderBox)

        // ---- servidor SFTP
        sftpBox = LinearLayout(this)
        sftpBox.orientation = LinearLayout.VERTICAL
        val hint = TextView(this)
        hint.text = "No Raspberry Pi, o SSH precisa estar ligado (sudo raspi-config → Interface Options → SSH). " +
            "Use o IP do Pi na rede, ex.: 192.168.0.10."
        hint.textSize = 12f
        hint.setTextColor(Color.DKGRAY)
        sftpBox.addView(hint)
        etHost = field("IP do servidor", Settings.sftpHost, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        sftpBox.addView(etHost)
        etPort = field("Porta (padrão 22)", Settings.sftpPort.toString(), InputType.TYPE_CLASS_NUMBER)
        sftpBox.addView(etPort)
        etUser = field("Usuário", Settings.sftpUser, InputType.TYPE_CLASS_TEXT)
        sftpBox.addView(etUser)
        etPass = field(
            "Senha", Settings.sftpPass,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        sftpBox.addView(etPass)
        etPath = field("Pasta no servidor (ex.: /home/pi/downloads)", Settings.sftpPath, InputType.TYPE_CLASS_TEXT)
        sftpBox.addView(etPath)
        val btnTest = Button(this)
        btnTest.text = "Salvar e testar conexão"
        btnTest.setOnClickListener { testConnection(btnTest) }
        sftpBox.addView(btnTest)
        root.addView(sftpBox)

        val cbHost = CheckBox(this)
        cbHost.text = "Criar subpasta com o nome do site"
        cbHost.isChecked = Settings.hostFolder
        cbHost.setOnClickListener { Settings.hostFolder = cbHost.isChecked }
        root.addView(cbHost)

        // ---------------- arquivo já existe
        root.addView(sectionTitle("Se o arquivo já existir"))
        val existsGroup = RadioGroup(this)
        existsGroup.orientation = RadioGroup.VERTICAL
        val cur = Settings.onExists
        addRadio(existsGroup, "Perguntar", cur == OnExists.ASK) { Settings.onExists = OnExists.ASK }
        addRadio(existsGroup, "Ignorar (não baixar de novo)", cur == OnExists.SKIP) { Settings.onExists = OnExists.SKIP }
        addRadio(existsGroup, "Manter os dois (renomeia o novo)", cur == OnExists.RENAME) { Settings.onExists = OnExists.RENAME }
        addRadio(existsGroup, "Substituir", cur == OnExists.OVERWRITE) { Settings.onExists = OnExists.OVERWRITE }
        root.addView(existsGroup)

        setContentView(scroll)
        updateVisibility()
    }

    override fun onPause() {
        saveSftpFields()
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqFolder || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            toast("Não consegui guardar a permissão dessa pasta. Tente outra.")
            return
        }
        Settings.folderUri = uri.toString()
        Settings.folderName = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "Pasta escolhida"
        Settings.mode = DestMode.FOLDER
        updateVisibility()
    }

    private fun updateVisibility() {
        folderBox.visibility = if (Settings.mode == DestMode.FOLDER) View.VISIBLE else View.GONE
        sftpBox.visibility = if (Settings.mode == DestMode.SFTP) View.VISIBLE else View.GONE
        folderInfo.text = if (Settings.folderUri.isEmpty()) "Nenhuma pasta escolhida"
        else "Pasta atual: ${Settings.folderName}"
    }

    private fun saveSftpFields() {
        if (!::etHost.isInitialized) return
        Settings.sftpHost = etHost.text.toString()
        Settings.sftpPort = etPort.text.toString().trim().toIntOrNull() ?: 22
        Settings.sftpUser = etUser.text.toString()
        Settings.sftpPass = etPass.text.toString()
        Settings.sftpPath = etPath.text.toString()
    }

    private fun testConnection(btn: Button) {
        saveSftpFields()
        btn.isEnabled = false
        btn.text = "Testando…"
        Thread {
            val msg = try {
                Storage.testSftp()
            } catch (e: Exception) {
                "Falhou: ${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "Salvar e testar conexão"
                toast(msg)
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun sectionTitle(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setTypeface(null, Typeface.BOLD)
        tv.setTextColor(accent)
        tv.setPadding(0, dp(18), 0, dp(4))
        return tv
    }

    private fun field(hint: String, value: String, type: Int): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.inputType = type
        e.setSingleLine(true)
        e.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return e
    }

    private fun addRadio(group: RadioGroup, label: String, checked: Boolean, onPick: () -> Unit) {
        val rb = RadioButton(this)
        rb.text = label
        rb.id = View.generateViewId()
        group.addView(rb)
        rb.isChecked = checked
        rb.setOnClickListener { onPick() }
    }
}
