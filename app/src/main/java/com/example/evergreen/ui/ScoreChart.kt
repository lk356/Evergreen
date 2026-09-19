package com.example.evergreen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.evergreen.scoring.ScorePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the numbers behind the line are, once the arithmetic is done.
 *
 * Pulled out of the drawing so the interesting part -- how far the score
 * actually swings, which is the open question this view exists to settle -- is
 * testable without a device.
 */
internal data class ScoreChartStats(
    val lowest: Double,
    val highest: Double,
    val latest: Double,
    /** The steepest fall between two consecutive samples. Positive is a fall. */
    val biggestDrop: Double,
    /** That fall as a fraction of the score it fell from, or 0 when it rose from nothing. */
    val biggestDropFraction: Double,
)

internal fun scoreChartStats(points: List<ScorePoint>): ScoreChartStats? {
    if (points.isEmpty()) return null
    var drop = 0.0
    var dropFraction = 0.0
    points.zipWithNext { earlier, later ->
        val fall = earlier.score - later.score
        if (fall > drop) {
            drop = fall
            dropFraction = if (earlier.score > 0.0) fall / earlier.score else 0.0
        }
    }
    return ScoreChartStats(
        lowest = points.minOf { it.score },
        highest = points.maxOf { it.score },
        latest = points.last().score,
        biggestDrop = drop,
        biggestDropFraction = dropFraction,
    )
}

/**
 * One track's score over the last six months.
 *
 * The tool for the volatility question the last phase left open: a single skip
 * visibly drags a score down, and what is not yet known is whether that is real
 * or merely noticeable. A line makes the difference legible -- the size of the
 * step against the height of the curve -- where a number cannot.
 *
 * Drawn with a Canvas rather than a charting library. This is one polyline; a
 * dependency for it would be a dependency on a device with limited RAM, and
 * none was needed.
 *
 * **The y axis starts at zero.** A chart cropped to its data makes every wobble
 * look like a cliff, which is exactly the judgement being made here and exactly
 * the way to get it wrong.
 */
@Composable
fun ScoreChart(points: List<ScorePoint>, modifier: Modifier = Modifier) {
    val stats = remember(points) { scoreChartStats(points) }
    if (points.size < 2 || stats == null) {
        Text(
            text = "Not enough history yet to draw a line.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val ceiling = maxOf(stats.highest, 0.0001)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(top = 6.dp, bottom = 2.dp),
        ) {
            val w = size.width
            val h = size.height

            fun x(index: Int) = w * index / (points.size - 1)
            fun y(score: Double) = h - (h * (score / ceiling)).toFloat().coerceIn(0f, h)

            // Zero, dashed, so the height of the curve is readable as an
            // absolute quantity rather than only as a shape.
            drawLine(
                color = axis,
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )

            val path = Path().apply {
                moveTo(x(0), y(points[0].score))
                points.forEachIndexed { index, point ->
                    if (index > 0) lineTo(x(index), y(point.score))
                }
            }
            drawPath(path = path, color = line, style = Stroke(width = 3f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = DAY_FORMAT.format(Date(points.first().atMs)),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "peak ${format(stats.highest)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "now ${format(stats.latest)}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = describeVolatility(stats),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The sentence under the line.
 *
 * Says what the biggest fall was and how big it was *relative to the score it
 * fell from*, because that ratio is the actual question. A drop of 0.4 means
 * one thing on a score of 1 and another on a score of 40, and the eye is bad at
 * that comparison on a chart whose height changes per track.
 */
internal fun describeVolatility(stats: ScoreChartStats): String {
    if (stats.biggestDrop <= 0.0001) {
        return "It has not fallen between any two samples in this window — only decayed " +
            "or climbed."
    }
    val percent = Math.round(stats.biggestDropFraction * 100)
    return "The steepest fall between two samples was ${format(stats.biggestDrop)}, " +
        "which was $percent% of the score at the time. Roughly three days per sample, so " +
        "a single skip shows as one step down."
}

private val DAY_FORMAT = SimpleDateFormat("d MMM", Locale.getDefault())
