package io.github.hecate2.D7

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.hecate2.D7.TestSupport.inRow
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.result.ResultActivity
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import kotlin.math.abs

/**
 * 结果页点列的照片行为：删除单点时连带删照片、点缩略图看大图。
 *
 * 两条都是「用户点了却没反应」那一类问题的高发处：删除的目标是行内下标而不是屏幕位置、
 * 缩略图的点击又不该抢走整行的角度编辑，所以这里都走真实交互，按行定位。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ResultPointPhotoTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private var scene: ActivityScenario<ResultActivity>? = null
    private var groupId: String = ""
    private val insertedPhotos = mutableListOf<Uri>()

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
    }

    @After
    fun tearDown() {
        scene?.close()
        TestSupport.deleteAllGroups(repository)
        insertedPhotos.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        insertedPhotos.clear()
    }

    /** 播两个点：外 1（方位 100，带照片 A）与外 2（方位 120，带照片 B）。 */
    private fun seedTwoPhotoPoints(): Pair<Uri, Uri> {
        val a = TestSupport.insertTestImage(context.contentResolver, "结果页照片组").also { insertedPhotos.add(it) }
        val b = TestSupport.insertTestImage(context.contentResolver, "结果页照片组").also { insertedPhotos.add(it) }
        val group = repository.createGroup(
            "结果页照片组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        )
        groupId = group.id
        repository.appendPoint(group.id, Region.EXTERNAL, PointRecord(az = 100.0, el = 20.0, photoUri = a.toString(), takenAt = 1), false)
        repository.appendPoint(group.id, Region.EXTERNAL, PointRecord(az = 120.0, el = 25.0, photoUri = b.toString(), takenAt = 2), false)
        launch()
        return a to b
    }

    private fun launch() {
        scene = ActivityScenario.launch(
            Intent(context, ResultActivity::class.java).putExtra(Extras.GROUP_ID, groupId),
        )
        // 点列是等后台算完覆盖率才画的
        TestSupport.waitUntil(8000) {
            var ready = false
            scene?.onActivity { ready = it.findViewById<ViewGroup>(R.id.pointList).childCount > 0 }
            ready
        }
    }

    @Test
    fun deletePoint_removesItsPhotoFromAlbum() {
        val (photoA, photoB) = seedTwoPhotoPoints()

        onView(inRow(0, R.id.delete)).perform(scrollTo(), click())

        val after = TestSupport.waitFor {
            repository.get(groupId)?.external?.takeIf { it.size == 1 }
        }
        assertTrue("删的应是外 1", after.single().az == 120.0)
        assertFalse(
            "被删的点（外 1）的照片还留在相册里",
            TestSupport.mediaExists(context.contentResolver, photoA),
        )
        assertTrue(
            "没被删的点（外 2）的照片不该被连累",
            TestSupport.mediaExists(context.contentResolver, photoB),
        )
    }

    @Test
    fun tapThumb_opensThePhotoFullScreen() {
        seedTwoPhotoPoints()

        onView(inRow(0, R.id.thumb)).perform(scrollTo(), click())

        onView(withId(R.id.photoView)).inRoot(isDialog()).check(matches(isDisplayed()))

        // 点画面任意处关闭。关闭这件事不用 doesNotExist 直接断言——弹窗没了就是根没了，
        // Espresso 会直接抛 NoMatchingRoot 而不是通过。改成看后果：弹窗还在时结果页拿不到
        // 焦点，不带 inRoot 的 onView 找不到任何根；能点到行内删除键就说明它真的让开了。
        onView(withId(R.id.photoRoot)).inRoot(isDialog()).perform(click())
        onView(inRow(0, R.id.delete)).perform(scrollTo(), click())
        TestSupport.waitFor { repository.get(groupId)?.external?.takeIf { it.size == 1 } }
    }

    /**
     * 大图正中的准星，位置必须与照片中心重合。
     *
     * 拍照记录的方位与仰角取的是后摄视轴，针孔投影下它对应的就是画面正中；大图上这个准星
     * 就是「当时对着的那一点」。照片按 fitCenter 居中缩放，所以照片中心等于 ImageView 的中心；
     * 哪天换成 fitStart、或给某一侧加了内边距，准星就会从瞄准点上偏走，这条会红。
     */
    @Test
    fun photoCrosshair_sitsOnThePhotoCenter() {
        seedTwoPhotoPoints()

        onView(inRow(0, R.id.thumb)).perform(scrollTo(), click())

        onView(withId(R.id.photoCrosshair)).inRoot(isDialog())
            .check(matches(allOf(isDisplayed(), centeredOnPhoto())))
    }

    /** 控件的中心与同一窗口里照片的中心重合（容许 1 px 取整误差）。 */
    private fun centeredOnPhoto(): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("中心落在照片中心上")
        }

        override fun matchesSafely(item: View): Boolean {
            val photo = item.rootView.findViewById<View>(R.id.photoView) ?: return false
            val mine = IntArray(2)
            val theirs = IntArray(2)
            item.getLocationOnScreen(mine)
            photo.getLocationOnScreen(theirs)
            return abs(mine[0] + item.width / 2 - (theirs[0] + photo.width / 2)) <= 1 &&
                abs(mine[1] + item.height / 2 - (theirs[1] + photo.height / 2)) <= 1
        }
    }

    /** 没有照片的点，缩略图不该吃掉本该落到整行的点击：点它仍然打开角度编辑。 */
    @Test
    fun tapThumb_withoutPhoto_stillOpensAngleEditor() {
        val group = repository.createGroup(
            "无图点组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
        )
        groupId = group.id
        repository.appendPoint(group.id, Region.EXTERNAL, PointRecord(az = 100.0, el = 20.0, photoUri = null, takenAt = 1), false)
        repository.appendPoint(group.id, Region.EXTERNAL, PointRecord(az = 120.0, el = 25.0, photoUri = null, takenAt = 2), false)
        launch()

        onView(inRow(0, R.id.thumb)).perform(scrollTo(), click())

        onView(withId(R.id.azInput)).inRoot(isDialog()).check(matches(isDisplayed()))
    }
}
