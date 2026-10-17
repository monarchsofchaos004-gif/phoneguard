package com.pg.sentinel

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/** 本地小型設定：配對碼、轉發站網址、政策 JSON。 */
object Prefs {
    private const val FILE = "pg_prefs"

    fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 取得配對碼；首次自動產生。家人端須輸入同一組。 */
    fun pairingSecret(ctx: Context): String {
        val s = sp(ctx)
        var sec = s.getString("secret", null)
        if (sec.isNullOrBlank()) {
            sec = randomSecret()
            s.edit().putString("secret", sec).apply()
        }
        return sec
    }

    fun regenSecret(ctx: Context): String {
        val sec = randomSecret()
        sp(ctx).edit().putString("secret", sec).apply()
        return sec
    }

    /** 轉發站網址（預設 ntfy.sh；可改成自架的 ntfy）。 */
    fun server(ctx: Context): String {
        val v = sp(ctx).getString("server", null)?.trim()?.trimEnd('/')
        return if (v.isNullOrBlank()) Transport.DEFAULT_SERVER else v
    }

    fun setServer(ctx: Context, url: String) {
        val v = url.trim().trimEnd('/')
        sp(ctx).edit().putString("server", if (v.startsWith("http")) v else "").apply()
    }

    private fun randomSecret(): String {
        val chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..12).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }
}
