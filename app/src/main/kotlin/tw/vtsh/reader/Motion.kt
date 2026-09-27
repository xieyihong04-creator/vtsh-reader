package tw.vtsh.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 交互动效工具箱（对应「高級交互設計」准则）。
 * 原则：动效服务于空间连续性与状态反馈，不炫技；每个动效都要回答
 * 「用户刚才的操作产生了什么结果」。所有入口都过 reduceMotion 这道闸——
 * 关掉后时长归零、位移归零，界面瞬间到达终态，不残留装饰性动画。
 */

/** 减弱动效时时长归零 */
fun motionMs(prefs: Prefs, ms: Int): Int = if (prefs.reduceMotion) 0 else ms

/** 中等弹性：用于「弹到位」的收尾（选中态滑块、按压缩放、进度环） */
val MediumBouncy = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 400f)

/**
 * 模式 3「交错入场」：列表项按序号延迟弹入，前一项约完成一半时后一项起步，
 * 整体 ~600ms 内收束，不拖慢首屏可读时间。gen 变化（换频道、重新加载）重播；
 * 同一轮里滚出再滚回不重播（LazyColumn 回收会重建组合，用 seen 集合记一次）；
 * reduceMotion 下直接显示终态。
 */
@Composable
fun StaggerIn(
    index: Int, gen: Any, enabled: Boolean,
    seen: MutableSet<String>? = null,
    id: Any = index,
    content: @Composable () -> Unit,
) {
    val key = "$gen#$id"
    val alreadySeen = seen?.contains(key) == true
    if (!enabled || alreadySeen) { content(); return }
    val alpha = remember { Animatable(0f) }
    val shiftPx = with(LocalDensity.current) { 14.dp.toPx() }
    val dy = remember { Animatable(shiftPx) }
    LaunchedEffect(gen, id) {
        seen?.add(key)   // 先记账：动画期间被回收也算看过
        alpha.snapTo(0f); dy.snapTo(shiftPx)
        // 只交错前 10 项，后面的不再排队等待
        kotlinx.coroutines.delay((index * 45L).coerceAtMost(450L))
        val spec = tween<Float>(300, easing = LinearOutSlowInEasing)
        alpha.animateTo(1f, spec)
        dy.animateTo(0f, spec)
    }
    Box(Modifier.graphicsLayer { this.alpha = alpha.value; this.translationY = dy.value }) {
        content()
    }
}

/**
 * 模式 8「开关联动」：开关变化驱动关联区块展开/收起，
 * 而不是只换开关本身的配色。弹性展开、缓速收起。
 */
@Composable
fun LinkedSection(visible: Boolean, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) { if (visible) content(); return }
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(spring(stiffness = 900f)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
    ) { content() }
}

/**
 * 模式 1/10「图形化选中态 / 标签滑动」：选中底板是一块实测出来的滑块，
 * 用弹簧在选项之间滑动并随目标宽度形变——「当前在哪」有连续的空间轨迹，
 * 而不是两块底色互切。子项用 slideTrack() 上报 root 坐标，
 * SlideThumb 挂在同一父 Box 的首位（画在文字底下），换算成局部偏移。
 */
class SlideRail {
    val bounds: SnapshotStateMap<Int, Rect> = mutableStateMapOf()
}

@Composable
fun rememberSlideRail(): SlideRail = remember { SlideRail() }

/** 挂在每个可选项上，上报它在窗口（root）中的位置——横向滚动下依然正确 */
fun Modifier.slideTrack(rail: SlideRail, index: Int): Modifier =
    onGloballyPositioned { rail.bounds[index] = it.boundsInRoot() }

/** 挂在 rail 所属 Box 的首个子节点，随选中项滑动/伸缩 */
@Composable
fun SlideThumb(
    rail: SlideRail,
    selected: Int?,
    motion: Boolean,
    shape: Shape,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val b = selected?.let { rail.bounds[it] }
    // 本 Box 的布局位（不含 offset 修饰符）：滑块局部坐标 = 目标 root 坐标 - 自身布局位
    val host = remember { arrayOf(Offset.Zero) }
    val x by androidx.compose.animation.core.animateFloatAsState(
        targetValue = (b?.left ?: 0f) - host[0].x,
        animationSpec = if (motion) MediumBouncy else tween(0), label = "thumbX")
    val y by androidx.compose.animation.core.animateFloatAsState(
        targetValue = (b?.top ?: 0f) - host[0].y,
        animationSpec = if (motion) MediumBouncy else tween(0), label = "thumbY")
    val w by androidx.compose.animation.core.animateFloatAsState(
        targetValue = b?.width ?: 0f,
        animationSpec = if (motion) MediumBouncy else tween(0), label = "thumbW")
    val h by androidx.compose.animation.core.animateFloatAsState(
        targetValue = b?.height ?: 0f,
        animationSpec = if (motion) MediumBouncy else tween(0), label = "thumbH")
    AnimatedVisibility(visible = b != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
        val density = LocalDensity.current
        Box(
            modifier
                .onGloballyPositioned { host[0] = it.positionInRoot() }
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .then(
                    with(density) {
                        Modifier.requiredSize(
                            width = w.coerceAtLeast(1f).toDp(),
                            height = h.coerceAtLeast(1f).toDp(),
                        )
                    }
                )
                .background(color, shape)
        )
    }
}

/** 玻璃按钮：按压缩放反馈（90ms 按下 + 弹簧回弹），替代裸 Text.clickable */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(99.dp),
    bg: Color = Color(0x24FFFFFF),
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed = enabled && src.collectIsPressedAsState().value
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        if (pressed) scale.animateTo(0.96f, tween(90))
        else scale.animateTo(1f, MediumBouncy)
    }
    Box(
        modifier = modifier
            .scale(scale.value)
            .background(bg, shape)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        content = content
    )
}

/** 卡片按压缩放（模式 8 的触感部分）：整卡 97.5% 回弹，150ms 内完成 */
fun Modifier.pressCard(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed = src.collectIsPressedAsState().value
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        if (pressed) scale.animateTo(0.975f, tween(90))
        else scale.animateTo(1f, MediumBouncy)
    }
    this.scale(scale.value).clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** 顶栏/工具栏按钮的通用内边距，集中一处避免各页不一致 */
val BtnPad = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
