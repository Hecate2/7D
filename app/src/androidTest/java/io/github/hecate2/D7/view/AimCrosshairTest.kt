package io.github.hecate2.D7.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 准星画法的两处护栏。
 *
 * 结果页点开大图时叠在照片正中的准星，与取景器的十字线共用 [AimCrosshair] 这一份画法。
 * 共用是刻意的：用户在大图上看到的那个点，必须就是拍照时对的那个点，两处各写一遍粗细与
 * 留白迟早会走偏成两个样子。
 *
 * 1. [bothCallSitesDrawTheSameCrosshair]：同一尺寸下把两处各渲染一遍，逐像素比对。
 *    哪天有人在其中一处改了长短、线宽或配色，这条立刻红。
 * 2. [crosshairIsNotClippedByItsOwnMeasuredSize]：按自然尺寸布局时四段短线的端点不能被裁。
 *    尺寸由 [AimCrosshair.sizePx] 自己报，改画法却忘了改尺寸就落在这条上。
 */
@RunWith(AndroidJUnit4::class)
class AimCrosshairTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val W = 400
        const val H = 800

        /** 关掉所有线：此时 [ViewfinderOverlayView.onDraw] 只画十字线。 */
        val NO_LINES = LineVisibility(
            summer = false, equinox = false, winter = false, today = false, sun = false,
            segments = false, horizon = false, vertical = false, fill = false, ground = false,
        )
    }

    private fun blank(): Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)

    /** 取景器在 [W]×[H] 上只画十字线（pose 为 null，onDraw 在十字线之后即返回）。 */
    private fun renderViewfinderCrosshair(): Bitmap {
        val view = ViewfinderOverlayView(ctx)
        view.show = NO_LINES
        view.pose = null
        view.layout(0, 0, W, H)
        val bmp = blank()
        view.draw(Canvas(bmp))
        return bmp
    }

    /**
     * 大图那个准星渲染到同样大小的画布上。直接 `layout` 到 [W]×[H]，两者的中心就都是
     * (W/2, H/2)，比对才有意义——大图里它其实只有几十 dp，居中画在自己的画布正中。
     */
    private fun renderPhotoCrosshair(): Bitmap {
        val view = AimCrosshairView(ctx)
        view.layout(0, 0, W, H)
        val bmp = blank()
        view.draw(Canvas(bmp))
        return bmp
    }

    private fun pixels(bmp: Bitmap): IntArray =
        IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }

    @Test
    fun bothCallSitesDrawTheSameCrosshair() {
        assertTrue(
            "取景器与大图两处的准星必须逐像素相同",
            pixels(renderViewfinderCrosshair()).contentEquals(pixels(renderPhotoCrosshair())),
        )
    }

    /** 画上去的像素数（alpha 非 0 即算，含抗锯齿边缘）。 */
    private fun litCount(bmp: Bitmap): Int = pixels(bmp).count { it ushr 24 != 0 }

    /** 按给定边长布局后画出的准星，落在多大的画布上不影响笔迹本身。 */
    private fun litCountAt(side: Int): Int {
        val view = AimCrosshairView(ctx)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bmp = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return litCount(bmp)
    }

    @Test
    fun crosshairIsNotClippedByItsOwnMeasuredSize() {
        val view = AimCrosshairView(ctx)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val natural = view.measuredWidth
        assertTrue("自然尺寸必须是正数（onMeasure 报了 $natural）", natural > 0)
        assertEquals("准星是正方形", natural, view.measuredHeight)

        assertEquals(
            "按自然尺寸布局时被裁掉了笔迹：画布要留出线宽的余量",
            litCountAt(natural * 4),
            litCountAt(natural),
        )
    }
}
