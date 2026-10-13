package io.github.hecate2.D7.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import io.github.hecate2.D7.R
import io.github.hecate2.D7.core.Angles
import io.github.hecate2.D7.core.AzElTrack
import io.github.hecate2.D7.core.ShotPoint
import io.github.hecate2.D7.core.Skyline
import io.github.hecate2.D7.core.SkylineShape
import io.github.hecate2.D7.core.Solar
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.sensor.Pose
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** 取景器显示线的显隐设置（与 SharedPreferences 的序列化一致）。 */
data class LineVisibility(
    val summer: Boolean = true,
    val equinox: Boolean = true,
    val winter: Boolean = true,
    val today: Boolean = true,
    /** 此刻太阳所在的那一点。 */
    val sun: Boolean = true,
    val segments: Boolean = true,
    val horizon: Boolean = false,
    val vertical: Boolean = false,
    /** 天际线以下的半透明遮挡区。默认开：拍完就该一眼看到挡在哪。 */
    val fill: Boolean = true,
    /** 地平线以下的地面层（土色）。 */
    val ground: Boolean = true,
)

/**
 * 取景器叠加层：太阳参考弧与此刻太阳、地平线与铅垂线、拍摄点与连线、十字线。
 *
 * 投影为针孔模型：世界方向（东、北、天）点乘屏幕右/上/后摄视轴得相机坐标，
 * 屏幕坐标 = 中心 + (x/z, -y/z) × 焦距像素；z ≤ 0.01 视为在相机背后，剔除并断线。
 * 参考弧按赤纬采样小时角生成，只由纬度与日期决定，手机姿态决定显示哪一段；
 * 此刻太阳那一点另要经度与当前时刻，见 [drawSunMarker]。
 */
class ViewfinderOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** 显示哪些线。 */
    var show: LineVisibility = LineVisibility()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 遮挡填充往折线的哪一侧画。
     *
     * 外部建筑区填下方：楼挡的是折线以下那片天，太阳要爬过折线才见得到。
     * 天花板区反过来——窗框挡的是折线以上那片天，太阳要低于折线才见得到，所以填上方。
     * 两者都由求值侧同一套语义决定（见 [Skyline.obstructionAt] 与判定方向）。
     */
    var fillAbove: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 所在纬度（南半球参考弧镜像）。 */
    var latDeg: Double = 39.0
        set(value) {
            // 采集页每次收到数据都同步一遍地点，同值时早退，免得白清一次弧缓存
            if (value == field) return
            field = value
            arcCache.clear()
            invalidate()
        }

    /**
     * 所在经度。参考弧只由纬度与日期决定，用不到它；要用它的是此刻太阳（[drawSunMarker]），
     * 时角要按当地经度算。组内经纬度可在结果页改（见 `ui/GroupEditor.kt`），改完由采集页同步过来。
     */
    var lonDeg: Double = 116.4
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    /**
     * 当前时间源（UTC 毫秒）。姿态每刷新一帧就重画一次，所以画出来的始终是「此刻」。
     * 留成可替换是为了测试能钉住时刻：太阳位置随时间走，「此刻画在哪儿」与「落到
     * 地平线以下不画」这类断言没法拿真实时钟去撞。
     */
    var nowMillis: () -> Long = System::currentTimeMillis

    /** 当前姿态（瞬时向量）；null 时只画十字线。 */
    var pose: Pose? = null

    /** 当前分区的拍摄点，按拍摄顺序。 */
    var points: List<PointRecord> = emptyList()
        set(value) {
            field = value
            gapPathCache.clear()
            invalidate()
        }

    /** 十字线方位角（+180° 药丸后的显示值），供地平线与铅垂线参考。 */
    var aimAzDeg: Double = 180.0
        set(value) {
            field = value
            guideCacheAz = Double.NaN
        }

    /**
     * 地平线/铅垂线的缓存方位角。这两条参考线的采样只是固定的角度偏移集合，
     * 每帧真正变的只有 aimAzDeg 一个数，故整段重建缓存即可——姿态每秒刷新数十次，
     * 逐帧重建采样数组会在采集时持续制造垃圾。
     */
    private var guideCacheAz = Double.NaN

    /** 焦距像素提供者（相机就绪后由 CameraController 计算，失败时用半视场角假设）。 */
    var focalPxProvider: ((Int, Int) -> Float)? = null

    /**
     * 按住快门的进度 0..1（长按阈值前逐渐填满）；NaN 表示不画。
     * 准星外再套一圈进度，让「按住」这段等待看得见；只在接受到动画帧时才重绘。
     */
    var pressProgress: Float = Float.NaN
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    /** 中心十字线的画法；结果页的大图查看复用同一份，见 [AimCrosshair]。 */
    private val crosshair = AimCrosshair(context)

    private val colorPaper = ContextCompat.getColor(context, R.color.paper)
    private val colorMoon = ContextCompat.getColor(context, R.color.moon)
    private val colorSmoke = ContextCompat.getColor(context, R.color.smoke)
    private val colorSun = ContextCompat.getColor(context, R.color.sun)

    private fun arcPaint(color: Int, dashed: Boolean): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        if (dashed) pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }

    private val summerArc = arcPaint(ContextCompat.getColor(context, R.color.summer), true)
    private val equinoxArc = arcPaint(ContextCompat.getColor(context, R.color.equinox), true)
    private val winterArc = arcPaint(ContextCompat.getColor(context, R.color.winter), true)
    private val todayArc = arcPaint(colorPaper, false)

    private val solidLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorPaper
    }
    private val moonLine = Paint(solidLine).apply {
        color = colorMoon
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(3f)), 0f)
    }
    private val smokeLine = Paint(solidLine).apply {
        strokeWidth = dp(1.2f)
        color = colorSmoke
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }
    private val pressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = colorSmoke
        alpha = 88
    }
    private val pressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.press)
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorPaper
    }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.BLACK
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(8f)
        color = colorSmoke
        textAlign = Paint.Align.CENTER
    }

    /**
     * 太阳标记：琥珀圆盘 + 同色柔光 + 细黑描边。
     *
     * 取景画面一半是亮天空、一半是暗楼体，单一颜色在某一侧总会糊掉，所以三层各自解决一件事：
     * 圆盘给出「就在这儿」，柔光让它在暗楼体上有光晕，黑描边让它在亮天空上仍有边界。
     * 颜色取比夏至橙更亮的金，免得被那条同色系的虚线认错。
     */
    private val sunDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorSun
    }
    private val sunGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorSun
        alpha = SUN_GLOW_ALPHA
    }
    private val sunRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.BLACK
        alpha = 160
    }

    private companion object {
        /** 参考弧按小时角 [ARC_HA_STEP_DEG] 度采样，-180..180 含两端共 181 点。 */
        const val ARC_HA_STEP_DEG = 2.0
        const val ARC_SAMPLE_COUNT = 181

        /** 地面层的方位采样步长（度）：4 度约 91 个点，足够平滑且开销可忽略。 */
        const val GROUND_AZ_STEP = 4

        /**
         * 填充层的等间距采样步长（度），与 [SkylineShape] 的绘图步长一致，整圈 181 点；
         * 拍摄点方位及其两侧会额外插入，见 [Skyline.fillAzimuths]。
         */
        const val FILL_AZ_STEP = 2.0

        /**
         * 闭合多边形往「远处」推点的距离（像素）。取远大于任何屏幕尺寸的值，
         * 配合画布裁剪即可得到「从这条线一路铺到画面外」的效果，避免在可见段内部
         * 猜测闭合点而拉出错误斜边。地面层与遮挡填充共用，只差一个方向。
         */
        const val FAR_REACH = 100_000f

        /** 太阳标记的圆盘半径与柔光半径（dp）；柔光只是让暗楼体上的那一点有光晕。 */
        const val SUN_DISC_DP = 5f
        const val SUN_GLOW_DP = 10f

        /** 柔光透明度：再淡就看不见，再浓就把圆盘本身糊成一团。 */
        const val SUN_GLOW_ALPHA = 64
    }

    /** 地面层：地平线以下的土色，仿飞机姿态仪。 */
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ground)
        alpha = 70
    }

    /**
     * 遮挡填充：折线挡光那一侧的淡白半透明层（外部区是折线以下，天花板区是折线以上）。
     *
     * alpha 26（10%）在取景器的亮天空上几乎看不出遮挡区，等于白画；64（约 25%）才既
     * 能一眼看出「这片被挡了」，又压不住画在它上面的轨迹弧与连线。
     */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorPaper
        alpha = 64
    }

    private val path = Path()
    private val pt = FloatArray(2)

    /** [Projector.screenDown] 等方向向量的暂存，避免逐帧装箱。 */
    private val scratch = FloatArray(2)
    private val arcRect = RectF()

    /**
     * 填充层从折线往外推的方向（单位向量）。外部区取世界下方、天花板区取世界上方，
     * 由 [drawSkylineFill] 每帧按 [fillAbove] 定好符号后写入。
     */
    private var fillDirX = 0f
    private var fillDirY = 1f

    /** 拍摄点转算法点的缓存：点列按引用比对，拍照/删点后自然失效。 */
    private var cachedPoints: List<PointRecord>? = null
    private var cachedShots: List<ShotPoint>? = null

    /** 复用同一个投影器：姿态每帧变，但对象本身无需重建。 */
    private val projector = Projector()

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        drawCrosshair(canvas)
        drawPressProgress(canvas)
        val p = pose ?: return

        val focal = focalPxProvider?.invoke(width, height)
            ?: ((width / 2f) / tan(32.5 * PI / 180.0).toFloat())
        projector.reset(p, width / 2f, height / 2f, focal)

        // 地面层在最下层，遮挡填充在其上，参考弧与连线最上
        if (show.ground) drawGround(canvas, projector)
        if (show.fill) drawSkylineFill(canvas, projector)

        // 一次取时刻，供今日弧与此刻太阳共用：两处各读一次钟的话，
        // 恰好在午夜前后跨日时会算出对不上的两条轨迹
        val now = nowMillis()
        val summerDecl = if (latDeg >= 0) 23.44 else -23.44
        if (show.summer) drawSunArc(canvas, projector, summerDecl, summerArc)
        if (show.equinox) drawSunArc(canvas, projector, 0.0, equinoxArc)
        if (show.winter) drawSunArc(canvas, projector, -summerDecl, winterArc)
        // 今日弧的赤纬取「此刻」那个值，太阳标记读的也是同一个值，两点才落在同一条线上
        if (show.today) drawSunArc(canvas, projector, Solar.declinationDeg(now), todayArc)
        // 太阳标记跟着参考弧走，所以画在弧这一层：用户自己的拍摄点与连线仍压在最上面
        if (show.sun) drawSunMarker(canvas, projector, now)
        if (show.horizon || show.vertical) {
            ensureGuideAzimuths()
            if (show.horizon) drawTrack(canvas, projector, smokeLine, horizonTrack)
            if (show.vertical) drawTrack(canvas, projector, smokeLine, verticalTrack)
        }
        if (show.segments) {
            drawSegments(canvas, projector)
            drawPoints(canvas, projector)
        }
    }

    /** 十字线：画法见 [AimCrosshair]（结果页的大图用同一份）。 */
    private fun drawCrosshair(canvas: Canvas) {
        crosshair.draw(canvas, width / 2f, height / 2f)
    }

    /** 按住进度：准星外侧的灰色底环 + 琥珀色进度弧（顺时针从正上方起画）。 */
    private fun drawPressProgress(canvas: Canvas) {
        val progress = pressProgress
        if (progress.isNaN()) return
        val cx = width / 2f
        val cy = height / 2f
        val r = dp(19f)
        canvas.drawCircle(cx, cy, r, pressTrackPaint)
        val sweep = 360f * progress.coerceIn(0f, 1f)
        if (sweep <= 0f) return
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arcRect, -90f, sweep, false, pressArcPaint)
    }

    /**
     * 地面层：地平线（仰角 0）以下填土色，仿飞机姿态仪的地面。
     *
     * 地平线是过球心的大圆，在 ENU 里就是一个过原点的平面，而针孔投影把过原点的
     * 平面映成直线——所以它必定是一条直线，取两个可投影点就足以定出整条线，不必沿弧采样。
     * 原先沿方位采样且只闭合最后一段折线，而可见弧跨过方位 0（正对北方拍照）时会断成
     * 两段，前半段整个被丢掉，这就是地面填不满的原因。
     *
     * 地面一侧的延伸方向取[世界下方在屏幕上的方向][Projector.screenDown]而不是固定向下：
     * 传感器固定在机身上，手机横过来时地平线是一条竖直线，固定向下闭合只会拉出一条细长
     * 斜条而不是整片地面。
     */
    private fun drawGround(canvas: Canvas, projector: Projector) {
        var ax = 0f
        var ay = 0f
        var bx = 0f
        var by = 0f
        var haveA = false
        var haveB = false
        for (az in 0 until 360 step GROUND_AZ_STEP) {
            if (!projector.project(az.toDouble(), 0.0, pt)) continue
            if (!haveA) {
                ax = pt[0]
                ay = pt[1]
                haveA = true
            } else if (abs(pt[0] - ax) > 1f || abs(pt[1] - ay) > 1f) {
                bx = pt[0]
                by = pt[1]
                haveB = true
                break
            }
        }
        // 抬头看天：地平线整圈都在相机背后（z ≤ 0），屏幕上不该出现地面
        if (!haveA || !haveB) return

        var dx = bx - ax
        var dy = by - ay
        val lineLen = hypot(dx, dy)
        if (lineLen < 1f) return
        dx /= lineLen
        dy /= lineLen

        projector.screenDown(scratch)
        var ex = scratch[0]
        var ey = scratch[1]
        val downLen = hypot(ex, ey)
        // 几乎正对着天顶时世界下方投影退化到零向量，此时地面本就在无穷远，不画
        if (downLen < 1e-3f) return
        ex /= downLen
        ey /= downLen

        // 地平线两侧各延伸 FAR_REACH，再沿地面方向推出同样远的一个平行四边形，
        // 靠画布自身裁切，不在可见范围内猜测闭合点
        path.reset()
        path.moveTo(ax - dx * FAR_REACH, ay - dy * FAR_REACH)
        path.lineTo(bx + dx * FAR_REACH, by + dy * FAR_REACH)
        path.lineTo(bx + dx * FAR_REACH + ex * FAR_REACH, by + dy * FAR_REACH + ey * FAR_REACH)
        path.lineTo(ax - dx * FAR_REACH + ex * FAR_REACH, ay - dy * FAR_REACH + ey * FAR_REACH)
        path.close()
        canvas.drawPath(path, groundPaint)
    }

    /**
     * 遮挡填充：沿折线的边缘仰角取样，往它挡光的那一侧铺满（外部区向下、天花板区向上，
     * 见 [fillAbove]）。
     *
     * 方位按全周扫描（与求值用的 [Skyline] 同口径）：拍摄点落在主半圆之外也照样画，
     * 否则「拍到了却看不见」与实际计算范围不一致，比半圆限制更容易让人误判。
     *
     * 采样方位不是等间距网格，而是 [Skyline.fillAzimuths] 给的「网格 ∪ 拍摄点方位及其
     * 两侧」：顶边必须落在拍摄点上、缺口的竖直崖边必须画成竖线，否则填出来的覆盖与
     * 旁边那条实际画出的天际线自己就对不上。详见 [Skyline.fillAzimuths]。
     *
     * 未覆盖方位 [Skyline.obstructionAt] 返回 -∞，这里断开不填——把未测区域画成
     * 「矮天际线」会被误读成「测过了但不挡」，反而比不画更糟。未测提示由覆盖条
     * 与结果页覆盖率承担。每段连续覆盖各自闭合（[closeFillRun]），共用一条路径往下
     * 推点会把两段之间的天空也填进去。可见段末尾若落在未覆盖方位上，先前画的那段
     * 仍要正常闭合，否则整段会被丢弃——实测可见半视角略大于已拍跨度时填充全消失。
     */
    private fun drawSkylineFill(canvas: Canvas, projector: Projector) {
        if (points.size < 2) return
        // 填充从折线出发，往它「挡光的那一侧」铺：外部区是世界下方，天花板区是世界
        // 上方（见 [fillAbove]）。两侧都从 [Projector.screenDown] 取方向，天花板区取
        // 反向——世界天顶正对相机时它退化到零向量，画面里根本没有那一侧可言，
        // 此时闭合只能拉出一条横穿天空的弦，索性整层不画（与地面层同一处理）。
        projector.screenDown(scratch)
        val reach = hypot(scratch[0], scratch[1])
        if (reach < 1e-3f) return
        val sign = if (fillAbove) -1f else 1f
        fillDirX = sign * scratch[0] / reach
        fillDirY = sign * scratch[1] / reach

        val plan = fillPlan()
        val sky = plan.sky
        val azimuths = plan.azimuths
        path.reset()
        var started = false
        var firstX = 0f
        var firstY = 0f
        var lastX = 0f
        var lastY = 0f
        for (i in azimuths.indices) {
            val step = azimuths[i]
            val el = sky.obstructionAt(step)
            if (el.isFinite() && projector.project(step, el, pt)) {
                if (started) {
                    path.lineTo(pt[0], pt[1])
                } else {
                    path.moveTo(pt[0], pt[1])
                    firstX = pt[0]
                    firstY = pt[1]
                    started = true
                }
                lastX = pt[0]
                lastY = pt[1]
            } else if (started) {
                closeFillRun(firstX, firstY, lastX, lastY)
                started = false
            }
        }
        if (started) closeFillRun(firstX, firstY, lastX, lastY)
        canvas.drawPath(path, fillPaint)
    }

    /** 填充用的天际线与采样方位：只依赖点列，按引用缓存，不必逐帧重建。 */
    private class FillPlan(val sky: Skyline, val azimuths: DoubleArray)

    private var cachedFillPoints: List<PointRecord>? = null
    private var cachedFillPlan: FillPlan? = null

    private fun fillPlan(): FillPlan {
        val current = points
        cachedFillPlan?.let { if (cachedFillPoints === current) return it }
        val sky = Skyline(shotPoints())
        return FillPlan(sky, sky.fillAzimuths(FILL_AZ_STEP)).also {
            cachedFillPoints = current
            cachedFillPlan = it
        }
    }

    /**
     * 闭合一段遮挡填充：首尾两点各沿 [fillDirX]/[fillDirY] 推出 [FAR_REACH]，
     * 靠画布裁剪得到「从折线一路铺满挡光那一侧」。哪一侧由 [fillAbove] 定。
     */
    private fun closeFillRun(firstX: Float, firstY: Float, lastX: Float, lastY: Float) {
        path.lineTo(lastX + fillDirX * FAR_REACH, lastY + fillDirY * FAR_REACH)
        path.lineTo(firstX + fillDirX * FAR_REACH, firstY + fillDirY * FAR_REACH)
        path.close()
    }

    /** 拍摄点转算法点。点列每次拍照/ 删点才会换，故按引用缓存，避免逐帧重建列表。 */
    private fun shotPoints(): List<ShotPoint> {
        val current = points
        if (cachedPoints === current && cachedShots != null) return cachedShots!!
        val shots = current.map { ShotPoint(it.az, it.el, it.gapAfter) }
        cachedPoints = current
        cachedShots = shots
        return shots
    }

    /**
     * 参考弧采样缓存：只依赖纬度与赤纬，姿态变化仅重投影，不重算天文位置。
     * 缓存命中率极高，多留几条数组在内存上毫无压力。
     */
    private val arcCache = HashMap<Int, AzElTrack>()

    /**
     * 给定赤纬的全天太阳位置采样（按小时角 [ARC_HA_STEP_DEG] 度步长）。
     * 用几何高度角（不含大气折射），与天际线求值的判定基准一致。
     */
    private fun arcTrack(declDeg: Double): AzElTrack =
        arcCache.getOrPut((declDeg * 100.0).roundToInt()) {
            val azimuths = DoubleArray(ARC_SAMPLE_COUNT)
            val elevations = DoubleArray(ARC_SAMPLE_COUNT)
            var k = 0
            var ha = -180.0
            while (ha <= 180.0 && k < ARC_SAMPLE_COUNT) {
                val sun = Solar.positionFrom(latDeg, declDeg, ha, refraction = false)
                azimuths[k] = sun.azimuthDeg
                elevations[k] = sun.elevationDeg
                k++
                ha += ARC_HA_STEP_DEG
            }
            AzElTrack(azimuths, elevations)
        }

    /**
     * 把一串方位/仰角采样投到屏幕上连成折线。太阳参考弧、地平线、铅垂线、经地平线推断段
     * 四处形状不同、投影规则完全一样，共用这一份：相机背后的采样（z ≤ 0）断开，
     * 断开处各自重新起笔。
     *
     * [cutBelowSunrise] 为真时，仰角低于 [Solar.SUNRISE_THRESHOLD_DEG] 的采样也断开——
     * 参考弧在地平线上下那截不该画出来。只有太阳参考弧要这一刀。
     */
    private fun drawTrack(
        canvas: Canvas,
        projector: Projector,
        paint: Paint,
        track: AzElTrack,
        cutBelowSunrise: Boolean = false,
    ) {
        path.reset()
        var started = false
        for (i in 0 until track.size) {
            val el = track.elevations[i]
            val onScreen = !(cutBelowSunrise && el < Solar.SUNRISE_THRESHOLD_DEG) &&
                projector.project(track.azimuths[i], el, pt)
            if (onScreen) {
                if (started) {
                    path.lineTo(pt[0], pt[1])
                } else {
                    path.moveTo(pt[0], pt[1])
                    started = true
                }
            } else {
                started = false
            }
        }
        canvas.drawPath(path, paint)
    }

    /** 给定赤纬的全天参考弧（按小时角 [ARC_HA_STEP_DEG] 度步长采样），地平线以下不画。 */
    private fun drawSunArc(canvas: Canvas, projector: Projector, declDeg: Double, paint: Paint) =
        drawTrack(canvas, projector, paint, arcTrack(declDeg), cutBelowSunrise = true)

    /**
     * 此刻太阳所在的那一点：琥珀圆盘一枚。
     *
     * 位置与今日弧同源——同一次 [now]、同一个赤纬（[Solar.position] 内部算的赤纬
     * 就是 `Solar.declinationDeg` 那一个）、同一套几何高度角（不含大气折射），所以标记
     * 必定落在那条白线上，不会浮在它旁边。地平线附近那最多 0.5 度的大气折射宁可不要，
     * 也不要一个和轨迹对不上的点。太阳落到地平线以下（含等效上缘的
     * [Solar.SUNRISE_THRESHOLD_DEG] 阈值）就不画，与今日弧一起从画面里消失。
     *
     * 每帧现算一次：约二十次超越函数，而同帧光把上千个采样点投到屏幕上就要两千来次三角
     * 函数，这点开销不到百分之一，换来的是不必管「缓存什么时候作废」（改经度、跨日、
     * 跨半球各是一种忘法）。真正需要按天缓存的是一天一千四百多个采样点的算法，
     * 见 [Solar.DayTrack]。
     *
     * 相机背后（z ≤ 0）由投影自己剔除，不必另判。
     */
    private fun drawSunMarker(canvas: Canvas, projector: Projector, now: Long) {
        val sun = Solar.position(now, latDeg, lonDeg, refraction = false)
        if (sun.elevationDeg < Solar.SUNRISE_THRESHOLD_DEG) return
        if (!projector.project(sun.azimuthDeg, sun.elevationDeg, pt)) return
        canvas.drawCircle(pt[0], pt[1], dp(SUN_GLOW_DP), sunGlowPaint)
        canvas.drawCircle(pt[0], pt[1], dp(SUN_DISC_DP), sunDiscPaint)
        canvas.drawCircle(pt[0], pt[1], dp(SUN_DISC_DP), sunRingPaint)
    }

    /** 地平线：沿方位展开 ±75°，仰角恒为 0。 */
    private val horizonOffsets = DoubleArray(61) { -75.0 + it * 2.5 }

    /** 铅垂线：沿仰角 0..90°，方位固定为当前十字线方位。 */
    private val verticalElevations = DoubleArray(37) { it * 2.5 }

    /** 地平线各采样点的仰角，恒为 0。 */
    private val groundLevel = DoubleArray(horizonOffsets.size)

    /** 两条参考线的采样，由 [ensureGuideAzimuths] 在首次绘制前建好。 */
    private lateinit var horizonTrack: AzElTrack
    private lateinit var verticalTrack: AzElTrack

    /** 按当前 aimAzDeg 重建两条参考线的采样（NaN 判定保证每帧至多重建一次）。 */
    private fun ensureGuideAzimuths() {
        if (guideCacheAz == aimAzDeg) return
        guideCacheAz = aimAzDeg
        horizonTrack = AzElTrack(
            DoubleArray(horizonOffsets.size) { Angles.normalize360(aimAzDeg + horizonOffsets[it]) },
            groundLevel,
        )
        verticalTrack = AzElTrack(
            DoubleArray(verticalElevations.size) { aimAzDeg },
            verticalElevations,
        )
    }

    /**
     * 经地平线段的采样缓存，键为段起止点位。路径形状只取决于两端点，与姿态无关，
     * 而姿态每秒刷新数十次，故按点位缓存：新增/删除拍摄点时整体作废重建。
     */
    private val gapPathCache = HashMap<GapKey, AzElTrack>()

    private data class GapKey(val az0: Double, val el0: Double, val az1: Double, val el1: Double)

    /** 拍摄点连线：直接连线画白色实线，经地平线段拆三段画月灰虚线。 */
    private fun drawSegments(canvas: Canvas, projector: Projector) {
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (a.gapAfter) {
                val key = GapKey(a.az, a.el, b.az, b.el)
                val samples = gapPathCache.getOrPut(key) {
                    SkylineShape.viaHorizon(a.az, a.el, b.az, b.el)
                }
                drawTrack(canvas, projector, moonLine, samples)
            } else if (projector.project(a.az, a.el, pt)) {
                val x0 = pt[0]
                val y0 = pt[1]
                if (projector.project(b.az, b.el, pt)) {
                    canvas.drawLine(x0, y0, pt[0], pt[1], solidLine)
                }
            }
        }
    }

    /** 拍摄点：白色圆点 + 分区内序号。 */
    private fun drawPoints(canvas: Canvas, projector: Projector) {
        points.forEachIndexed { index, point ->
            if (projector.project(point.az, point.el, pt)) {
                canvas.drawCircle(pt[0], pt[1], dp(3.5f), dotFill)
                canvas.drawCircle(pt[0], pt[1], dp(3.5f), dotRing)
                canvas.drawText((index + 1).toString(), pt[0], pt[1] - dp(6f), labelPaint)
            }
        }
    }

    /** 世界方向到屏幕像素的针孔投影。可复用，姿态与视口尺寸在 [reset] 时刷新。 */
    private class Projector {
        private var cx = 0f
        private var cy = 0f
        private var focal = 0f
        private var right = FloatArray(3)
        private var up = FloatArray(3)
        private var forward = FloatArray(3)

        fun reset(pose: Pose, cx: Float, cy: Float, focal: Float) {
            this.cx = cx
            this.cy = cy
            this.focal = focal
            right = pose.right
            up = pose.up
            forward = pose.forward
        }

        fun project(azDeg: Double, elDeg: Double, out: FloatArray): Boolean {
            val az = azDeg * PI / 180.0
            val el = elDeg * PI / 180.0
            val cosEl = cos(el)
            val e = (sin(az) * cosEl).toFloat()
            val n = (cos(az) * cosEl).toFloat()
            val u = sin(el).toFloat()
            val x = e * right[0] + n * right[1] + u * right[2]
            val y = e * up[0] + n * up[1] + u * up[2]
            val z = e * forward[0] + n * forward[1] + u * forward[2]
            if (z <= 0.01f) return false
            out[0] = cx + x / z * focal
            out[1] = cy - y / z * focal
            return true
        }

        /**
         * 世界「下」（ENU 的 -z 方向）在屏幕上的方向，写入 [out]，长度不保证为 1。
         *
         * 不能当成「屏幕正下方」用：传感器固定在机身上，手机横过来时世界下方在画面里
         * 偏到了侧面，地平线也随之从水平线变成竖直线。
         */
        fun screenDown(out: FloatArray) {
            // 远处方向 d 的屏幕偏移正比于 (d·right, -d·up)，取 d = (0, 0, -1)
            out[0] = -right[2]
            out[1] = up[2]
        }
    }
}