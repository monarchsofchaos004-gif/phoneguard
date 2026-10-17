package com.pg.sentinel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 常駐救援浮鈕。長輩隨時可點＝強制回主畫面（獨立於自動偵測的手動逃生口）。
 * 用 TYPE_APPLICATION_OVERLAY 疊在最上層；需「顯示在其他 App 上」權限。
 */
class RescueOverlay private constructor(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var bubble: TextView? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (bubble != null) return
        val tv = TextView(ctx).apply {
            text = "🛡"
            textSize = 20f
            setPadding(28, 18, 28, 18)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC2E7D32"))
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 240
        }

        var downX = 0f
        var downY = 0f
        var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > 20 || abs(e.rawY - downY) > 20) {
                        moved = true
                        lp.y = (lp.y + (e.rawY - downY)).toInt().coerceAtLeast(0)
                        downY = e.rawY
                        try { wm.updateViewLayout(tv, lp) } catch (ex: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) GuardService.instance?.forceHome("手動救援鈕")
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(tv, lp)
            bubble = tv
        } catch (e: Exception) {
            // 沒有覆蓋權限：略過，功能降級（自動守門仍運作）
        }
    }

    fun flashBlocked(pkg: String) {
        val tv = bubble ?: return
        h.post {
            tv.text = "⛔ 已擋"
            h.postDelayed({ tv.text = "🛡" }, 1500)
        }
    }

    fun hide() {
        val tv = bubble ?: return
        try { wm.removeView(tv) } catch (e: Exception) {}
        bubble = null
    }

    companion object {
        @Volatile
        private var inst: RescueOverlay? = null

        fun get(): RescueOverlay? = inst

        fun ensure(ctx: Context): RescueOverlay =
            inst ?: RescueOverlay(ctx.applicationContext).also { inst = it }
    }
}
