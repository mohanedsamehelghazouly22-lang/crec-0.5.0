package com.mohaned.clashoverlay.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.mohaned.clashoverlay.core.model.GameState
import kotlin.math.roundToInt

class OverlayView(
    context: Context,
    private val moveWindow: (Int, Int) -> Unit
) : View(context) {
    private val bg = Paint(1).apply { color = Color.argb(232, 12, 15, 22) }
    private val stroke = Paint(1).apply {
        color = Color.argb(110, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val text = Paint(1).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private var state = GameState()
    private var downX = 0f
    private var downY = 0f

    fun render(newState: GameState) {
        state = newState
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).roundToInt()
                val dy = (event.rawY - downY).roundToInt()
                if (dx != 0 || dy != 0) {
                    moveWindow(dx, dy)
                    downX = event.rawX
                    downY = event.rawY
                }
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 18f, 18f, bg)
        canvas.drawRoundRect(.75f, .75f, width - .75f, height - .75f, 18f, 18f, stroke)

        text.textSize = 28f
        canvas.drawText("%.1f".format(state.elixir.current), 16f, 34f, text)

        text.textSize = 10f
        canvas.drawText(
            "ELIXIR %.1f–%.1f  %s".format(state.elixir.min, state.elixir.max, pct(state.elixir.confidence)),
            16f, 51f, text
        )
        canvas.drawText(
            "FPS %.1f   LAT %dms   CYCLE %d/4 %s".format(
                state.fps,
                state.recognitionLatencyMs,
                state.cycleIndex,
                pct(state.cycleConfidence)
            ),
            16f, 66f, text
        )

        val detected = state.opponentDeck.take(8).joinToString(" • ") { it.cardId }
        text.textSize = 9f
        canvas.drawText(
            if (detected.isBlank()) "DECK: waiting for recognition…" else "DECK: $detected",
            16f, 84f, text
        )
        canvas.drawText(
            "${state.captureStatus}  |  ${state.modelStatus}",
            16f, 103f, text
        )
        canvas.drawText(
            state.lastTip ?: "OBSERVE ONLY • ON-DEVICE",
            16f, height - 12f, text
        )
    }

    private fun pct(value: Float): String = "${(value.coerceIn(0f, 1f) * 100).roundToInt()}%"
}
