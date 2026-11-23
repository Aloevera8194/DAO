package com.aloevera.dao

import android.content.Context
import android.content.SharedPreferences

/** Onde os arquivos terminam: pasta padrão do celular, pasta escolhida pelo usuário ou servidor SFTP. */
enum class DestMode { PHONE, FOLDER, SFTP }

/** O que fazer quando o arquivo já existe no destino. */
enum class OnExists { ASK, SKIP, RENAME, OVERWRITE }

object Settings {
    private var prefs: SharedPreferences? = null

    fun init(c: Context) {
        if (prefs == null) {
            prefs = c.applicationContext.getSharedPreferences("dao_settings", Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences =
        prefs ?: throw IllegalStateException("Settings.init não foi chamado")

    private fun getS(key: String, def: String): String = p().getString(key, def) ?: def
    private fun putS(key: String, v: String) {
        p().edit().putString(key, v).apply()
    }

    var mode: DestMode
        get() = try {
            DestMode.valueOf(getS("mode", DestMode.PHONE.name))
        } catch (e: Exception) {
            DestMode.PHONE
        }
        set(v) = putS("mode", v.name)

    var onExists: OnExists
        get() = try {
            OnExists.valueOf(getS("onExists", OnExists.ASK.name))
        } catch (e: Exception) {
            OnExists.ASK
        }
        set(v) = putS("onExists", v.name)

    /** Criar uma subpasta com o nome do site dentro do destino. */
    var hostFolder: Boolean
        get() = p().getBoolean("hostFolder", true)
        set(v) {
            p().edit().putBoolean("hostFolder", v).apply()
        }

    // Pasta escolhida (Storage Access Framework)
    var folderUri: String
        get() = getS("folderUri", "")
        set(v) = putS("folderUri", v)

    var folderName: String
        get() = getS("folderName", "")
        set(v) = putS("folderName", v)

    // Servidor SFTP (Raspberry Pi)
    var sftpHost: String
        get() = getS("sftpHost", "")
        set(v) = putS("sftpHost", v.trim())

    var sftpPort: Int
        get() = p().getInt("sftpPort", 22)
        set(v) {
            p().edit().putInt("sftpPort", v).apply()
        }

    var sftpUser: String
        get() = getS("sftpUser", "pi")
        set(v) = putS("sftpUser", v.trim())

    var sftpPass: String
        get() = getS("sftpPass", "")
        set(v) = putS("sftpPass", v)

    var sftpPath: String
        get() = getS("sftpPath", "/home/pi/downloads")
        set(v) = putS("sftpPath", v.trim())
}
