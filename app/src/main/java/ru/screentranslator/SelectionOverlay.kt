package ru.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Full-screen overlay where the user drags a rectangle.
 * Everything outside the rectangle is dimmed and is never sent to OCR or to the API.
 */
class SelectionOverlay(private val context: Context, private val onDone: (Region?) -> Unit) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val area = AreaView(context)
    private val root = FrameLayout(context)

    init {
        root.addView(area, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            buttonBar(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = 160 },
        )
    }

    fun show() {
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(root, params)
    }

    fun dismiss() {
        runCatching { wm.removeView(root) }
    }

    private fun buttonBar() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(Button(context).apply {
            text = "Готово"
            setOnClickListener { finish(area.region()) }
        })
        addView(Button(context).apply {
            text = "Отмена"
            setOnClickListener { finish(null) }
        })
    }

    private fun finish(region: Region?) {
        dismiss()
        onDone(region)
    }
}

private class AreaView(context: Context) : View(context) {
    private var startX = -1f
    private var startY = -1f
    private var endX = -1f
    private var endY = -1f

    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val stroke = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    fun region(): Region? {
        if (startX < 0f) return null
        val l = minOf(startX, endX).toInt()
        val t = minOf(startY, endY).toInt()
        val r = maxOf(startX, endX).toInt()
        val b = maxOf(startY, endY).toInt()
        return Region(l, t, r, b).takeIf { r - l > 40 && b - t > 40 }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY
                endX = event.rawX; endY = event.rawY
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.rawX; endY = event.rawY
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (startX < 0f) {
            canvas.drawRect(0f, 0f, w, h, dim)
            return
        }
        val l = minOf(startX, endX)
        val t = minOf(startY, endY)
        val r = maxOf(startX, endX)
        val b = maxOf(startY, endY)
        canvas.drawRect(0f, 0f, w, t, dim)
        canvas.drawRect(0f, b, w, h, dim)
        canvas.drawRect(0f, t, l, b, dim)
        canvas.drawRect(r, t, w, b, dim)
        canvas.drawRect(l, t, r, b, stroke)
    }
}
