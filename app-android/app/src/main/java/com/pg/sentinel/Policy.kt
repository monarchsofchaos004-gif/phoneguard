package com.pg.sentinel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 守門政策（本地持久化，可被家人端指令更新）。
 * allow＝放行不介入；block＝一到前景就介入；autoGuard＝自動偵測接管；
 * liftUntil＝家人端暫停守門到此毫秒時間（0＝未暫停）。
 */
class Policy private constructor() {
    val allow = HashSet<String>()
    val block = HashSet<String>()
    var autoGuard = true
    var liftUntil = 0L

    fun isLifted(nowMs: Long) = liftUntil > nowMs

    /** 套用一條家人端指令，回傳可讀結果字串。 */
    fun apply(cmd: String, nowMs: Long): String {
        val parts = cmd.trim().split(" ", limit = 2)
        val op = parts.getOrElse(0) { "" }.lowercase()
        val arg = parts.getOrElse(1) { "" }.trim()
        return when (op) {
            "allow" -> { if (arg.isNotEmpty()) { allow.add(arg); block.remove(arg) }; "allow $arg" }
            "block" -> { if (arg.isNotEmpty()) { block.add(arg); allow.remove(arg) }; "block $arg" }
            "unallow" -> { allow.remove(arg); "unallow $arg" }
            "lift" -> { val m = arg.toLongOrNull() ?: 10L; liftUntil = nowMs + m * 60_000L; "lift ${m}m" }
            "guard" -> { liftUntil = 0L; "guard-on" }
            "auto" -> { autoGuard = arg != "off"; "auto=$autoGuard" }
            else -> "ignored:$op"
        }
    }

    fun save(ctx: Context) {
        val o = JSONObject()
        o.put("allow", JSONArray(allow.toList()))
        o.put("block", JSONArray(block.toList()))
        o.put("autoGuard", autoGuard)
        o.put("liftUntil", liftUntil)
        Prefs.sp(ctx).edit().putString("policy", o.toString()).apply()
    }

    companion object {
        fun load(ctx: Context): Policy {
            val p = Policy()
            val s = Prefs.sp(ctx).getString("policy", null) ?: return p
            try {
                val o = JSONObject(s)
                o.optJSONArray("allow")?.let { for (i in 0 until it.length()) p.allow.add(it.getString(i)) }
                o.optJSONArray("block")?.let { for (i in 0 until it.length()) p.block.add(it.getString(i)) }
                p.autoGuard = o.optBoolean("autoGuard", true)
                p.liftUntil = o.optLong("liftUntil", 0L)
            } catch (e: Exception) {
            }
            return p
        }
    }
}
