package com.pg.sentinel

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 小工具（選用的「加強模式」）：以 shell 身分跑指令，做無障礙服務做不到的反制
 * （強制關閉、停用、收回覆蓋權限、收回別的 App 偷拿的無障礙）。沒裝/沒啟動/沒授權＝什麼都不做，守門照舊。
 * newProcess 是隱藏方法，用反射叫（同 splitaudio）。
 */
object Shz {
    const val REQ = 1001
    private val PKG_RE = Regex("^[A-Za-z0-9_.]+$")

    fun alive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    fun granted(): Boolean = try { alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
    fun request() { try { if (alive() && !granted()) Shizuku.requestPermission(REQ) } catch (_: Throwable) {} }

    /** 套件名只准英數、底線、點，才拼進 shell 指令（防注入）。 */
    fun safePkg(pkg: String): Boolean = PKG_RE.matches(pkg)

    private fun newProcess(cmd: String): Process {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }

    /** 跑一條 shell，回 (rc, 輸出)；rc=-2 沒授權、-1 逾時 */
    fun run(cmd: String, timeoutSec: Int = 20): Pair<Int, String> {
        if (!granted()) return Pair(-2, if (alive()) "not_granted" else "shizuku_dead")
        return try {
            val p = newProcess(cmd)
            p.outputStream.close()
            val sb = StringBuilder()
            val t = Thread { try { BufferedReader(InputStreamReader(p.inputStream)).use { r -> var l = r.readLine(); while (l != null && sb.length < 16384) { sb.append(l).append('\n'); l = r.readLine() } } } catch (_: Exception) {} }
            t.isDaemon = true; t.start()
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L; var rc: Int? = null
            while (System.currentTimeMillis() < deadline) { try { rc = p.exitValue(); break } catch (_: Exception) { Thread.sleep(100) } }
            if (rc == null) try { p.destroy() } catch (_: Exception) {}
            t.join(1500)
            Pair(rc ?: -1, sb.toString())
        } catch (e: Throwable) { Pair(-3, e.javaClass.simpleName + ":" + (e.message ?: "").take(100)) }
    }

    /**
     * 加強反制：強制關閉＋收回「顯示在其他 App 上」＋拿掉它的無障礙服務；hard（家人放進封鎖清單的）再加停用。
     * 呼叫端已排除系統 App／放行清單／自己。回傳做了哪些（給家人端看）；沒授權回空字串。
     */
    fun counter(pkg: String, hard: Boolean, selfPkg: String): String {
        if (!granted() || !safePkg(pkg) || pkg == selfPkg) return ""
        val done = ArrayList<String>()
        if (run("am force-stop $pkg").first == 0) done.add("強制關閉")
        if (run("appops set $pkg SYSTEM_ALERT_WINDOW deny").first == 0) done.add("收回覆蓋權限")
        val (rc, out) = run("settings get secure enabled_accessibility_services")
        if (rc == 0) {
            val cur = out.trim().takeIf { it != "null" } ?: ""
            val items = cur.split(':').filter { it.isNotBlank() }
            val keep = items.filterNot { it.startsWith("$pkg/") }
            if (keep.size != items.size && keep.all { s -> s.all { it.isLetterOrDigit() || it in "._/\$" } }) {
                if (run("settings put secure enabled_accessibility_services '${keep.joinToString(":")}'").first == 0) done.add("收回無障礙")
            }
        }
        if (hard && run("pm disable-user --user 0 $pkg").first == 0) done.add("停用")
        return done.joinToString("/")
    }

    /** 家人端 allow 某 App 時順手解除停用（之前被封鎖清單停用的才有作用；沒停用也無害）。 */
    fun enable(pkg: String): String {
        if (!granted() || !safePkg(pkg)) return ""
        return if (run("pm enable --user 0 $pkg").first == 0) "已解除停用" else ""
    }
}
