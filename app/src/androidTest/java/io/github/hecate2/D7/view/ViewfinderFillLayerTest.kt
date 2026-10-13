package io.github.hecate2.D7.view

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.sensor.Pose
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 取景器楼体遮挡区（天际线以下的淡白填充）的回归护栏。
 *
 * 与 [ViewfinderGroundLayerTest] 同法用像素判定：只开填充层，把叠加层按已知姿态渲染到
 * 透明位图上，认 alpha 等于 [FILL_ALPHA] 的像素。十字线是不透明的白线、且恰好画在画面
 * 正中，它的**抗锯齿边缘**会留下一批部分透明的像素（其中就有恰好 alpha=64 的），
 * 所以另渲染一张 pose=null 的位图（此时 onDraw 只画十字线）当遮罩，凡被十字线碰过的
 * 像素一律不算填充。
 *
 * 覆盖几条曾经真实存在的缺陷：
 * 1. 整段被丢弃——可见半视角（约 65 度）略大于已拍跨度时，扫描循环结束时 started
 *    已被未覆盖采样复位，`if (!started) return` 把已经画出的那一段一起扔掉，填充全没了。
 * 2. 半圆限制——只扫 90..270（北半球），拍到主半圆之外的点不画，与求值用的全周
 *    [io.github.hecate2.D7.core.Skyline] 口径不一致。
 * 3. 闭合方向——下推点写死屏幕正下方，手机横过来时填充方向与地面层不一致；
 *    正对天顶时更会拉出一条横穿天空的弦。
 * 4. 分区方向——天花板区的淡白曾一律往下填，而窗框挡的是它上方那片天，
 *    方向正好填反（见 [ceilingRegionFillsAboveTheFrameLine]）。
 */
@RunWith(AndroidJUnit4::class)
class ViewfinderFillLayerTest {

    companion object {
        /** 填充层是 alpha=64 的淡白半透明填充。 */
        const val FILL_ALPHA = 64

        const val W = 400
        const val H = 800

        /** 只留填充层，排除地面层、参考弧与太阳标记对像素统计的干扰。 */
        val ONLY_FILL = LineVisibility(
            summer = false, equinox = false, winter = false, today = false, sun = false,
            segments = false, horizon = false, vertical = false, fill = true, ground = false,
        )
    }

    private fun pose(forward: FloatArray, right: FloatArray, up: FloatArray) =
        Pose(forward, right, up, 0.0, 0.0, 0.0, 0.0, 0.0, 0)

    private fun render(p: Pose?, points: List<PointRecord>, fillAbove: Boolean = false): Bitmap {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val view = ViewfinderOverlayView(ctx)
        view.show = ONLY_FILL
        view.fillAbove = fillAbove
        view.layout(0, 0, W, H)
        view.pose = p
        view.points = points
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    /** 只有十字线的位图：用来扣掉十字线本体与其抗锯齿边缘。pose 为 null 时 onDraw 提前返回。 */
    private val mask: Bitmap by lazy { render(null, emptyList()) }

    private fun alpha(bmp: Bitmap, x: Int, y: Int): Int = bmp.getPixel(x, y) ushr 24

    private fun fillCount(scene: Bitmap, mask: Bitmap, yFrom: Int, yTo: Int): Int =
        (yFrom until yTo).sumOf { y ->
            (0 until W).count { x ->
                alpha(scene, x, y) == FILL_ALPHA && alpha(mask, x, y) == 0
            }
        }

    /** 后摄正对北方/南方且屏幕水平。 */
    private fun level(facingNorth: Boolean) = if (facingNorth) {
        pose(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
    } else {
        pose(floatArrayOf(0f, -1f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
    }

    /** 后摄朝 [facingNorth] 方向并低头 [deg] 度。 */
    private fun pitchedDown(facingNorth: Boolean, deg: Double) = run {
        val a = Math.toRadians(deg)
        val n = if (facingNorth) 1f else -1f
        val e = if (facingNorth) 1f else -1f
        pose(
            floatArrayOf(0f, n * Math.cos(a).toFloat(), -Math.sin(a).toFloat()),
            floatArrayOf(e, 0f, 0f),
            floatArrayOf(0f, n * Math.sin(a).toFloat(), Math.cos(a).toFloat()),
        )
    }

    /** 正对天顶：世界下方投影退化，[Projector.screenDown] 返回零向量。 */
    private fun straightUp() =
        pose(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))

    private fun shot(vararg azEl: Pair<Double, Double>) =
        azEl.mapIndexed { i, (az, el) -> PointRecord(az = az, el = el, takenAt = i.toLong()) }

    /**
     * 镜头正对南方（方位 180、仰角 0、无滚转）时，世界方向 (az, el) 落在哪个像素。
     * 这是取景器针孔投影的独立复算，测试用它给出期望值，而**不是**读被测代码的输出。
     * 焦距与 onDraw 一样取半视场角 32.5 度。
     */
    private fun screenX(az: Double): Double {
        val focal = (W / 2f) / Math.tan(Math.toRadians(32.5))
        return W / 2.0 + Math.tan(Math.toRadians(az - 180.0)) * focal
    }

    private fun screenY(az: Double, el: Double): Double {
        val focal = (W / 2f) / Math.tan(Math.toRadians(32.5))
        return H / 2.0 - Math.tan(Math.toRadians(el)) / Math.cos(Math.toRadians(az - 180.0)) * focal
    }

    /**
     * 该列自上而下第一个属于填充层的像素；整列都没有填充返回 -1。
     * 十字线的抗锯齿边缘会留下各种半透明像素，故照旧用 [mask] 扣掉。
     */
    private fun topFillY(scene: Bitmap, x: Int): Int {
        for (y in 0 until H) {
            if (alpha(scene, x, y) >= 32 && alpha(mask, x, y) == 0) return y
        }
        return -1
    }

    /**
     * 该像素列左右各 [halfWidth] 列里最高的那个填充像素；全都没有返回 -1。
     *
     * 缺口端头不能只看它自己那一列：端头正是一道竖直崖边，四舍五入落进去的那一列可能
     * 整个在崖边右侧（即缺口一侧），顶边自然在地平线上。取邻域的最小值，等于问「顶边有没有
     * 到过这个点的高度」，这才对应「淡白覆盖与拍摄点对齐」这件事。
     */
    private fun topFillNear(scene: Bitmap, xCenter: Int, halfWidth: Int = 2): Int {
        var best = -1
        for (x in (xCenter - halfWidth)..(xCenter + halfWidth)) {
            if (x < 0 || x >= W) continue
            val y = topFillY(scene, x)
            if (y >= 0 && (best < 0 || y < best)) best = y
        }
        return best
    }

    /**
     * 该列自下而上第一个属于填充层的像素；整列都没有返回 -1。
     *
     * 天花板区填在折线上方，折线就是填充的**下**边沿，所以量的是「最低的那个填充像素」。
     */
    private fun bottomFillY(scene: Bitmap, x: Int): Int {
        for (y in H - 1 downTo 0) {
            if (alpha(scene, x, y) >= 32 && alpha(mask, x, y) == 0) return y
        }
        return -1
    }

    /** [bottomFillY] 的邻域版：取左右各 [halfWidth] 列里最低的填充像素，理由同 [topFillNear]。 */
    private fun bottomFillNear(scene: Bitmap, xCenter: Int, halfWidth: Int = 2): Int {
        var best = -1
        for (x in (xCenter - halfWidth)..(xCenter + halfWidth)) {
            if (x < 0 || x >= W) continue
            val y = bottomFillY(scene, x)
            if (y > best) best = y
        }
        return best
    }

    @Test
    fun coveredSpanWiderThanViewFillsBelowSkyline() {
        // 已拍 90..270 远宽于可见半视角，画面内应整片都是遮挡区，天际线以上干净
        val scene = render(level(facingNorth = false), shot(90.0 to 30.0, 270.0 to 30.0))
        assertTrue("天际线以上不该有填充", fillCount(scene, mask, 0, H / 5) == 0)
        assertTrue("天际线以下整片都应是遮挡区", fillCount(scene, mask, H * 4 / 5, H) > (W * H / 5) * 0.9f)
    }

    @Test
    fun spanEndingInsideViewStillFills() {
        // 已拍跨度 150..200，正南方可见半视角是 147.5..212.5：可见段末尾落在未拍方位上。
        // 旧实现此时 started 已被复位并 return，整段填充全部消失；正确结果应是左侧约 3/4 宽。
        val scene = render(level(facingNorth = false), shot(150.0 to 30.0, 200.0 to 30.0))
        assertTrue(
            "可见段末尾越过已拍跨度时，挡住的那段仍必须画出来",
            fillCount(scene, mask, H * 4 / 5, H) > (W * H / 5) * 0.6f,
        )
    }

    @Test
    fun fillsOutsideMainHalf() {
        // 300 与 60 两个点都在北半球主半圆（90..270）之外，且连线跨过方位 0：
        // 求值按全周算，填充也必须画，且可见半视角几乎整个落在已拍跨度内
        val scene = render(level(facingNorth = true), shot(300.0 to 25.0, 60.0 to 25.0))
        assertTrue("天际线以上不该有填充", fillCount(scene, mask, 0, H / 5) == 0)
        assertTrue(
            "主半圆之外拍到的遮挡区也必须画",
            fillCount(scene, mask, H * 4 / 5, H) > (W * H / 5) * 0.9f,
        )
    }

    @Test
    fun pitchedDownFillStopsAtRaisedSkyline() {
        // 低头 5 度、天际线仰角 10 度：天际线在画面上半部，填充只到天际线为止
        val scene = render(pitchedDown(facingNorth = false, 5.0), shot(90.0 to 10.0, 270.0 to 10.0))
        assertTrue("天际线以上不该有填充", fillCount(scene, mask, 0, H / 6) == 0)
        assertTrue("天际线以下应整片填满", fillCount(scene, mask, H * 5 / 6, H) > (W * H / 6) * 0.9f)
    }

    @Test
    fun unshotAzimuthsDrawNothing() {
        // 只拍正北 300..60，却正对南方看：可见方位全未拍，不该有任何填充
        val scene = render(level(facingNorth = false), shot(300.0 to 25.0, 60.0 to 25.0))
        assertTrue("未拍方位不该凭空出现填充", fillCount(scene, mask, 0, H) == 0)
    }

    @Test
    fun lookingStraightUpDrawsNoFill() {
        // 正对天顶时「下方」在屏幕里不存在，高仰角天际线若照直闭合会拉出横穿天空的弦。
        // 取仰角 70 度：仍在视锥内（距天顶 20 度），是最容易糊成一大片的那种姿态。
        val scene = render(straightUp(), shot(90.0 to 70.0, 270.0 to 70.0))
        assertTrue("正对天顶时不该有填充", fillCount(scene, mask, 0, H) == 0)
    }

    @Test
    fun singlePointHasNoSkyline() {
        // 只有一个点不构成线段，天际线为空
        val scene = render(level(facingNorth = false), shot(180.0 to 30.0))
        assertTrue("单点不构成天际线，不该有填充", fillCount(scene, mask, 0, H) == 0)
    }

    /**
     * 填充的顶边必须落在拍摄点上，缺口的崖边必须竖直。
     *
     * 这是「淡白覆盖准不准」的像素口径。旧实现沿着从方位 0 起、每 2 度一格的等间距网格
     * 采样 [io.github.hecate2.D7.core.Skyline]，而天际线的折点落在拍摄点方位上——两者几乎
     * 不会重合。于是顶边停在网格点上，拍摄点与它之间只连一根弦：实测最坏差 33.6 度，
     * 正是缺口端头那种竖直跳变被整段摊平的位置（屏幕上两百多像素）。
     *
     * 方位角故意都不落在偶数度上（真实拍摄就该如此），每个点都能把这根弦揪出来。
     */
    @Test
    fun fillTopEdgePassesThroughEveryShotPoint() {
        // 166.4 之后是长按快门记下的经地平线段：崖边一条竖线，中间整段降到地平线。
        // 方位刻意避开 ±8 度，那是画面正中，十字线占着，像素会被 mask 扣掉。
        val points = listOf(
            PointRecord(az = 152.6, el = 24.0, takenAt = 0),
            PointRecord(az = 166.4, el = 33.0, gapAfter = true, takenAt = 1),
            PointRecord(az = 196.7, el = 27.0, takenAt = 2),
            PointRecord(az = 208.9, el = 15.0, takenAt = 3),
        )
        val scene = render(level(facingNorth = false), points)

        for (p in points) {
            val x = Math.round(screenX(p.az)).toInt()
            val want = screenY(p.az, p.el)
            val got = topFillNear(scene, x)
            assertTrue(
                "方位 ${p.az} 附近整片都没有填充，顶边根本没画到这个高度",
                got >= 0,
            )
            assertTrue(
                "方位 ${p.az} 附近的顶边最高只到 y=$got，拍摄点在 y=${want.toInt()}：" +
                    "淡白覆盖与拍摄点对不上（差 ${got - want.toInt()} 像素）",
                Math.abs(got - want) <= 4.0,
            )
        }

        // 缺口中间：顶边必须降到地平线（画面正中），而不是斜着从两端连过去。
        val gapX = Math.round(screenX(190.0)).toInt()
        val gapY = topFillY(scene, gapX)
        assertTrue(
            "缺口中间没有填充，缺口段没画出来",
            gapY >= 0,
        )
        assertTrue(
            "缺口中间的顶边在 y=$gapY，地平线在 y=${H / 2}：缺口没有落到地平线",
            Math.abs(gapY - H / 2) <= 4.0,
        )
    }
    /**
     * 天花板区的填充必须落在折线**上方**。
     *
     * 用户提的：切到天花板区拍窗框上沿，淡白却铺在点下面——那是外部建筑区的方向。
     * 窗框挡的是它上面那片天（太阳要低于框沿才照得进来），填在下面正好填反：
     * 该看见遮挡的上半屏干干净净，太阳根本照不到的窗口下方反而一片白。
     */
    @Test
    fun ceilingRegionFillsAboveTheFrameLine() {
        // 正对南方、镜头水平，框沿仰角 10 度：折线在画面中线稍上方，其上应整片填满
        val scene = render(level(facingNorth = false), shot(90.0 to 10.0, 270.0 to 10.0), fillAbove = true)
        val frameY = screenY(180.0, 10.0).toInt()
        assertTrue(
            "窗框以上应整片都是遮挡区",
            fillCount(scene, mask, 0, frameY - 12) > (W * (frameY - 12)) * 0.9f,
        )
        assertTrue(
            "窗框以下不该有填充——那一侧是太阳照得进来的方向",
            fillCount(scene, mask, frameY + 12, H) == 0,
        )
    }

    /** 天花板区填充的下边沿同样要落在拍摄点上，不能停在采样格上（与外部区的顶边同一件事）。 */
    @Test
    fun ceilingFillBottomEdgePassesThroughEveryShotPoint() {
        val points = listOf(
            PointRecord(az = 152.6, el = 24.0, takenAt = 0),
            PointRecord(az = 196.7, el = 33.0, takenAt = 1),
            PointRecord(az = 208.9, el = 15.0, takenAt = 2),
        )
        val scene = render(level(facingNorth = false), points, fillAbove = true)

        for (p in points) {
            val x = Math.round(screenX(p.az)).toInt()
            val want = screenY(p.az, p.el)
            val got = bottomFillNear(scene, x)
            assertTrue("方位 ${p.az} 附近整片都没有填充，下边沿根本没画到这个高度", got >= 0)
            assertTrue(
                "方位 ${p.az} 附近的下边沿最低只到 y=$got，拍摄点在 y=${want.toInt()}：" +
                    "淡白覆盖与拍摄点对不上（差 ${got - want.toInt()} 像素）",
                Math.abs(got - want) <= 4.0,
            )
        }
    }

    /**
     * 天花板区未拍到的方位依然不填。
     *
     * 这里两处口径**故意**不同：求值把天花板区未拍方位当作全遮挡（偏保守），
     * 而填充层只画量过的那一段，没量过的由覆盖条与结果页覆盖率去说。
     * 把整屏都涂白的「按模型算」看着更一致，却会盖掉用户正要对准的画面。
     */
    @Test
    fun ceilingRegionStillDrawsNothingForUnshotAzimuths() {
        // 只拍正北 300..60，却正对南方看：可见方位全未拍，不该有任何填充
        val scene = render(
            level(facingNorth = false), shot(300.0 to 25.0, 60.0 to 25.0), fillAbove = true,
        )
        assertTrue("未拍方位不该凭空出现填充", fillCount(scene, mask, 0, H) == 0)
    }

}
