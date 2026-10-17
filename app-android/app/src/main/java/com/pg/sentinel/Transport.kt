package com.pg.sentinel

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 手機只對「轉發站」（預設 ntfy.sh，可自架）做連出；手機從不被任何人連入。
 * 指令：家人端 → 轉發站 → 手機（手機只 poll、只讀）。
 * 狀態：手機 → 轉發站（只寫）；家人端只讀、只當資料顯示。
 *
 * The phone only makes outbound requests to a relay (ntfy.sh by default; self-hostable).
 * Nothing ever connects *into* the phone.
 */
class Transport(private val secret: String, private val base: String = DEFAULT_SERVER) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val cmdTopic = Crypto.topic(secret, "cmd")
    private val stTopic = Crypto.topic(secret, "st")

    /** 手機→轉發站：推送加密狀態。 */
    fun pushStatus(json: String): Boolean {
        return try {
            val body = Crypto.encrypt(secret, json).toRequestBody()
            val req = Request.Builder()
                .url("$base/$stTopic")
                .header("Title", "pg")
                .post(body)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 手機→轉發站：拉取家人端下發的指令（poll，只讀）。
     * 回傳 (解密後的明文 list, 前進後的 sinceRef)。解不開的訊息一律丟掉。
     */
    fun pollCommands(sinceRef: String): Pair<List<String>, String> {
        val out = ArrayList<String>()
        var newest = sinceRef
        try {
            val since = if (sinceRef.isBlank()) "5m" else sinceRef
            val req = Request.Builder()
                .url("$base/$cmdTopic/json?poll=1&since=$since")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Pair(out, sinceRef)
                val text = resp.body?.string() ?: return Pair(out, sinceRef)
                for (line in text.split("\n")) {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    val o = try { JSONObject(t) } catch (e: Exception) { continue }
                    val tm = o.optLong("time", 0L)
                    if (tm > 0L) newest = (tm + 1).toString()
                    if (o.optString("event") != "message") continue
                    val msg = o.optString("message", "")
                    if (msg.isNotEmpty()) {
                        val plain = Crypto.decrypt(secret, msg)
                        if (plain != null) out.add(plain)
                    }
                }
            }
        } catch (e: Exception) {
            // 離線：忽略，下輪再試
        }
        return Pair(out, newest)
    }

    companion object {
        const val DEFAULT_SERVER = "https://ntfy.sh"
    }
}
