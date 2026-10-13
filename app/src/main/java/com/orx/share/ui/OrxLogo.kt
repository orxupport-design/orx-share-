package com.orx.share.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The orx share logo: two arrows crossing to form an "X"
 * (data flowing both ways) inside a glowing ring.
 */
@Composable
fun OrxLogo(size: Dp = 96.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val u = s / 108f // same 108-unit grid as the launcher icon
        val stroke = 7f * u

        // Soft glow disc
        drawCircle(
            brush = Brush.radialGradient(
                listOf(OrxViolet.copy(alpha = 0.35f), Color.Transparent),
                center = center, radius = s / 2
            )
        )
        // Ring
        drawCircle(
            brush = Brush.sweepGradient(listOf(OrxCyan, OrxViolet, OrxCyan)),
            radius = 30f * u,
            style = Stroke(width = 2.5f * u)
        )

        fun p(x: Float, y: Float) = Offset(x * u, y * u)

        // Cyan arrow: top-left -> bottom-right
        drawLine(OrxCyan, p(38f, 38f), p(70f, 70f), stroke, StrokeCap.Round)
        val cyanHead = Path().apply {
            moveTo(70f * u, 52f * u); lineTo(70f * u, 70f * u); lineTo(52f * u, 70f * u)
        }
        drawPath(cyanHead, OrxCyan, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Violet arrow: bottom-left -> top-right
        drawLine(OrxViolet, p(38f, 70f), p(70f, 38f), stroke, StrokeCap.Round)
        val violetHead = Path().apply {
            moveTo(52f * u, 38f * u); lineTo(70f * u, 38f * u); lineTo(70f * u, 56f * u)
        }
        drawPath(violetHead, OrxViolet, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
