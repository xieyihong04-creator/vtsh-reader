package tw.vtsh.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class GlassTier { Full, Fast, Off }

/** 场景时钟（秒）。animated=false 时冻结，省电档不再逐帧失效 */
@Composable
fun rememberSceneClock(animated: Boolean): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        while (true) {
            withFrameNanos { nanos -> t.floatValue = nanos / 1_000_000_000f % 3600f }
        }
    }
    return t
}

/**
 * 液态玻璃。极光本身是程序化光场，因此卡片不必"采样背板像素"，
 * 而是按自己在窗口中的位置重绘同一场景的对应区域：
 * 无 backdrop-filter、无 blur、无离屏合成，API 29 起满速。
 */
fun Modifier.liquidGlass(
    sceneT: Float,
    screen: Offset,
    parallax: Float,
    tier: GlassTier,
    corner: Dp = 20.dp,
): Modifier = composed {
    val density = LocalDensity.current
    val pos = remember { arrayOf(Offset.Zero) }
    this
        .onGloballyPositioned { pos[0] = it.positionInWindow() }
        .drawBehind {
            val rr = RoundRect(Rect(Offset.Zero, size),
                CornerRadius(with(density) { corner.toPx() }, with(density) { corner.toPx() }))
            when (tier) {
                GlassTier.Off -> drawRect(Color(0xE6161C2E))
                GlassTier.Fast -> drawRect(
                    brush = Brush.linearGradient(
                        listOf(Color(0x59202A44), Color(0x40141B2E)),
                        start = Offset.Zero, end = Offset(size.width, size.height)
                    )
                )
                GlassTier.Full -> {
                    val p = Path().apply { addRoundRect(rr) }
                    clipPath(p) {
                    with(Aurora) { drawScene(
                        t = sceneT, w = screen.x, h = screen.y,
                        ox = pos[0].x, oy = pos[0].y - parallax,
                        zoom = 1.35f, vignette = false
                    ) }
                    // tint：压暗提对比，保证白字可读
                    drawRect(
                        brush = Brush.linearGradient(
                            0f to Color(0x3DFFFFFF), 0.45f to Color(0x2A1B2438),
                            1f to Color(0x33FFFFFF),
                            start = Offset.Zero, end = Offset(size.width, size.height)
                        )
                    )
                    // 入射光/反弹光（静态高光，不用 mix-blend-mode）
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color(0x4DFFFFFF), 0.3f to Color(0x0AFFFFFF),
                            0.75f to Color(0x00FFFFFF), 1f to Color(0x1AFFFFFF)
                        )
                    )
                    }
                }
            }
            // 边缘厚度感：上亮下中亮
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0x61FFFFFF), Color(0x14FFFFFF), Color(0x30FFFFFF))
                ),
                size = size,
                cornerRadius = CornerRadius(with(density) { corner.toPx() }),
                style = Stroke(width = with(density) { 1.dp.toPx() })
            )
        }
}

/** 元数据小标签：发布单位 / 附件 / 重要 */
@Composable
fun Chip(text: String, fg: Color = Color.White, bg: Color = Color(0x26FFFFFF),
         border: Color = Color(0x40FFFFFF)) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}
