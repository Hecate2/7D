package io.github.hecate2.D7.view

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hecate2.D7.core.Solar
import io.github.hecate2.D7.core.SolarPosition
import io.github.hecate2.D7.sensor.Pose
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 取景器里「此刻太阳」那一点的回归护栏。
 *
 * 与 [ViewfinderFillLayerTest] 同法用像素判定：只开太阳标记，把叠加层按已知姿态渲染到透明
 * 位图上，认那批**完全不透明**的金色像素——柔光是同色 alpha=64，圆盘是不透明的，按 alpha
 * 就分得开，不必再渲染一张遮罩。找到的像素取质心当圆心，容差按像素给。
 *
 * 期望位置由测试自己重算一遍针孔投影得出，不读被测代码的输出。时刻由
 * [ViewfinderOverlayView.nowMillis] 钉死：太阳位置随时间走，「此刻画在哪儿」与「夜里不画」
 * 这两类断言没法拿真实时钟去撞。
 *
 * 覆盖四件事：位置准（镜头对准太阳时它就在画面正中）、跟着钟走（同一姿态换个时刻就挪一段，
 * 且挪到的位置与独立复算一致）、落到地平线下不画、这一项关掉就真不画。
 */
@RunWith(AndroidJUnit4::class)
class SunMarkerTest {

    private companion object {
        const val W = 400
        const val H = 800

        /**
         * 圆盘的颜色，故意照抄 `colors.xml` 里的 `sun` 而不去读资源：这条断言要锁的正是
         * 「画的确实是那个金」，从被测代码里取期望值等于自己证明自己。
         */
        const val SUN_ARGB = 0xFFFFC107.toInt()

        /** 质心容差（像素）：圆盘半径约 5dp，取质心比盯某个固定像素稳得多。 */
        const val TOLERANCE_PX = 5.0

        /** 北京：参考弧与太阳标记共用同一套经纬度。 */
        const val LAT = 39.9
        const val LON = 116.4

        /** 夏至中午（UTC 04:00 = 北京 12:00 前后）：太阳高、方位近正南。 */
        val NOON: Long = Instant.parse("2026-06-21T04:00:00Z").toEpochMilli()

        /** 两小时后：同一姿态下它该往西挪一段，且仍在画面里。 */
        val TWO_HOURS_LATER: Long = NOON + 2 * 3_600_000L

        /** 北京当地午夜（UTC 16:00）：太阳在地平线下二十多度。 */
        val LOCAL_MIDNIGHT: Long = Instant.parse("2026-06-21T16:00:00Z").toEpochMilli()

        /** 只留太阳标记，排除参考弧、地面层与遮挡填充对像素统计的干扰。 */
        val ONLY_SUN = LineVisibility(
            summer = false, equinox = false, winter = false, today = false, sun = true,
            segments = false, horizon = false, vertical = false, fill = false, ground = false,
        )

        val NO_SUN = LineVisibility(
            summer = false, equinox = false, winter = false, today = false, sun = false,
            segments = false, horizon = false, vertical = false, fill = false, ground = false,
        )
    }

    /** 金色圆盘像素的质心与个数。 */
    private class Blob(val x: Double, val y: Double, val count: Int)

    private fun render(p: Pose, nowMillis: Long, show: LineVisibility = ONLY_SUN): Bitmap {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val view = ViewfinderOverlayView(ctx)
        view.show = show
        view.latDeg = LAT
        view.lonDeg = LON
        view.nowMillis = { nowMillis }
        view.layout(0, 0, W, H)
        view.pose = p
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    /** 一次取整幅像素再筛；逐像素 getPixel 在这张 400×800 上要慢一个量级。 */
    private fun sunBlob(bmp: Bitmap): Blob? {
        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        var sx = 0L
        var sy = 0L
        var n = 0
        for (i in px.indices) {
            if (px[i] != SUN_ARGB) continue
            sx += i % W
            sy += i / W
            n++
        }
        return if (n == 0) null else Blob(sx.toDouble() / n, sy.toDouble() / n, n)
    }

    private fun requireBlob(bmp: Bitmap, what: String): Blob {
        val blob = sunBlob(bmp)
        if (blob == null) throw AssertionError("$what：整幅图里一颗太阳像素都没有")
        return blob
    }

    /** 世界方向（方位、仰角）在 ENU（东、北、天）里的单位向量。 */
    private fun dir(azDeg: Double, elDeg: Double): DoubleArray {
        val az = Math.toRadians(azDeg)
        val el = Math.toRadians(elDeg)
        return doubleArrayOf(sin(az) * cos(el), cos(az) * cos(el), sin(el))
    }

    /**
     * 后摄视轴瞄准给定方向并且没有滚转的姿态。
     *
     * 屏幕「上」取世界天顶在视轴垂面上的投影，屏幕「右」取 forward × up——与
     * [ViewfinderFillLayerTest] 里那几组手写姿态同一套约定（镜头对南方时屏幕右是西）。
     */
    private fun poseLookingAt(azDeg: Double, elDeg: Double): Pose {
        val f = dir(azDeg, elDeg)
        var ux = -f[2] * f[0]
        var uy = -f[2] * f[1]
        var uz = 1.0 - f[2] * f[2]
        val un = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= un
        uy /= un
        uz /= un
        val r = doubleArrayOf(f[1] * uz - f[2] * uy, f[2] * ux - f[0] * uz, f[0] * uy - f[1] * ux)
        return Pose(
            forward = floatArrayOf(f[0].toFloat(), f[1].toFloat(), f[2].toFloat()),
            right = floatArrayOf(r[0].toFloat(), r[1].toFloat(), r[2].toFloat()),
            up = floatArrayOf(ux.toFloat(), uy.toFloat(), uz.toFloat()),
            frontAzDeg = 0.0,
            frontElDeg = 0.0,
            smoothAzDeg = 0.0,
            smoothElDeg = 0.0,
            rollDeg = 0.0,
            accuracy = 0,
        )
    }

    /**
     * 该姿态下，某个世界方向落在哪个像素——取景器针孔投影的独立复算，
     * 焦距与 onDraw 一样取半视场角 32.5 度。
     */
    private fun screenOf(p: Pose, azDeg: Double, elDeg: Double): DoubleArray {
        val focal = (W / 2.0) / tan(Math.toRadians(32.5))
        val d = dir(azDeg, elDeg)
        fun dot(v: FloatArray) = d[0] * v[0] + d[1] * v[1] + d[2] * v[2]
        val z = dot(p.forward)
        if (z <= 0.01) throw AssertionError("方位 ${fmt(azDeg)} 落在相机背后，用例前提不成立")
        return doubleArrayOf(W / 2.0 + dot(p.right) / z * focal, H / 2.0 - dot(p.up) / z * focal)
    }

    /** 太阳此刻的世界方向，用几何高度角（不含大气折射），与叠加层的口径一致。 */
    private fun sunAt(nowMillis: Long): SolarPosition =
        Solar.position(nowMillis, LAT, LON, refraction = false)

    private fun assertBlobAt(blob: Blob, want: DoubleArray, what: String) {
        assertTrue(
            "$what：圆盘质心在 (${fmt(blob.x)}, ${fmt(blob.y)})，独立复算在 " +
                "(${fmt(want[0])}, ${fmt(want[1])})",
            abs(blob.x - want[0]) <= TOLERANCE_PX && abs(blob.y - want[1]) <= TOLERANCE_PX,
        )
    }

    private fun fmt(v: Double) = String.format("%.1f", v)

    /** 镜头正对太阳：那一点必须在画面正中，而正中也是十字线所在——圆盘要压在它上面。 */
    @Test
    fun sunMarkerSitsWhereTheSunIs() {
        val sun = sunAt(NOON)
        val scene = render(poseLookingAt(sun.azimuthDeg, sun.elevationDeg), NOON)
        val blob = requireBlob(scene, "对准太阳")
        assertBlobAt(blob, doubleArrayOf(W / 2.0, H / 2.0), "对准太阳")
        assertTrue("圆盘只有 ${blob.count} 个像素，太小了，多半根本没画出来", blob.count > 20)
    }

    /**
     * 同一个姿态，两个时刻，太阳各在自己该在的地方——这就是「实时」那一半。
     *
     * 姿态取两个时刻的中点方向，让两次都在画面里且拉开距离；两处位置都要与独立复算的
     * 投影对齐，光看「挪了」证明不了挪对了。
     */
    @Test
    fun sunMarkerFollowsTheClock() {
        val first = sunAt(NOON)
        val second = sunAt(TWO_HOURS_LATER)
        // 两个方位都在 180..215 附近，不会绕过 0 度，取算术平均即可
        val pose = poseLookingAt(
            (first.azimuthDeg + second.azimuthDeg) / 2,
            (first.elevationDeg + second.elevationDeg) / 2,
        )

        val blob1 = requireBlob(render(pose, NOON), "中午那一张")
        val blob2 = requireBlob(render(pose, TWO_HOURS_LATER), "两小时后那一张")
        assertBlobAt(blob1, screenOf(pose, first.azimuthDeg, first.elevationDeg), "中午")
        assertBlobAt(blob2, screenOf(pose, second.azimuthDeg, second.elevationDeg), "两小时后")

        val moved = sqrt(
            (blob1.x - blob2.x) * (blob1.x - blob2.x) + (blob1.y - blob2.y) * (blob1.y - blob2.y),
        )
        assertTrue("两小时里太阳只挪了 ${fmt(moved)} 像素，像是没跟着钟走", moved > 40.0)
    }

    /** 太阳在地平线以下：与今日弧一起从画面里消失，而不是留一个点贴在地平线上。 */
    @Test
    fun sunMarkerHiddenBelowHorizon() {
        val sun = sunAt(LOCAL_MIDNIGHT)
        assertTrue(
            "北京夏至午夜的高度角是 ${fmt(sun.elevationDeg)}，用例前提不成立",
            sun.elevationDeg < 0.0,
        )
        // 姿态正对午夜太阳的方向：若画了，它就会出现在画面正中
        val scene = render(poseLookingAt(sun.azimuthDeg, sun.elevationDeg), LOCAL_MIDNIGHT)
        assertTrue("太阳在地平线下时不该画", sunBlob(scene) == null)
    }

    /** 「显示线」里把这一项关掉，对准太阳也不画。 */
    @Test
    fun sunMarkerOffDrawsNothing() {
        val sun = sunAt(NOON)
        val scene = render(poseLookingAt(sun.azimuthDeg, sun.elevationDeg), NOON, NO_SUN)
        assertTrue("关掉这一项后不该再画", sunBlob(scene) == null)
    }
}
