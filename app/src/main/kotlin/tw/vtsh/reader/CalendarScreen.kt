package tw.vtsh.reader

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 行事曆覆盖层。吸取旧「月历」的教训——不做月网格，改成事件为中心的时间线：
 * 今天 / 未来一週 / 未來一個月 / 更多，配部门筛选 chips（复用滑块选中态）。
 * 数据由 CalendarRepo 运行时从学校 PDF 解析，一次拉取当天缓存复用。
 */
@Composable
fun CalendarScreen(
    clock: Float, screen: Offset, parallax: Float, tier: GlassTier,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = Prefs.get(ctx)
    val scope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(onBack = onDismiss)

    var events by remember { mutableStateOf<List<SchEvent>?>(CalendarRepo.loadCached(ctx)) }
    var loading by remember { mutableStateOf(events == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deptFilter by remember { mutableStateOf<String?>(null) }

    fun load(force: Boolean) {
        loading = true; error = null
        scope.launch {
            try {
                events = withContext(Dispatchers.IO) { CalendarRepo.refresh(ctx, force) }
            } catch (e: Exception) {
                error = e.message ?: "連線失敗"
                events = CalendarRepo.loadCached(ctx)
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { if (events == null) load(false) }

    val today = remember { LocalDate.now(ZoneId.of("Asia/Taipei")) }
    val depts = remember(events) {
        events?.mapNotNull { it.dept }?.distinct()?.sorted() ?: emptyList()
    }
    val filtered = remember(events, deptFilter) {
        val all = events ?: emptyList()
        val base = if (deptFilter == null) all else all.filter { it.dept == deptFilter }
        base.filter { !it.date.isBefore(today) }   // 只关心今天及以后
    }
    val seen = remember { mutableSetOf<String>() }

    Box(Modifier.fillMaxSize().background(Color(0xF2050812))) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // 工具栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                GlassButton(onClick = onDismiss) {
                    Text("⌄", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("學校行事曆", color = Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                GlassButton(onClick = { load(true) }, enabled = !loading) {
                    Text(if (loading) "…" else "更新", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }

            // 部门筛选（含「全部」）
            if (depts.isNotEmpty()) {
                val scroll = rememberScrollState()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .horizontalScroll(scroll)
                ) {
                    ChipSel("全部", deptFilter == null) { deptFilter = null }
                    depts.forEach { d ->
                        ChipSel(d, deptFilter == d) { deptFilter = if (deptFilter == d) null else d }
                    }
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 30.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (loading && events == null) {
                    item {
                        Box(Modifier.fillMaxWidth().height(220.dp), Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color(0xB3FFFFFF), strokeWidth = 2.dp)
                                Spacer(Modifier.height(14.dp))
                                Text("解析行事曆…", color = Color(0xB3FFFFFF), fontSize = 13.sp)
                            }
                        }
                    }
                }
                if (error != null && events.isNullOrEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .liquidGlass(clock, screen, parallax, tier)
                                .pressCard { load(true) }.padding(22.dp)
                        ) {
                            Text("連線失敗：$error\n（點此重試）",
                                color = Color(0xB3FFFFFF), fontSize = 13.sp, lineHeight = 20.sp)
                        }
                    }
                }

                val gen = "${deptFilter ?: "ALL"}-${today}"
                val groups = groupBuckets(filtered, today)
                groups.forEach { (label, dayList) ->
                    if (dayList.isEmpty()) return@forEach
                    item {
                        Text(label, color = Color(0x99FFFFFF), fontSize = 11.sp,
                            letterSpacing = 2.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    }
                    dayList.forEachIndexed { idx, day ->
                        items(count = day.events.size, key = { "${day.date}-${it}" }) { k ->
                            val ev = day.events[k]
                            StaggerIn((idx + k).coerceAtMost(9), gen,
                                enabled = !prefs.reduceMotion, seen = seen,
                                id = "${day.date}${ev.text}") {
                                EventRow(ev, today, clock, screen, parallax, tier)
                            }
                        }
                    }
                }

                if (!loading && filtered.isEmpty() && error == null) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 60.dp), Alignment.Center) {
                            Text(if (events == null) "尚無資料，點右上「更新」"
                                else "這個範圍沒有安排了",
                                color = Color(0x8CFFFFFF), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

private data class DayGroup(val date: LocalDate, val events: List<SchEvent>)

/** 按「今天 / 未來一週 / 未來一個月 / 更多」分桶，桶内按日期再聚合成天 */
private fun groupBuckets(events: List<SchEvent>, today: LocalDate): List<Pair<String, List<DayGroup>>> {
    val byDay = events.groupBy { it.date }
        .toSortedMap()
        .map { (d, es) -> DayGroup(d, es.sortedBy { it.text }) }
    fun bucket(p: (Long) -> Boolean): List<DayGroup> = byDay.filter {
        p(ChronoUnit.DAYS.between(today, it.date))
    }
    return listOf(
        "今天" to bucket { it == 0L },
        "未來一週" to bucket { it in 1..7 },
        "未來一個月" to bucket { it in 8..30 },
        "更多活動" to bucket { it > 30 },
    )
}

@Composable
private fun EventRow(
    ev: SchEvent, today: LocalDate, clock: Float, screen: Offset, parallax: Float,
    tier: GlassTier,
) {
    val df = remember { DateTimeFormatter.ofPattern("MM/dd") }
    val isToday = ev.date == today
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .liquidGlass(clock, screen, parallax, tier, corner = 14.dp)
            .background(if (isToday) Color(0x147FB2A8) else Color.Transparent)
            .padding(horizontal = 13.dp, vertical = 10.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(48.dp)
        ) {
            Text(ev.date.format(df), color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, lineHeight = 18.sp)
            val wd = "日一二三四五六"[ev.date.dayOfWeek.value % 7]
            Text("周$wd", color = if (isToday) Color(0xFFBFE6DC) else Color(0x8CFFFFFF),
                fontSize = 10.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ev.dept?.let {
                Text(it, color = Color(0xFFFFE6A3), fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 2.dp))
            }
            Text(ev.text, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun ChipSel(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(if (selected) Color(0xE8FFFFFF) else Color(0x1AFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text,
            color = if (selected) Color(0xFF101426) else Color(0xCCFFFFFF),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}
