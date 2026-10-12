package io.github.anwo91.phevlink.ui.home.carpic

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin

/** [Pen] on a Compose canvas. */
class ComposePen(private val canvas: Canvas) : Pen {
    override fun save() = canvas.save()
    override fun restore() = canvas.restore()
    override fun translate(dx: Float, dy: Float) = canvas.translate(dx, dy)
    override fun scale(sx: Float, sy: Float) = canvas.scale(sx, sy)

    override fun rotate(deg: Float, px: Float, py: Float) {
        canvas.translate(px, py)
        canvas.rotate(deg)
        canvas.translate(-px, -py)
    }

    override fun clip(shape: Shape) = canvas.clipPath(path(shape))

    override fun fill(shape: Shape, paint: Paint) = canvas.drawPath(path(shape), paintOf(paint, PaintingStyle.Fill))

    override fun stroke(shape: Shape, paint: Paint, width: Float, dash: FloatArray?, dashPhase: Float, roundCap: Boolean) {
        val p = paintOf(paint, PaintingStyle.Stroke)
        p.strokeWidth = width
        p.strokeCap = if (roundCap) StrokeCap.Round else StrokeCap.Butt
        p.strokeJoin = StrokeJoin.Round
        if (dash != null) p.pathEffect = PathEffect.dashPathEffect(dash, dashPhase)
        canvas.drawPath(path(shape), p)
    }

    private fun paintOf(paint: Paint, style: PaintingStyle) = androidx.compose.ui.graphics.Paint().apply {
        isAntiAlias = true
        this.style = style
        when (paint) {
            is Paint.Solid -> color = Color(paint.argb)
            is Paint.Linear -> shader = LinearGradientShader(
                Offset(paint.x0, paint.y0), Offset(paint.x1, paint.y1), paint.colors.map { Color(it) }, paint.stops?.toList(),
            )
            is Paint.Radial -> shader = RadialGradientShader(
                Offset(paint.cx, paint.cy), paint.r.coerceAtLeast(0.01f), paint.colors.map { Color(it) }, paint.stops?.toList(),
            )
        }
    }

    private fun path(s: Shape) = Path().apply {
        for (op in s.ops) when (op) {
            is Shape.Move -> moveTo(op.x, op.y)
            is Shape.Line -> lineTo(op.x, op.y)
            is Shape.Quad -> quadraticTo(op.x1, op.y1, op.x, op.y)
            is Shape.Cubic -> cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
            Shape.Close -> close()
        }
    }
}
