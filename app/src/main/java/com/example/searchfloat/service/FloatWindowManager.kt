package com.example.searchfloat.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

object FloatWindowManager {
    private const val TAG = "FloatWindow"
    private var windowManager: WindowManager? = null
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelText: TextView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var ctxRef: Context? = null
    private var currentResult: String = ""

    var lastError: String = ""
    var lastLog: String = ""

    val isRunning: Boolean get() = ballView != null
    fun isShowing(): Boolean = isRunning

    private fun dp(ctx: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context): Boolean {
        val ctx = context.applicationContext
        ctxRef = ctx
        lastLog = ""
        lastError = ""

        try {
            if (!Settings.canDrawOverlays(ctx)) {
                lastError = "没有悬浮窗权限"
                Toast.makeText(ctx, lastError, Toast.LENGTH_LONG).show()
                return false
            }

            if (ballView != null) return true

            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // ---- 圆形小球 ----
            val ballSize = dp(ctx, 56f)
            val ball = TextView(ctx).apply {
                text = "搜题"
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#CC6750A4")) // 半透明紫
                    setStroke(dp(ctx, 1.5f), Color.parseColor("#FFFFFFFF"))
                }
                elevation = dp(ctx, 4f).toFloat()
            }

            val ballLp = WindowManager.LayoutParams(
                ballSize, ballSize,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = ctx.resources.displayMetrics.widthPixels - ballSize - dp(ctx, 8f)
                y = ctx.resources.displayMetrics.heightPixels / 3
            }

            wm.addView(ball, ballLp)
            ballView = ball
            ballParams = ballLp

            // 拖动 + 点击
            var downX = 0f; var downY = 0f
            var startX = 0; var startY = 0
            var moved = false
            ball.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = ballLp.x; startY = ballLp.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (abs(dx) + abs(dy) > 12) moved = true
                        ballLp.x = startX + dx
                        ballLp.y = startY + dy
                        try { wm.updateViewLayout(ball, ballLp) } catch (_: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            // 点击小球：直接打开扫描搜题（实时摄像头扫题）
                            val intent = android.content.Intent(ctx, com.example.searchfloat.LiveScanActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(intent)
                        }
                        else snapToEdge(ctx, ball, ballLp)
                        true
                    }
                    else -> false
                }
            }

            // 订阅辅助功能服务的搜索结果
            ScreenCaptureService.onResultUpdate = { result ->
                Handler(Looper.getMainLooper()).post {
                    currentResult = result
                    if (panelView != null) updatePanelText(result)
                    // 闪烁小球提示有新答案
                    ball.animate().alpha(0.3f).setDuration(120).withEndAction {
                        ball.animate().alpha(1f).setDuration(120).start()
                    }.start()
                }
            }

            // 如果服务里已经有上次结果，先拿过来
            if (ScreenCaptureService.lastResult.isNotBlank()) {
                currentResult = ScreenCaptureService.lastResult
            }

            Toast.makeText(ctx, "悬浮搜题已启动", Toast.LENGTH_SHORT).show()
            return true
        } catch (e: Throwable) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "悬浮窗创建失败", e)
            Toast.makeText(ctx, "失败: $lastError", Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun snapToEdge(ctx: Context, ball: View, lp: WindowManager.LayoutParams) {
        val wm = windowManager ?: return
        val screenW = ctx.resources.displayMetrics.widthPixels
        val mid = screenW / 2
        lp.x = if (lp.x + ball.width / 2 > mid) screenW - ball.width - dp(ctx, 4f) else dp(ctx, 4f)
        try { wm.updateViewLayout(ball, lp) } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun togglePanel() {
        val ctx = ctxRef ?: return
        val wm = windowManager ?: return
        if (panelView != null) {
            try { wm.removeView(panelView) } catch (_: Exception) {}
            panelView = null
            panelText = null
            return
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val body = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
            text = if (currentResult.isBlank()) {
                if (OcrService.isAuthorized())
                    "📸 已就绪：切到题目页面后点小球即可截屏识别"
                else
                    "等待识别屏幕题目…\n\n如果考试 App 是 H5 / WebView，需要回主界面开启「截屏 OCR」"
            } else currentResult
        }
        panelText = body

        val close = TextView(ctx).apply {
            text = "关闭"
            setTextColor(Color.parseColor("#FFD0BCFF"))
            textSize = 13f
            gravity = Gravity.END
            setPadding(dp(ctx, 14f), dp(ctx, 6f), dp(ctx, 14f), dp(ctx, 10f))
            setOnClickListener { togglePanel() }
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#EE2C2435"))
                cornerRadius = dp(ctx, 12f).toFloat()
                setStroke(dp(ctx, 1f), Color.parseColor("#806750A4"))
            }
            addView(body, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(close, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val width = (ctx.resources.displayMetrics.widthPixels * 0.85f).toInt()
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(ctx, 80f)
        }

        wm.addView(container, lp)
        panelView = container
        panelParams = lp
    }

    private fun updatePanelText(result: String) {
        panelText?.text = result
    }

    fun hide(context: Context? = null) {
        try { panelView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        try { ballView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        panelView = null
        panelText = null
        ballView = null
        ballParams = null
        ScreenCaptureService.onResultUpdate = null
    }
}
