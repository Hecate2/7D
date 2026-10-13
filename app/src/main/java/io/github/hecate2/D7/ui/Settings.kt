package io.github.hecate2.D7.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import io.github.hecate2.D7.R
import io.github.hecate2.D7.view.LineVisibility

/**
 * 用户偏好的唯一入口：屏幕常亮、取景器显示线、「不拍照」与 `+180°`。
 *
 * 三个页面各有自己要读的项（照片组页读常亮，采集页读其余），但它们是同一份用户偏好，
 * 分成两个存储只会让人问「这个开关记在哪儿」。偏好文件沿用历史名 `capture`：它只是
 * 内部文件名，对用户不可见，而改名会把用户已经调好的显示线一次性清回默认值。
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("capture", Context.MODE_PRIVATE)

    /**
     * 屏幕常亮，默认开。
     *
     * 三个页面都要用户举着手机盯画面——采集时要连着按快门与长按，看结果时要比对时间轴，
     * 中途熄屏既打断操作又要重新解锁。关掉即清掉常亮标志，交回系统默认超时策略。
     */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(value) = prefs.edit { putBoolean("keep_screen_on", value) }

    /** `+180°`：后摄视轴与机身朝向读数差约 180 度，默认叠加。 */
    var plus180: Boolean
        get() = prefs.getBoolean("plus180", true)
        set(value) = prefs.edit { putBoolean("plus180", value) }

    /**
     * 「不拍照」：快门只落角度点，不拍照也不写相册。默认关。
     *
     * 取景预览、对焦与手电筒照常——预览就是瞄准工具；省掉的是 JPEG 编码、EXIF 回写
     * 与 MediaStore 发布这一整条 IO。只想要轮廓、不想留照片（或不想在相册里留一堆
     * 楼体照）时用它。
     */
    var noPhoto: Boolean
        get() = prefs.getBoolean("no_photo", false)
        set(value) = prefs.edit { putBoolean("no_photo", value) }

    /** 一条显示线当前开不开。 */
    fun isOn(line: CaptureLine): Boolean = prefs.getBoolean(line.prefKey, line.defaultOn)

    /** 开关一条显示线。 */
    fun setOn(line: CaptureLine, on: Boolean) = prefs.edit { putBoolean(line.prefKey, on) }

    /** 当前设置对应的显示线组合。 */
    fun lines(): LineVisibility = LineVisibility(
        summer = isOn(CaptureLine.SUMMER),
        equinox = isOn(CaptureLine.EQUINOX),
        winter = isOn(CaptureLine.WINTER),
        today = isOn(CaptureLine.TODAY),
        sun = isOn(CaptureLine.SUN),
        segments = isOn(CaptureLine.SEGMENTS),
        horizon = isOn(CaptureLine.HORIZON),
        vertical = isOn(CaptureLine.VERTICAL),
        fill = isOn(CaptureLine.FILL),
        ground = isOn(CaptureLine.GROUND),
    )
}

/**
 * 「显示线」的十个条目：对话框文案、线色、持久化键与默认显隐。
 *
 * 对话框条目顺序就是 [entries] 的顺序，设置读写也按条目本身走，所以加一项只需在这里加一行；
 * 下标的含义不再散在三个文件里靠注释对齐。线色与
 * [io.github.hecate2.D7.view.ViewfinderOverlayView] 画线时取的是同一批 @color，
 * 条目文字即用它上色，所见即所画。
 */
enum class CaptureLine(
    /** 对话框里的条目文案。 */
    @StringRes val labelRes: Int,
    /** 该线的颜色。 */
    @ColorRes val colorRes: Int,
    /** SharedPreferences 键。 */
    val prefKey: String,
    /** 首次安装的默认显隐，同时也是 [LineVisibility] 各字段的默认值。 */
    val defaultOn: Boolean,
) {
    SUMMER(R.string.line_summer, R.color.summer, "line_summer", true),
    EQUINOX(R.string.line_equinox, R.color.equinox, "line_equinox", true),
    WINTER(R.string.line_winter, R.color.winter, "line_winter", true),
    TODAY(R.string.line_today, R.color.paper, "line_today", true),
    SUN(R.string.line_sun, R.color.sun, "line_sun", true),
    SEGMENTS(R.string.line_segments, R.color.paper, "line_segments", true),
    HORIZON(R.string.line_horizon, R.color.smoke, "line_horizon", false),
    VERTICAL(R.string.line_vertical, R.color.smoke, "line_vertical", false),
    FILL(R.string.line_fill, R.color.moon, "line_fill", true),
    GROUND(R.string.line_ground, R.color.ground, "line_ground", true),
}
