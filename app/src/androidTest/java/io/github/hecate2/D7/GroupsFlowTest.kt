package io.github.hecate2.D7

import android.Manifest
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.clearText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.rule.IntentsRule
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isFocused
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.ui.capture.CaptureActivity
import io.github.hecate2.D7.ui.groups.GroupsActivity
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.ArrayList

/**
 * 照片组管理页的仪器测试（模拟器运行，无需真机）：
 * 新建组对话框的经纬度布局与半球提示、校验拦截、建组落库，以及删组时照片保留/连带删除两条路径。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GroupsFlowTest {

    @get:Rule
    val intentsRule = IntentsRule()

    /**
     * 建组对话框一打开就自动定位。没给权限时 app 会弹系统权限对话框，那个窗口会盖在
     * AlertDialog 上面，Espresso 再找对话框里的控件就会因为「两个窗口都有」而失手。
     * 所以这里先把定位权限授予到位，让自动定位安静地跑完（模拟器多半定位失败，
     * 那只影响状态行的文案，不影响本类要验的布局与校验）。
     */
    @get:Rule
    val locationPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository get() = GroupRepository.get(context)
    private val insertedPhotos = ArrayList<Uri>()
    private var scene: ActivityScenario<GroupsActivity>? = null

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
    }
    @After
    fun tearDown() {
        scene?.close()
        insertedPhotos.forEach { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
        insertedPhotos.clear()
        TestSupport.deleteAllGroups(repository)
    }

    private fun launchGroups() {
        scene = ActivityScenario.launch(GroupsActivity::class.java)
    }

    private fun openNewGroupDialog() {
        launchGroups()
        onView(withId(R.id.newButton)).perform(click())
        onView(withText(R.string.new_group_title)).check(matches(isDisplayed()))
    }

    /** 点「开始拍摄」前先把键盘收掉：键盘是自动弹出的，不收掉有可能盖住对话框底部那颗按钮。 */
    private fun clickStartCapture() {
        onView(withId(R.id.nameInput)).perform(closeSoftKeyboard())
        onView(withText(R.string.start_capture)).perform(click())
    }



    /** 断言文本视图非空（用来验「自动定位确实启动了」而不必赌它成不成功）。 */
    private fun hasNonEmptyText(): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("文本非空")
        }

        override fun matchesSafely(item: View): Boolean =
            item is TextView && item.text.toString().isNotEmpty()
    }

    /**
     * 一个组都没有时，主显示区正中那枚大加号是整屏唯一显眼的出路，它必须和右上角那颗
     * 走同一条路。这条要单独盯着：它只在「零个组」这个状态下存在，而本类其余用例虽然也
     * 从零个组起步，但都是直接点右上角的 `newButton`，点不到它。
     */
    @Test
    fun emptyStateBigPlusOpensTheSameDialogAsTheHeaderPlus() {
        launchGroups()
        onView(withId(R.id.emptyNewButton)).check(matches(isDisplayed()))
        onView(withText(R.string.groups_empty)).check(matches(isDisplayed()))
        onView(withId(R.id.emptyNewButton)).perform(click())
        onView(withText(R.string.new_group_title)).check(matches(isDisplayed()))
    }

    @Test
    fun newGroupDialog_longitudeAboveLatitude() {
        openNewGroupDialog()
        onView(withId(R.id.lonInput)).check(
            matches(allOf(isDisplayed(), withHint(R.string.label_longitude_hint))),
        )
        assertStackedAbove(R.id.lonInput, R.id.latInput)
        onView(withId(R.id.latInput)).check(
            matches(allOf(isDisplayed(), withHint(R.string.label_latitude_hint))),
        )
    }

    /**
     * 断言两格各占一整行且上者完全位于下者之上。
     *
     * 分两行是为了让 `121.470000` 这种六位小数不被拦腰截断，所以顺带守住「宽度至少超过半屏」：
     * 并排写法的回归会把每格砍到大约四成。两格宽度不必逐像素相等——左边是 `LON`/`LAT`，
     * 三个字母的字宽本来就不一样（实测差 5 像素）。
     */
    private fun assertStackedAbove(upperId: Int, lowerId: Int) {
        onView(withId(upperId)).check { view, noViewFoundException ->
            if (view == null) throw noViewFoundException
            val lower = view.rootView.findViewById<View>(lowerId)
                ?: throw AssertionError("未找到下方输入框")
            val upperPos = IntArray(2).also { view.getLocationOnScreen(it) }
            val lowerPos = IntArray(2).also { lower.getLocationOnScreen(it) }
            assertTrue("上格应完全位于下格之上", upperPos[1] + view.height <= lowerPos[1])
            assertTrue(
                "输入框应当占满一行（实测 ${view.width} 像素，屏幕 ${view.rootView.width}）",
                view.width * 2 > view.rootView.width,
            )
        }
    }

    @Test
    fun newGroupDialog_hemisphereHintFollowsLatitude() {
        openNewGroupDialog()
        onView(withId(R.id.latInput)).perform(replaceText("31.23"), closeSoftKeyboard())
        onView(withId(R.id.hemisphereHint)).check(matches(withText(R.string.hemisphere_north)))
        onView(withId(R.id.latInput)).perform(replaceText("-31.23"), closeSoftKeyboard())
        onView(withId(R.id.hemisphereHint)).check(matches(withText(R.string.hemisphere_south)))
        onView(withId(R.id.latInput)).perform(replaceText("10"), closeSoftKeyboard())
        onView(withId(R.id.hemisphereHint)).check(matches(withText(R.string.hemisphere_tropic)))
        onView(withId(R.id.latInput)).perform(clearText(), closeSoftKeyboard())
        onView(withId(R.id.hemisphereHint)).check(matches(withText(R.string.hemisphere_unknown)))
    }

    /** 对话框一打开就把组名填好、光标停在里面且全选：想改直接敲，不想改直接下一步。 */
    @Test
    fun newGroupDialog_prefillsDefaultNameSelectedAndFocused() {
        openNewGroupDialog()
        onView(withId(R.id.nameInput)).check(matches(withText(R.string.group_name_default)))
        onView(withId(R.id.nameInput)).check(matches(isFocused()))
        onView(withId(R.id.nameInput)).check(matches(TestSupport.selectedAll()))
    }

    /** 提示词只剩「组名」：例子已经填进框里，不必在提示词里再举一次。 */
    @Test
    fun newGroupDialog_nameHintIsJustTheLabel() {
        openNewGroupDialog()
        onView(withId(R.id.nameInput)).check(matches(withHint(R.string.group_name_hint)))
    }

    /** 进焦点即全选；再点一下就把光标落到点击处（原生行为），不再整段选中。 */
    @Test
    fun newGroupDialog_tappingAFieldSelectsAllOfIt() {
        openNewGroupDialog()
        onView(withId(R.id.latInput)).perform(replaceText("31.23"), closeSoftKeyboard())
        // 先点别处把焦点挪开，再点回来：这次是「重新获得焦点」
        onView(withId(R.id.lonInput)).perform(click(), closeSoftKeyboard())
        onView(withId(R.id.latInput)).perform(click(), closeSoftKeyboard())
        onView(withId(R.id.latInput)).check(matches(TestSupport.selectedAll()))
        // 已经全选，再点一次就只落光标
        onView(withId(R.id.latInput)).perform(click(), closeSoftKeyboard())
        onView(withId(R.id.latInput)).check(matches(TestSupport.noSelection()))
    }

    /**
     * 经纬度两行的轴名与半球后缀：`LON … °E`、`LAT … °N`，后缀跟着正负走。
     *
     * 两个值都自己填：对话框打开就自动定位，模拟器上几毫秒就回来一个西经的值，
     * 直接断言初始后缀会随环境翻车。
     */
    @Test
    fun newGroupDialog_showsAxisNamesAndHemisphereSuffixes() {
        openNewGroupDialog()
        onView(withText(R.string.label_lon)).check(matches(isDisplayed()))
        onView(withText(R.string.label_lat)).check(matches(isDisplayed()))
        onView(withId(R.id.lonInput)).perform(replaceText("-74.006"), closeSoftKeyboard())
        onView(withId(R.id.latInput)).perform(replaceText("-33.87"), closeSoftKeyboard())
        onView(withId(R.id.lonSuffix)).check(matches(withText("°W")))
        onView(withId(R.id.latSuffix)).check(matches(withText("°S")))
        onView(withId(R.id.lonInput)).perform(replaceText("121.47"), closeSoftKeyboard())
        onView(withId(R.id.latInput)).perform(replaceText("31.23"), closeSoftKeyboard())
        onView(withId(R.id.lonSuffix)).check(matches(withText("°E")))
        onView(withId(R.id.latSuffix)).check(matches(withText("°N")))
    }

    /** 打开就自动定位：状态行立刻由空变成「定位中…」，或直接给出结果（命中缓存时）。 */
    @Test
    fun newGroupDialog_startsLocatingOnOpen() {
        openNewGroupDialog()
        onView(withId(R.id.gpsStatus)).check(matches(hasNonEmptyText()))
    }

    @Test
    fun newGroupDialog_validationKeepsDialogOpenAndCreatesNothing() {
        openNewGroupDialog()
        val before = repository.groups.value.size
        // 清空组名（默认名字是替用户填好的，可以删掉）：拦截，对话框保持打开
        onView(withId(R.id.nameInput)).perform(replaceText(""), closeSoftKeyboard())
        clickStartCapture()
        onView(withText(R.string.new_group_title)).check(matches(isDisplayed()))
        // 非法纬度：同样拦截
        onView(withId(R.id.nameInput)).perform(replaceText("在线测试组"), closeSoftKeyboard())
        onView(withId(R.id.latInput)).perform(replaceText("999"), closeSoftKeyboard())
        onView(withId(R.id.lonInput)).perform(replaceText("121.47"), closeSoftKeyboard())
        clickStartCapture()
        onView(withText(R.string.new_group_title)).check(matches(isDisplayed()))
        assertEquals(before, repository.groups.value.size)
    }

    @Test
    fun newGroupDialog_createsGroupWithTypedCoordinates() {
        openNewGroupDialog()
        // 采集页被桩替换，避免测试依赖相机
        intending(hasComponent(CaptureActivity::class.java.name))
            .respondWith(ActivityResult(android.app.Activity.RESULT_OK, null))
        onView(withId(R.id.nameInput)).perform(replaceText("在线测试组"), closeSoftKeyboard())
        onView(withId(R.id.latInput)).perform(replaceText("31.23"), closeSoftKeyboard())
        onView(withId(R.id.lonInput)).perform(replaceText("121.47"), closeSoftKeyboard())
        clickStartCapture()
        intended(hasComponent(CaptureActivity::class.java.name))
        val group = TestSupport.waitFor {
            repository.groups.value.firstOrNull { it.name == "在线测试组" }
        }
        // 自动定位可能晚于手输才回来，绝不能把手输的坐标盖掉
        assertEquals(31.23, group.lat, 1e-9)
        assertEquals(121.47, group.lon, 1e-9)
    }

    @Test
    fun deleteGroup_defaultKeepsPhotos() {
        val uri = TestSupport.insertTestImage(context.contentResolver, "在线测试组")
        insertedPhotos.add(uri)
        val group = seedGroupWithPhoto(uri)
        launchGroups()
        openDeleteDialog(uri)
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())
        TestSupport.waitFor { repository.get(group.id) == null }
        assertTrue(TestSupport.mediaExists(context.contentResolver, uri))
    }

    @Test
    fun deleteGroup_withPhotosChecked_deletesPhotos() {
        val uri = TestSupport.insertTestImage(context.contentResolver, "在线测试组")
        insertedPhotos.add(uri)
        val group = seedGroupWithPhoto(uri)
        launchGroups()
        openDeleteDialog(uri)
        onView(withId(R.id.deletePhotos)).inRoot(isDialog()).perform(click())
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())
        TestSupport.waitFor { repository.get(group.id) == null }
        TestSupport.waitFor { !TestSupport.mediaExists(context.contentResolver, uri) }
    }

    /** 长按卡片 → 菜单「删除」→ 确认勾选框默认为未勾选。 */
    private fun openDeleteDialog(uri: Uri) {
        val name = TestSupport.waitFor {
            repository.groups.value.firstOrNull { it.external.any { p -> p.photoUri == uri.toString() } }
        }
        onView(withText(name.name)).perform(longClick())
        onView(withText(R.string.menu_delete)).inRoot(isPlatformPopup()).perform(click())
        onView(withId(R.id.deletePhotos)).inRoot(isDialog()).check(matches(isNotChecked()))
    }

    private fun seedGroupWithPhoto(uri: Uri) = repository.createGroup(
        "在线测试组", 31.23, 121.47, 0.0, ZoneId.systemDefault().id,
    ).also { group ->
        repository.appendPoint(
            group.id,
            Region.EXTERNAL,
            PointRecord(
                az = 180.0,
                el = 10.0,
                photoUri = uri.toString(),
                takenAt = System.currentTimeMillis(),
            ),
            viaHorizon = false,
        )
    }
}