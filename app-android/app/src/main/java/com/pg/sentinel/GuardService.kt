package com.pg.sentinel

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/** 守門事件匯流排：GuardService 偵測到介入時通知前景服務推狀態給家人端。 */
object GuardBus {
    @Volatile
    var onEvent: ((String) -> Unit)? = null
    fun emit(msg: String) { onEvent?.invoke(msg) }
}

/**
 * 守門核心＝無障礙服務。追蹤前景 App，偵測「接管」行為並反制：
 *  - block 清單：一到前景即踢回主畫面
 *  - 反覆搶前景（送 HOME 後又立刻回來）：升級介入
 *  - 手動/遠端救援：forceHome() 連打 HOME 打穿頑固覆蓋層
 * 這是 Tier B 反應式守門的第一版啟發式，實機再調參（見 README「極限」）。
 */
class GuardService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var lastPkg = ""
    private var lastHomeAt = 0L
    private var fightCount = 0

    override fun onServiceConnected() {
        instance = this
        resolveLauncher()
        GuardBus.emit("守門已連線")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        val pkg = event.packageName?.toString() ?: return
        val now = SystemClock.elapsedRealtime()

        if (pkg == lastPkg) return
        lastPkg = pkg

        val policy = policyRef ?: return
        if (policy.isLifted(System.currentTimeMillis())) return   // 家人端暫停守門中
        if (pkg == packageName) return                            // 自己
        if (isSafePackage(pkg)) { fightCount = 0; return }        // 啟動器/系統/放行

        // 反制1：明確封鎖清單
        if (policy.block.contains(pkg)) {
            intervene(pkg, "封鎖清單", hard = true)
            return
        }
        // 反制2：送 HOME 後同一非安全 App 立刻又回前景 = 硬搶控制權
        if (pkg == fightPkg && now - lastHomeAt < 2500) {
            fightCount++
            if (fightCount >= 2 && policy.autoGuard) {
                intervene(pkg, "反覆搶前景x$fightCount")
                return
            }
        } else if (pkg != fightPkg) {
            fightCount = 0
        }
    }

    /** 連打 HOME 打斷接管；手動救援鈕與家人端 rescue 都走這裡。 */
    fun forceHome(reason: String) {
        lastHomeAt = SystemClock.elapsedRealtime()
        fightPkg = lastPkg
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 400)
        h.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 900)
        GuardBus.emit("已介入($reason)→回主畫面 pkg=$lastPkg")
    }

    private fun intervene(pkg: String, reason: String, hard: Boolean = false) {
        forceHome(reason)
        RescueOverlay.get()?.flashBlocked(pkg)
        // 選用加強模式:有 Shizuku 授權才做;系統 App 一律不動(打錯會讓手機失靈)
        if (Shz.granted() && !isSystemApp(pkg)) {
            val self = packageName
            Thread {
                val did = Shz.counter(pkg, hard, self)
                if (did.isNotEmpty()) GuardBus.emit("Shizuku 加強($reason):$did pkg=$pkg")
            }.start()
        }
    }

    private fun isSystemApp(pkg: String): Boolean = try {
        (packageManager.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    } catch (e: Exception) { true }   // 查不到當系統 App,不動

    private fun isSafePackage(pkg: String): Boolean {
        if (launcherPkgs.contains(pkg)) return true
        if (policyRef?.allow?.contains(pkg) == true) return true      // 家人放行的 App 不介入
        if (pkg == "com.android.systemui" || pkg == "com.android.settings") return true
        if (pkg.startsWith("com.google.android.")) return true       // GMS/系統元件
        return false
    }

    private fun resolveLauncher() {
        try {
            val hi = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val ri = packageManager.resolveActivity(hi, PackageManager.MATCH_DEFAULT_ONLY)
            ri?.activityInfo?.packageName?.let { launcherPkgs.add(it) }
        } catch (e: Exception) {
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    companion object {
        @Volatile
        var instance: GuardService? = null

        @Volatile
        var policyRef: Policy? = null

        @Volatile
        var fightPkg = ""

        val launcherPkgs = HashSet<String>()
    }
}
