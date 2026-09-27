package tw.vtsh.reader

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val TierNames = listOf("折射", "輕玻璃", "省電")
private val TierDesc = listOf(
    "卡片逐帧重采样极光，最漂亮也最吃 GPU",
    "只做渐变与边缘光，不动光源",
    "纯色底板，省电与低端机推荐",
)

/** 设置面板：玻璃档位 + 减弱动效 + 桌面小部件说明 */
@Composable
fun SettingsSheet(prefs: Prefs, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF5101828))
                .padding(20.dp)
        ) {
            Text("設定", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp)
            Spacer(Modifier.height(16.dp))

            Text("玻璃質感", color = Color(0x99FFFFFF), fontSize = 11.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(8.dp))
            // 模式 1「图形化选中态」：白色底板在三个档位间滑动，而不是三块底色互切
            val rail = rememberSlideRail()
            Box {
                SlideThumb(rail, prefs.tierIndex, motion = !prefs.reduceMotion,
                    shape = RoundedCornerShape(12.dp), color = Color(0xE8FFFFFF))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TierNames.forEachIndexed { i, name ->
                        val sel = prefs.tierIndex == i
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .slideTrack(rail, i)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (sel) Color.Transparent else Color(0x1AFFFFFF))
                                .clickable { prefs.tierIndex = i }
                                .padding(vertical = 9.dp)
                        ) {
                            Text(name,
                                color = if (sel) Color(0xFF101426) else Color(0xCCFFFFFF),
                                fontSize = 13.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 模式 6「文本展开」：说明文字随档位切换交叉淡入，不是硬切
            androidx.compose.animation.AnimatedContent(
                TierDesc[prefs.tierIndex.coerceIn(0, 2)],
                transitionSpec = {
                    fadeIn(androidx.compose.animation.core.tween(motionMs(prefs, 200))) togetherWith
                        fadeOut(androidx.compose.animation.core.tween(motionMs(prefs, 120)))
                },
                label = "tierDesc",
            ) { desc ->
                Text(desc, color = Color(0x8CFFFFFF), fontSize = 11.sp, lineHeight = 17.sp)
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("減弱動效", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("适合对动效敏感或想再省一点电",
                        color = Color(0x8CFFFFFF), fontSize = 11.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = prefs.reduceMotion,
                    onCheckedChange = { prefs.reduceMotion = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF7FB2A8),
                        uncheckedTrackColor = Color(0x33FFFFFF),
                    )
                )
            }
            // 模式 8「开关联动」：开关驱动说明区块展开/收起，反馈不止于配色变化
            LinkedSection(visible = prefs.reduceMotion, enabled = !prefs.reduceMotion) {
                Text("已关闭极光漂移、交错入场、滑块与按压回弹；界面直接到达终态。",
                    color = Color(0x8CFFFFFF), fontSize = 11.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("繳費提醒", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("小工具刷新时顺带抓取繳費类公告",
                        color = Color(0x8CFFFFFF), fontSize = 11.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = prefs.feeAlerts,
                    onCheckedChange = { prefs.feeAlerts = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF7FB2A8),
                        uncheckedTrackColor = Color(0x33FFFFFF),
                    )
                )
            }
            LinkedSection(visible = !prefs.feeAlerts, enabled = !prefs.reduceMotion) {
                Text("关闭后可省一次搜索请求，桌面「繳費提醒」将停留在上次结果。",
                    color = Color(0x8CFFFFFF), fontSize = 11.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(Modifier.height(18.dp))
            Text("桌面小工具", color = Color(0x99FFFFFF), fontSize = 11.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "長按桌面 → 應用程式 → 衛道速覽 → 小工具，可选「今日公告」或「繳費提醒」。" +
                    "資料每 30 分鐘自動更新，也可點小工具上的「重新整理」。",
                color = Color(0x8CFFFFFF), fontSize = 11.sp, lineHeight = 18.sp
            )

            Spacer(Modifier.height(20.dp))
            GlassButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                bg = Color(0x1AFFFFFF),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("關閉", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 11.dp))
                }
            }
        }
    }
}
