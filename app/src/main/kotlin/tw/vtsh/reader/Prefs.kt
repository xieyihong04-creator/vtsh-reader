package tw.vtsh.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * 偏好设置（SharedPreferences + Compose 可观察快照）。
 * 只有 3 个键，不引 DataStore。读写都走属性：赋值即落盘，
 * 底层 snapshot state 变化会触发重组。
 */
class Prefs private constructor(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("vtsh_prefs", Context.MODE_PRIVATE)

    private val _tier = mutableIntStateOf(sp.getInt("tier", GlassTier.Full.ordinal))
    private val _motion = mutableStateOf(sp.getBoolean("reduce_motion", false))
    private val _fee = mutableStateOf(sp.getBoolean("fee_alerts", true))

    /** 玻璃档位：0=折射 1=輕玻璃 2=省電（默认沿用 v0.2 的折射） */
    var tierIndex: Int
        get() = _tier.intValue
        set(v) {
            if (v == _tier.intValue) return
            sp.edit().putInt("tier", v).apply()
            _tier.intValue = v
        }

    /** 减弱动效：冻结极光场景与过渡动画 */
    var reduceMotion: Boolean
        get() = _motion.value
        set(v) {
            if (v == _motion.value) return
            sp.edit().putBoolean("reduce_motion", v).apply()
            _motion.value = v
        }

    /** 缴费提醒小部件是否抓取（关掉可省一次搜索请求） */
    var feeAlerts: Boolean
        get() = _fee.value
        set(v) {
            if (v == _fee.value) return
            sp.edit().putBoolean("fee_alerts", v).apply()
            _fee.value = v
        }

    companion object {
        @Volatile private var inst: Prefs? = null

        fun get(ctx: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(ctx).also { inst = it }
            }
    }
}
