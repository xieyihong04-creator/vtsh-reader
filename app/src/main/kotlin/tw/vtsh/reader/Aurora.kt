package tw.vtsh.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform

/**
 * 弥散极光 —— 与原型 v0.4 定稿同一套参数（低彩度莫兰迪、深墨底、暗角）。
 * 程序化绘制：背景渐变 + 5 个漂移光斑 + 暗角。
 * 玻璃卡调用 drawScene 时传入自己的偏移与缩放，即"采样"同一场景，
 * 全站只有一份内容源，成本远低于逐元素 backdrop 采样。
 */
object Aurora {
    private val bgTop = Color(0xFF0D1524)
    private val bgMid = Color(0xFF080D18)
    private val bgBot = Color(0xFF04070E)

    data class Blob(
        val color: Color, val inner: Float, val mid: Color, val midAt: Float,
        val x0: Float, val y0: Float, val rx: Float, val ry: Float,
        val ampX: Float, val ampY: Float, val scaleAmp: Float,
        val period: Float, val phase: Float,
    )

    private val blobs = listOf(
        // 雾青
        Blob(Color(0x80268484), 0.46f, Color(0x2B268484), 0.46f,
            x0 = -0.18f, y0 = -0.14f, rx = 0.86f, ry = 0.52f,
            ampX = 0.10f, ampY = 0.06f, scaleAmp = 0.10f, period = 44f, phase = 0f),
        // 灰靛
        Blob(Color(0x853A54A6), 0.48f, Color(0x2B3A54A6), 0.48f,
            x0 = 0.72f, y0 = -0.08f, rx = 0.78f, ry = 0.48f,
            ampX = -0.09f, ampY = 0.07f, scaleAmp = -0.06f, period = 52f, phase = 1.7f),
        // 藕紫
        Blob(Color(0x6670509E), 0.50f, Color(0x1E70509E), 0.50f,
            x0 = 0.08f, y0 = 0.30f, rx = 0.70f, ry = 0.44f,
            ampX = 0.06f, ampY = -0.09f, scaleAmp = 0.07f, period = 48f, phase = 3.1f),
        // 旧金（提亮）
        Blob(Color(0x4DC49660), 0.50f, Color(0x17C49660), 0.50f,
            x0 = 0.66f, y0 = 0.46f, rx = 0.58f, ry = 0.38f,
            ampX = -0.07f, ampY = -0.06f, scaleAmp = 0.10f, period = 58f, phase = 4.4f),
        // 藕红
        Blob(Color(0x528C4A68), 0.50f, Color(0x008C4A68), 0.74f,
            x0 = -0.10f, y0 = 0.82f, rx = 0.52f, ry = 0.34f,
            ampX = 0.08f, ampY = -0.05f, scaleAmp = 0.08f, period = 54f, phase = 2.2f),
    )

    /**
     * @param w,h     场景参考尺寸（全屏尺寸，卡片以此换算自己的采样区域）
     * @param ox,oy   本层采样原点（卡片在屏幕上的位置 - 滚动视差）
     * @param zoom    >1 表示光斑相对放大（卡片用 ~1.35，让有限面积里有完整渐变）
     */
    fun DrawScope.drawScene(t: Float, w: Float, h: Float, ox: Float, oy: Float, zoom: Float, vignette: Boolean) {
        // 把画布坐标系平移到"本层原点对应全屏场景的位置"
        withTransform({ translate(-ox, -oy) }) {
            drawRect(brush = Brush.verticalGradient(
                0f to bgTop, 0.44f to bgMid, 1f to bgBot,
                startY = 0f, endY = h
            ), size = androidx.compose.ui.geometry.Size(w, h))
            blobs.forEach { b ->
                val ph = (t / b.period) * (2 * Math.PI).toFloat() + b.phase
                val dx = b.ampX * Math.sin(ph.toDouble()).toFloat()
                val dy = b.ampY * Math.cos(ph * 0.83).toFloat()
                val sc = (1f + b.scaleAmp * Math.sin(ph * 0.61).toFloat()) * zoom
                val cx = (b.x0 + dx) * w
                val cy = (b.y0 + dy) * h
                val rx = b.rx * w * sc
                val ry = b.ry * h * sc
                withTransform({
                    translate(cx, cy)
                    scale(1f, ry / rx, Offset.Zero)
                }) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to b.color, b.inner to b.color,
                                b.midAt to b.mid, 1f to Color.Transparent
                            ),
                            radius = rx
                        ),
                        radius = rx
                    )
                }
            }
            if (vignette) {
                val cx = w * 0.5f
                val cy = h * 0.42f
                val far = maxOf(
                    Math.hypot((cx).toDouble(), (cy).toDouble()),
                    Math.hypot((w - cx).toDouble(), (cy).toDouble()),
                    Math.hypot((cx).toDouble(), (h - cy).toDouble()),
                    Math.hypot((w - cx).toDouble(), (h - cy).toDouble())
                ).toFloat()
                drawRect(brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.55f to Color.Transparent,
                        0.86f to Color(0x5703050A),
                        1f to Color(0x9903050A)
                    ),
                    center = Offset(cx, cy), radius = far
                ), size = androidx.compose.ui.geometry.Size(w, h))
            }
        }
    }
}
