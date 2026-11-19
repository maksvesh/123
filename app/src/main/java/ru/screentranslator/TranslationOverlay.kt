package ru.screentranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

data class OverlayItem(val box: Rect, val text: String)

/** Full-screen, non-touchable overlay that paints translations over the original text. */
class TranslationOverlay(context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = DrawView(context)
    private val params = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    fun show() = wm.addView(view, params)

    fun remove() {
        runCatching { wm.removeView(view) }
    }

    fun update(items: List<OverlayItem>) {
        view.post {
            view.items = items
            view.invalidate()
        }
    }

    /** Hidden while the screen is captured, so the translation is not OCR'd again. */
    fun setShown(shown: Boolean) {
        view.post { view.visibility = if (shown) View.VISIBLE else View.INVISIBLE }
    }

    private class DrawView(context: Context) : View(context) {
        var items: List<OverlayItem> = emptyList()

        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 255, 255) }
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val pad = 6f

        override fun onDraw(canvas: Canvas) {
            for (item in items) {
                val rect = RectF(item.box)
                canvas.drawRoundRect(rect, 8f, 8f, bg)
                drawFitted(canvas, item.text, rect)
            }
        }

        /** Shrinks the font until the text fits the original text box. */
        private fun drawFitted(canvas: Canvas, str: String, rect: RectF) {
            val width = (rect.width() - 2 * pad).toInt()
            val height = rect.height() - 2 * pad
            if (width <= 0 || height <= 0) return
            var size = height * 0.9f
            var layout: StaticLayout
            do {
                textPaint.textSize = size
                layout = StaticLayout.Builder.obtain(str, 0, str.length, textPaint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                size -= 1f
            } while (layout.height > height && size > 8f)

            canvas.save()
            canvas.translate(rect.left + pad, rect.top + pad)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
