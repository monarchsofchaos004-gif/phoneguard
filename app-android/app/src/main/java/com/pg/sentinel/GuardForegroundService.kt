package com.pg.sentinel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 常駐前景服務：載入政策 → 輪詢轉發站取家人端指令 → 驗時間戳/去重 → 套用 → 推狀態；管救援浮鈕。
 * 全部只對轉發站連出，手機永不被連入。
 */
class GuardForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var policy: Policy
    private lateinit var transport: Transport
    private var sinceRef = ""
    private val seenNonce = ArrayDeque<String>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1, buildNotif("守門中"))
        policy = Policy.load(this)
        GuardService.policyRef = policy
        transport = Transport(Prefs.pairingSecret(this), Prefs.server(this))
        sinceRef = (System.currentTimeMillis() / 1000).toString()   // 只收未來指令
        RescueOverlay.ensure(this).show()
        GuardBus.onEvent = { msg -> scope.launch { pushStatus("event", msg) } }
        loop()
    }

    private fun loop() {
        scope.launch {
            var beat = 0
            while (isActive) {
                try {
                    val (cmds, newSince) = transport.pollCommands(sinceRef)
                    sinceRef = newSince
                    val accepted = cmds.mapNotNull { unwrap(it) }
                    if (accepted.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        val applied = ArrayList<String>()
                        for (c in accepted) {
                            val op = c.trim().split(" ", limit = 2)
                            if (c.trim().equals("rescue", ignoreCase = true)) {
                                withContext(Dispatchers.Main) {
                                    GuardService.instance?.forceHome("遠端救援")
                                }
                                applied.add("rescue")
                            } else if (op[0].equals("ring", ignoreCase = true)) {   // 找手機:大聲響 N 秒(ring 0＝停)
                                val sec = op.getOrNull(1)?.trim()?.toIntOrNull() ?: 60
                                applied.add(withContext(Dispatchers.Main) {
                                    if (sec <= 0) Ringer.stop() else Ringer.start(this@GuardForegroundService, sec)
                                })
                            } else if (op[0].equals("stopring", ignoreCase = true)) {
                                applied.add(withContext(Dispatchers.Main) { Ringer.stop() })
                            } else {
                                applied.add(policy.apply(c, now))
                            }
                        }
                        policy.save(this@GuardForegroundService)
                        GuardService.policyRef = policy
                        pushStatus("applied", applied.joinToString(";"))
                    }
                    beat++
                    if (beat % 9 == 0) pushStatus("heartbeat", "alive")
                } catch (e: Exception) {
                    // 離線：下輪再試
                }
                delay(20_000)
            }
        }
    }

    /**
     * 指令信封＝{"c":"<指令>","t":<epoch 秒>,"n":"<隨機 nonce>"}。
     * 時間戳超過 ±5 分鐘、或 nonce 已見過 → 丟掉（防重放：攔到一則加密的「lift 60」也不能重送）。
     */
    private fun unwrap(plain: String): String? {
        val o = try { JSONObject(plain) } catch (e: Exception) { return null }
        val cmd = o.optString("c", "").trim()
        val t = o.optLong("t", 0L)
        val n = o.optString("n", "")
        if (cmd.isEmpty() || n.isEmpty()) return null
        val now = System.currentTimeMillis() / 1000
        if (t < now - 300 || t > now + 300) return null
        synchronized(seenNonce) {
            if (seenNonce.contains(n)) return null
            seenNonce.addLast(n)
            while (seenNonce.size > 200) seenNonce.removeFirst()
        }
        return cmd
    }

    private fun pushStatus(kind: String, detail: String) {
        val o = JSONObject()
        o.put("k", kind)
        o.put("d", detail)
        o.put("t", System.currentTimeMillis() / 1000)
        o.put("lift", policy.liftUntil)
        o.put("allow", policy.allow.size)
        o.put("block", policy.block.size)
        transport.pushStatus(o.toString())
    }

    private fun buildNotif(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        GuardBus.onEvent = null
        Ringer.stop()
    }

    companion object {
        const val CHANNEL = "phoneguard"

        fun start(ctx: Context) {
            val i = Intent(ctx, GuardForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
