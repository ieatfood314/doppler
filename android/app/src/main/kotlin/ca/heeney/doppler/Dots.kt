package ca.heeney.doppler

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** The 1 and the 2 as stars on a 5 by 7 grid. tools/make_art.py and desktop/Main.qml draw the same grid. */
object Dots {
    const val COLS = 5
    const val ROWS = 7
    private val glyphs = mapOf(
        "1" to listOf("..#..", ".##..", "#.#..", "..#..", "..#..", "..#..", "#####"),
        "2" to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
    )

    fun on(digit: String, col: Int, row: Int) = glyphs[digit]?.get(row)?.get(col) == '#'
}

/**
 * A numeral drawn as a constellation: its stars bright, the rest of the grid faint. While [lit],
 * the stars twinkle out of step with each other (unless animations are off).
 */
@Composable
fun DotNumeral(digit: String, height: Dp, color: Color, lit: Boolean, animate: Boolean, modifier: Modifier = Modifier) {
    val phase = if (lit && animate) {
        rememberInfiniteTransition(label = "twinkle").animateFloat(
            0f, (2 * PI).toFloat(), infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "phase").value
    } else 0f
    Canvas(modifier.size(height * Dots.COLS / Dots.ROWS, height)) {
        val pitch = size.height / Dots.ROWS
        for (row in 0 until Dots.ROWS) for (col in 0 until Dots.COLS) {
            val center = Offset((col + 0.5f) * pitch, (row + 0.5f) * pitch)
            if (Dots.on(digit, col, row)) {
                val twinkle = if (lit && animate) 0.82f + 0.18f * sin(phase + (col * 1.7f + row * 2.3f)) else 1f
                if (lit) drawCircle(color.copy(alpha = 0.12f * twinkle), pitch * 0.5f, center)
                drawCircle(color.copy(alpha = color.alpha * twinkle), pitch * 0.33f, center)
            } else if (size.height > 40.dp.toPx()) {
                drawCircle(Palette.Faint.copy(alpha = 0.45f), pitch * 0.07f, center)
            }
        }
    }
}
