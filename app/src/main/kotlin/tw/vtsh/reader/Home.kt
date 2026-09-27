package tw.vtsh.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

private val White = Color.White
private val Sub = Color(0xB3FFFFFF)

@Composable
fun Home(deepLink: String? = null, onDeepLinkUsed: () -> Unit = {}) {
    val screen = with(LocalDensity.current) {
        Offset(
            LocalConfiguration.current.screenWidthDp.dp.toPx(),
            LocalConfiguration.current.screenHeightDp.dp.toPx()
        )
    }
    val appCtx = LocalContext.current
    val prefs = remember { Prefs.get(appCtx) }
    val tier = GlassTier.entries[prefs.tierIndex.coerceIn(0, 2)]
    // 减弱动效时冻结场景：极光停在固定相位，卡片不再逐帧重绘
    val clock = rememberSceneClock(animated = tier == GlassTier.Full && !prefs.reduceMotion)
    val listState = rememberLazyListState()
    val parallax by remember {
        derivedStateOf {
            if (prefs.reduceMotion) 0f else
                (listState.firstVisibleItemScrollOffset +
                    listState.firstVisibleItemIndex * 90f) * -0.10f
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    var channel by remember { mutableIntStateOf(1) }
    var news by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(1) }
    var pageStart by remember { mutableIntStateOf(0) }   // 本页首条在列表中的下标（交错入场用）
    var detail by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cache = remember { RssCache(appCtx) }
    // 选中态滑块（模式 1/10）：频道 chips 一条轨道
    val chipRail = rememberSlideRail()
    // 交错入场的"轮次"：换频道/退出搜索时重播
    val staggerGen = "$channel-$searching"
    val staggerSeen = remember { mutableSetOf<String>() }

    fun load(ch: Int, p: Int, append: Boolean) {
        error = null
        val url = Rss.CHANNELS[ch].second
        val fresh = cache.fresh(url, p)
        if (fresh != null && !append) { news = fresh; page = p; pageStart = 0; loading = false; return }
        loading = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Rss.fetch(url, p) }
                if (append) pageStart = news.size else pageStart = 0
                news = if (append) news + r else r
                page = p
                cache.save(url, p, r)
            } catch (e: Exception) {
                val stale = cache.load(url, p)
                if (!append) pageStart = 0
                if (stale != null && !append) { news = stale; error = "離線模式（舊資料）" }
                else if (!append) { news = emptyList(); error = e.message ?: "連線失敗" }
                else error = e.message ?: "連線失敗"
            } finally { loading = false }
        }
    }

    fun search(q: String) {
        if (q.isBlank()) { searching = false; load(channel, 1, false); return }
        searching = true; loading = true; error = null
        scope.launch {
            try {
                news = withContext(Dispatchers.IO) { Rss.fetch(Rss.searchUrl(q)) }
            } catch (e: Exception) { news = emptyList(); error = e.message }
            finally { loading = false }
        }
    }
    LaunchedEffect(channel) { load(channel, 1, false) }
    // 桌面小工具点进来的公告
    LaunchedEffect(deepLink) {
        if (!deepLink.isNullOrBlank()) {
            detail = deepLink
            onDeepLinkUsed()
        }
    }

    // 极光背景（唯一场景源，与玻璃卡同参数）
    Box(Modifier.fillMaxSize().background(Color(0xFF04070E))) {
        Canvas(Modifier.fillMaxSize()) {
            with(Aurora) { drawScene(clock.value, screen.x, screen.y, 0f, parallax, 1f, vignette = true) }
        }

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 顶栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .liquidGlass(clock.value, screen, parallax, tier)
                ) { Text("衛", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Text("衛道速覽", color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp)
                Spacer(Modifier.width(6.dp))
                Text("非官方", color = Color(0x8CFFFFFF), fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                GlassButton(onClick = {
                    if (searching) { query = ""; searching = false; load(channel, 1, false) }
                    else searching = true
                }) {
                    Text(if (searching) "✕" else "🔍", color = Color.White, fontSize = 15.sp,
                        modifier = BtnPad)
                }
                Spacer(Modifier.width(8.dp))
                GlassButton(onClick = { showCalendar = true }) {
                    Text("曆", color = White, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                }
                Spacer(Modifier.width(8.dp))
                GlassButton(onClick = { showSettings = true }) {
                    Text("設定", color = White, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }

            // 模式 6「文本展开」变体：搜索框点开时弹性展开，收起时缓速缩回
            androidx.compose.animation.AnimatedVisibility(
                visible = searching,
                enter = androidx.compose.animation.expandVertically(
                    androidx.compose.animation.core.spring(stiffness = 900f)) +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
                exit = androidx.compose.animation.shrinkVertically(
                    androidx.compose.animation.core.tween(160)) +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = { query = it; search(it) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White, fontSize = 15.sp),
                    decorationBox = { inner ->
                        Row(Modifier
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(99.dp))
                            .liquidGlass(clock.value, screen, parallax, tier, corner = 99.dp)
                            .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("🔍", fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) Text("搜尋公告…", color = Color(0x73FFFFFF), fontSize = 15.sp)
                                inner()
                            }
                        }
                    },
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // 频道 chips（模式 10「标签展开」：选中滑块跟随 + 自动滚入视野）
            val chipScroll = remember { androidx.compose.foundation.ScrollState(0) }
            var chipViewW by remember { mutableIntStateOf(0) }
            LaunchedEffect(channel, chipRail.bounds.size, chipViewW) {
                val b = chipRail.bounds[channel] ?: return@LaunchedEffect
                if (chipViewW == 0) return@LaunchedEffect
                // bounds 是 root 坐标：+当前滚动量换算成内容坐标，再取居中目标
                val target = (b.left + chipScroll.value + b.width / 2f - chipViewW / 2f)
                    .toInt().coerceIn(0, chipScroll.maxValue)
                if (target != chipScroll.value)
                    chipScroll.animateScrollTo(target,
                        androidx.compose.animation.core.tween(motionMs(prefs, 260)))
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                .onSizeChanged { chipViewW = it.width }) {
                SlideThumb(chipRail, if (searching) null else channel, motion = !prefs.reduceMotion,
                    shape = RoundedCornerShape(99.dp), color = Color(0xDBFFFFFF))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(chipScroll)
                ) {
                    Rss.CHANNELS.forEachIndexed { i, (name, _) ->
                        val sel = i == channel && !searching
                        Box(
                            modifier = Modifier
                                .slideTrack(chipRail, i)
                                .clip(RoundedCornerShape(99.dp))
                                .then(
                                    if (sel) Modifier
                                    else Modifier.liquidGlass(clock.value, screen, parallax, tier, corner = 99.dp)
                                )
                                .clickable { if (!sel) { query = ""; searching = false; channel = i } }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(name,
                                color = if (sel) Color(0xFF101426) else White,
                                fontSize = 12.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (loading && news.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) {
                            CircularProgressIndicator(color = Sub, strokeWidth = 2.dp)
                        }
                    }
                }
                if (error != null && news.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .liquidGlass(clock.value, screen, parallax, tier)
                                .pressCard { load(channel, page, false) }
                                .padding(22.dp)
                        ) {
                            Text("連線失敗：$error\n（點此重試／離線時無快取）",
                                color = Sub, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                    }
                }
                items(count = news.size, key = { "$channel-$it" }) { idx ->
                    val n = news[idx]
                    // 模式 3「交错入场」：新加载的列表逐条错峰弹入，前 10 项内完成；
                    // 翻页追加时旧条目已记账，不重播
                    StaggerIn(index = (idx - pageStart).coerceAtLeast(0), gen = staggerGen,
                        enabled = !prefs.reduceMotion, seen = staggerSeen, id = n.link) {
                        NewsRow(n, clock.value, screen, parallax, tier) { detail = n.link }
                    }
                }
                if (news.isNotEmpty() && !searching) {
                    item {
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .liquidGlass(clock.value, screen, parallax, tier, corner = 16.dp)
                                .pressCard { load(channel, page + 1, true) }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (loading) "載入中…" else "載入更多",
                                color = White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (showSettings) SettingsSheet(prefs, onDismiss = { showSettings = false })

        if (showCalendar) {
            androidx.compose.animation.AnimatedVisibility(
                visible = true,
                enter = androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(motionMs(prefs, 180))),
            ) {
                CalendarScreen(clock.value, screen, parallax, tier,
                    onDismiss = { showCalendar = false })
            }
        }

        detail?.let { u ->
            DetailLayer(u, screen, parallax, tier, clock.value, onDismiss = { detail = null })
        }
    }
}

@Composable
private fun NewsRow(n: Item, t: Float, screen: Offset, parallax: Float,
                    tier: GlassTier, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .liquidGlass(t, screen, parallax, tier, corner = 14.dp)
            // 模式 8 触感：按下 97.5% 回弹，反馈先于内容加载到达
            .pressCard(onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(40.dp)
        ) {
            val z = n.date?.withZoneSameInstant(ZoneId.of("Asia/Taipei"))
            Text(z?.dayOfMonth?.toString() ?: "–", color = White, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, lineHeight = 20.sp)
            Text(z?.let { "${it.monthValue}月" } ?: "", color = Sub, fontSize = 10.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(n.title, color = White, fontSize = 14.sp, lineHeight = 20.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (n.unit.isNotBlank()) Chip(n.unit.replace("幹事|組長|主任|秘書|長$".toRegex(), ""))
                if (n.hasAtt) Chip("附件", fg = Color(0xFFFFE6A3), border = Color(0x80FFD678))
                n.cats.firstOrNull { it.contains("重要") || it.contains("置頂") }?.let {
                    Chip("重要", fg = Color(0xFFFFC9C9), border = Color(0x80FF9696))
                }
            }
        }
    }
}
