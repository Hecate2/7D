package io.github.hecate2.D7.view

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hecate2.D7.sensor.Pose
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 取景器地面层（地平线以下土色）的回归护栏。
 *
 * 地面层直接决定「哪些像素属于地面」，所以用像素判定而不是截图肉眼比对：把叠加层按已知
 * 姿态渲染到透明位图上，只认地面那层半透明填充（alpha=70）。
 *
 * 十字线是不透明的、又恰好画在画面正中，会盖在地面上把 alpha 顶掉，所以另渲染一张
 * pose=null 的位图（此时 onDraw 只画十字线）当遮罩扣掉，避免把准星误判成地面或反之。
 *
 * 修复前是沿方位采样折线后只闭合最后一段，可见弧一旦跨过方位 0（正对北方拍照）就丢掉
 * 前半段，没有延伸到方位 356（正对南方拍照）则整段丢弃——正南时地面像素数为 0。
 */
@RunWith(AndroidJUnit4::class)
class ViewfinderGroundLayerTest {

    companion object {
        /** 地面层是 alpha=70 的半透明填充。 */
        const val GROUND_ALPHA = 70

        const val W = 400
        const val H = 800

        /** 只留地面层，排除参考弧与拍摄点对像素统计的干扰。 */
        val ONLY_GROUND = LineVisibility(
            summer = false, equinox = false, winter = false, today = false, sun = false,
            segments = false, horizon = false, vertical = false, fill = false, ground = true,
        )
    }

    private fun pose(forward: FloatArray, right: FloatArray, up: FloatArray) =
        Pose(forward, right, up, 0.0, 0.0, 0.0, 0.0, 0.0, 0)

    private fun render(p: Pose?): Bitmap {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val view = ViewfinderOverlayView(ctx)
        view.show = ONLY_GROUND
        view.layout(0, 0, W, H)
        view.pose = p
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    private fun alpha(bmp: Bitmap, x: Int, y: Int): Int = bmp.getPixel(x, y) ushr 24

    /** 地面像素：本次渲染是半透明填充，且没有被不透明的十字线盖住。 */
    private fun isGround(scene: Bitmap, crosshair: Bitmap, x: Int, y: Int): Boolean =
        alpha(scene, x, y) == GROUND_ALPHA && alpha(crosshair, x, y) != 255

    /** 扣除十字线后画面里可判定的像素总数，用作覆盖率的分母。 */
    private fun judgeable(crosshair: Bitmap, yFrom: Int, yTo: Int, xFrom: Int = 0, xTo: Int = W): Int =
        (yFrom until yTo).sumOf { y ->
            (xFrom until xTo).count { x -> alpha(crosshair, x, y) != 255 }
        }

    private fun groundCount(
        scene: Bitmap, crosshair: Bitmap,
        yFrom: Int, yTo: Int, xFrom: Int = 0, xTo: Int = W,
    ): Int = (yFrom until yTo).sumOf { y ->
        (xFrom until xTo).count { x -> isGround(scene, crosshair, x, y) }
    }

    /** 后摄正对北方/南方且屏幕水平，可见地平线弧分别是跨方位 0 与 90..270。 */
    private fun level(facingNorth: Boolean) = if (facingNorth) {
        pose(floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
    } else {
        pose(floatArrayOf(0f, -1f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f))
    }

    /** 后摄朝北并低头 [deg] 度。 */
    private fun pitchedDown(deg: Double) = run {
        val a = Math.toRadians(deg)
        pose(
            floatArrayOf(0f, Math.cos(a).toFloat(), -Math.sin(a).toFloat()),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, Math.sin(a).toFloat(), Math.cos(a).toFloat()),
        )
    }

    @Test
    fun facingNorthFillsWholeLowerHalf() {
        val crosshair = render(null)
        val scene = render(level(facingNorth = true))
        assertTrue("地平线以上不该有地面", groundCount(scene, crosshair, 0, H / 4) == 0)
        assertTrue(
            "地平线以下整片都应是地面",
            groundCount(scene, crosshair, H * 3 / 4, H) > judgeable(crosshair, H * 3 / 4, H) * 0.95f,
        )
        // 贴着地平线下方那一行最能暴露问题：旧实现这里只填了左半屏（764/1600），
        // 右半屏要更下面才被斜向闭合兜住，肉眼看底部是满的
        assertTrue(
            "贴地平线下方应整行铺满",
            groundCount(scene, crosshair, H / 2 + 20, H / 2 + 24) >
                judgeable(crosshair, H / 2 + 20, H / 2 + 24) * 0.95f,
        )
    }

    @Test
    fun facingSouthFillsWholeLowerHalf() {
        // 可见弧是 90..270，不跨方位 0；旧实现循环结束时 started 已复位，整段被丢弃
        val crosshair = render(null)
        val scene = render(level(facingNorth = false))
        assertTrue("地平线以上不该有地面", groundCount(scene, crosshair, 0, H / 4) == 0)
        assertTrue(
            "正南时地平线以下也必须填满，旧实现这里一个像素都没有",
            groundCount(scene, crosshair, H * 3 / 4, H) > judgeable(crosshair, H * 3 / 4, H) * 0.95f,
        )
    }

    @Test
    fun rolledNinetyDegreesFillsCorrectHalf() {
        // 手机横过来：地平线变成竖直线，地面应盖住世界下方那一侧（屏幕左侧）
        val crosshair = render(null)
        val scene = render(
            pose(floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f)),
        )
        assertTrue(
            "横滚时左半屏应是地面",
            groundCount(scene, crosshair, 0, H, 0, W / 4) > judgeable(crosshair, 0, H, 0, W / 4) * 0.95f,
        )
        assertTrue("横滚时右半屏不该是地面", groundCount(scene, crosshair, 0, H, W * 3 / 4, W) == 0)
    }

    @Test
    fun pitchedDownFillsBelowRaisedHorizon() {
        val crosshair = render(null)
        val scene = render(pitchedDown(30.0))
        assertTrue("低头时上半屏不该是地面", groundCount(scene, crosshair, 0, H / 4) == 0)
        assertTrue(
            "低头时下半屏应全是地面",
            groundCount(scene, crosshair, H / 2, H) > judgeable(crosshair, H / 2, H) * 0.95f,
        )
    }

    @Test
    fun slightlyBelowHorizonLeavesTopClean() {
        // 采集时最常见的姿态：仰角略低于 0 度，红框警告就是这时弹的。
        // 地平线只比画面中心高一点点，上部必须基本是天空，不能整屏都糊成土色。
        val crosshair = render(null)
        val scene = render(pitchedDown(5.0))
        assertTrue("微俯时画面上部不该有地面", groundCount(scene, crosshair, 0, H / 4) == 0)
        assertTrue(
            "地平线以下应整片填满",
            groundCount(scene, crosshair, H * 3 / 4, H) > judgeable(crosshair, H * 3 / 4, H) * 0.95f,
        )
        assertTrue(
            "地平线稍下方应整行铺满",
            groundCount(scene, crosshair, H / 2 + 30, H / 2 + 34) >
                judgeable(crosshair, H / 2 + 30, H / 2 + 34) * 0.95f,
        )
    }

    @Test
    fun lookingStraightUpDrawsNoGround() {
        // 正对天顶：地平线整圈在相机背后（z ≤ 0），屏幕上不该有任何地面
        val crosshair = render(null)
        val scene = render(
            pose(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)),
        )
        assertTrue("正对天顶时不该有地面", groundCount(scene, crosshair, 0, H) == 0)
    }
}