package com.pg.sentinel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 設定畫面：引導授權、啟動守門、顯示配對碼、設定轉發站、測試救援。 */
class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var tvPairing: TextView
    private lateinit var etServer: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        tvStatus = TextView(this).apply { textSize = 15f }
        root.addView(tvStatus)
        root.addView(space())

        root.addView(btn("① 開啟無障礙（守門核心）") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("② 允許顯示在其他 App 上（救援鈕）") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })
        root.addView(btn("③ 允許使用情況存取") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        root.addView(space())
        root.addView(btn("▶ 啟動守門") {
            GuardForegroundService.start(this)
            refresh()
        })
        root.addView(btn("🛡 測試救援（強制回主畫面）") {
            GuardService.instance?.forceHome("測試")
        })
        root.addView(space())

        root.addView(TextView(this).apply { text = "配對碼（家人端輸入同一組）："; textSize = 14f })
        tvPairing = TextView(this).apply { textSize = 20f }
        root.addView(tvPairing)
        root.addView(btn("🔕 停止響鈴（家人端遠端響鈴時用）") { Ringer.stop() })
        root.addView(btn("重新產生配對碼") {
            Prefs.regenSecret(this)
            refresh()
        })
        root.addView(space())

        root.addView(TextView(this).apply { text = "轉發站網址（預設 ntfy.sh，可自架）："; textSize = 14f })
        etServer = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(Prefs.server(this@MainActivity))
        }
        root.addView(etServer)
        root.addView(btn("儲存轉發站（重新啟動守門後生效）") {
            Prefs.setServer(this, etServer.text.toString())
            refresh()
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        val acc = isAccessibilityOn()
        val ov = Settings.canDrawOverlays(this)
        tvStatus.text = buildString {
            append("守門狀態\n")
            append(if (acc) "✓ 無障礙已開\n" else "✗ 無障礙未開（點①）\n")
            append(if (ov) "✓ 覆蓋權限已給\n" else "✗ 覆蓋權限未給（點②）\n")
            append(if (GuardService.instance != null) "✓ 守門服務執行中\n" else "· 守門服務未連線\n")
        }
        tvPairing.text = Prefs.pairingSecret(this)
        if (::etServer.isInitialized) etServer.setText(Prefs.server(this))
    }

    private fun isAccessibilityOn(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return flat.contains("$packageName/")
    }

    private fun btn(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

    private fun space(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 32)
    }
}
