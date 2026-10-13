package io.github.hecate2.D7

import android.app.Instrumentation.ActivityResult
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents.getIntents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.Intents.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtraWithKey
import androidx.test.espresso.intent.rule.IntentsRule
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import io.github.hecate2.D7.data.GroupRecord
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.decodeStore
import io.github.hecate2.D7.data.encodeStore
import io.github.hecate2.D7.data.readStoreText
import io.github.hecate2.D7.ui.groups.GroupsActivity
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.ArrayList

/**
 * 存档导出/导入的端到端测试。
 *
 * 系统文件选择器不可能真去点，用 espresso-intents 把它拦下来，返回一个我们自己造的 URI，
 * 于是整条链路（发 Intent → 拿 URI → 写/读 → 落下或替换）都被真实执行了一遍，
 * 只有「用户点了哪个文件」这一步是假的。
 *
 * 那个 URI 走 MediaStore 的下载集合，不用 `file://`：`ContentResolver` 只允许 file 方案**读**，
 * 写会直接抛 FileNotFoundException，所以最省事的 `Uri.fromFile` 在这里根本走不通。放下载集合
 * 而不是相册，是因为 `.json` 进了 Images 会被媒体库当成一张坏图片。
 */
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
@RunWith(AndroidJUnit4::class)
@LargeTest
class GroupsStoreTransferTest {

    @get:Rule
    val intentsRule = IntentsRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver: ContentResolver get() = context.contentResolver
    private val repository get() = GroupRepository.get(context)
    private val created = ArrayList<Uri>()
    private var scene: ActivityScenario<GroupsActivity>? = null

    @Before
    fun setUp() {
        TestSupport.deleteAllGroups(repository)
    }

    @After
    fun tearDown() {
        scene?.close()
        created.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        created.clear()
        TestSupport.deleteAllGroups(repository)
    }

    private fun launchGroups() {
        scene = ActivityScenario.launch(GroupsActivity::class.java)
    }

    /** 在下载集合里造一个可读写的测试文件，返回它的 URI。 */
    private fun newTarget(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/D7Test")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw AssertionError("MediaStore 插入测试文件失败")
        created += uri
        return uri
    }

    private fun write(uri: Uri, text: String) {
        val sink = resolver.openOutputStream(uri) ?: throw AssertionError("写不进测试文件")
        sink.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun read(uri: Uri): String? = resolver.openInputStream(uri)?.use { readStoreText(it) }

    private fun record(name: String): GroupRecord = GroupRecord(
        id = "imported-$name",
        name = name,
        lat = 31.2304,
        lon = 121.4737,
        altitude = 0.0,
        zoneId = "Asia/Shanghai",
        createdAt = 100L,
        updatedAt = 200L,
    )

    /** 把系统选择器拦下来，让它返回我们指定的 URI 并报「用户选中了」。 */
    private fun stubPicker(action: String, target: Uri) {
        intending(hasAction(action))
            .respondWith(ActivityResult(android.app.Activity.RESULT_OK, Intent().setData(target)))
    }

    @Test
    fun exportWritesEveryGroupToTheChosenFile() {
        repository.createGroup("阳台", 31.2304, 121.4737, 0.0, "Asia/Shanghai")
        repository.createGroup("主卧", 31.2305, 121.4738, 0.0, "Asia/Shanghai")
        launchGroups()

        val target = newTarget("d7-export.json")
        stubPicker(Intent.ACTION_CREATE_DOCUMENT, target)
        onView(withId(R.id.exportStoreButton)).perform(click())

        // 建议文件名带着日期，用户在选择器里一眼能看出这是哪一天导的
        val stamp = LocalDate.now().toString().replace("-", "")
        intended(
            allOf(
                hasAction(Intent.ACTION_CREATE_DOCUMENT),
                hasExtraWithKey(Intent.EXTRA_TITLE),
            ),
        )
        val suggested = getIntents()
            .first { it.action == Intent.ACTION_CREATE_DOCUMENT }
            .getStringExtra(Intent.EXTRA_TITLE)
        assertEquals(context.getString(R.string.export_store_file_name, stamp), suggested)

        val text = TestSupport.waitFor { read(target)?.takeIf { it.contains("阳台") } }
        assertEquals(
            listOf("阳台", "主卧"),
            decodeStore(text)?.groups?.map { it.name },
        )
    }

    /**
     * 空库点导出：连选择器都不该弹。用 `times(0)` 断言这个 Intent 一次都没发出，
     * 比「等一会儿看文件是不是空的」可靠——后者要靠睡眠去赌一个否定结论。
     */
    @Test
    fun exportWithNoGroupsNeverOpensTheFilePicker() {
        launchGroups()
        val target = newTarget("d7-export-empty.json")
        stubPicker(Intent.ACTION_CREATE_DOCUMENT, target)

        onView(withId(R.id.exportStoreButton)).perform(click())

        intended(hasAction(Intent.ACTION_CREATE_DOCUMENT), times(0))
    }

    @Test
    fun importReplacesInsteadOfMerging() {
        repository.createGroup("旧的甲", 31.0, 121.0, 0.0, "Asia/Shanghai")
        repository.createGroup("旧的乙", 31.1, 121.1, 0.0, "Asia/Shanghai")
        repository.createGroup("旧的丙", 31.2, 121.2, 0.0, "Asia/Shanghai")
        launchGroups()

        val source = newTarget("d7-import.json")
        write(source, encodeStore(listOf(record("导入的"))))
        stubPicker(Intent.ACTION_OPEN_DOCUMENT, source)

        onView(withId(R.id.importStoreButton)).perform(click())

        // 不可撤销的一步，确认框必须把「几组换几组」两边都写出来
        onView(withText(context.getString(R.string.import_store_message, 1, 3)))
            .inRoot(isDialog())
            .check(matches(isDisplayed()))
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())

        assertEquals(listOf("导入的"), repository.groups.value.map { it.name })
    }

    @Test
    fun cancellingTheImportKeepsTheCurrentData() {
        repository.createGroup("旧的甲", 31.0, 121.0, 0.0, "Asia/Shanghai")
        repository.createGroup("旧的乙", 31.1, 121.1, 0.0, "Asia/Shanghai")
        launchGroups()

        val source = newTarget("d7-import-cancel.json")
        write(source, encodeStore(listOf(record("导入的"))))
        stubPicker(Intent.ACTION_OPEN_DOCUMENT, source)

        onView(withId(R.id.importStoreButton)).perform(click())
        onView(withText(R.string.cancel)).inRoot(isDialog()).perform(click())

        assertEquals(
            listOf("旧的甲", "旧的乙"),
            repository.groups.value.map { it.name },
        )
    }

    /**
     * 坏文件：流程要真的跑过（选择器确实弹了），但数据一个字节都不许动，
     * 也不该出现一个「要不要覆盖」的确认框去问用户。
     */
    @Test
    fun anInvalidFileIsRejectedAndTheDataStays() {
        repository.createGroup("旧的甲", 31.0, 121.0, 0.0, "Asia/Shanghai")
        repository.createGroup("旧的乙", 31.1, 121.1, 0.0, "Asia/Shanghai")
        launchGroups()

        val source = newTarget("d7-import-bad.json")
        // 唯一的毛病是时区：其余字段齐全（createdAt/updatedAt 这类没有默认值，少一个就变成
        // 「因为缺键被拒」，验不到时区那一条）。这正是最像坑的坏数据——格式完全合法，
        // 炸点却在几小时后算日照的那一步。
        write(
            source,
            """{"groups":[{"id":"x","name":"假的","lat":1.0,"lon":2.0,""" +
                """"zoneId":"没有这个时区","createdAt":1,"updatedAt":1}]}""",
        )
        stubPicker(Intent.ACTION_OPEN_DOCUMENT, source)

        onView(withId(R.id.importStoreButton)).perform(click())

        intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))
        assertEquals(listOf("旧的甲", "旧的乙"), repository.groups.value.map { it.name })
        // 没有对话框时 Espresso 搜的是 Activity 那个有焦点的窗口，所以这条断言确实在查
        // 「确认框在不在」，而不是含糊地放过
        onView(withText(R.string.import_store_title)).check(doesNotExist())
    }

    /**
     * 读不出字节的文件：连确认框都不该出现，数据一个字节不许动。
     *
     * 这条盯的不是「读失败有没有提示」，而是**读失败绝不会被当成空存档**。把 null 的读结果
     * 一路顺着走成 `emptyList()` 是个很自然的写法（`text.orEmpty()` 之类），而那样做的话，
     * 用户随手选中一个打不开的文件就等于把全部照片组删光。
     */
    @Test
    fun aFileThatCannotBeReadNeverClearsTheData() {
        repository.createGroup("旧的甲", 31.0, 121.0, 0.0, "Asia/Shanghai")
        repository.createGroup("旧的乙", 31.1, 121.1, 0.0, "Asia/Shanghai")
        launchGroups()

        // 指向一个不存在的 provider：openInputStream 抛异常，与「文件被删掉」「权限被收回」
        // 走的是同一条路径
        val ghost = Uri.parse("content://io.github.hecate2.D7.nonexistent/ghost.json")
        stubPicker(Intent.ACTION_OPEN_DOCUMENT, ghost)

        onView(withId(R.id.importStoreButton)).perform(click())
        intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))

        onView(withText(R.string.import_store_title)).check(doesNotExist())
        assertEquals(listOf("旧的甲", "旧的乙"), repository.groups.value.map { it.name })
    }

    /**
     * 空存档是**合法**的输入，但它意味着「把现在这些全删掉」，所以确认框必须把 0 这个数
     * 明明白白写出来。这条把现状钉住：一旦哪天有人图省事在空存档上直接返回，或者把确认
     * 框对空存档跳过去，这里就会红，而不是等用户在某次误点之后才发现数据没了。
     */
    @Test
    fun anEmptyStoreIsDisclosedAsAZeroForNReplacement() {
        repository.createGroup("旧的甲", 31.0, 121.0, 0.0, "Asia/Shanghai")
        repository.createGroup("旧的乙", 31.1, 121.1, 0.0, "Asia/Shanghai")
        launchGroups()

        val source = newTarget("d7-import-nothing.json")
        write(source, encodeStore(emptyList()))
        stubPicker(Intent.ACTION_OPEN_DOCUMENT, source)

        onView(withId(R.id.importStoreButton)).perform(click())

        // 关键词是那个 0：用户看到「0 个换掉 2 个」就该收手
        onView(withText(context.getString(R.string.import_store_message, 0, 2)))
            .inRoot(isDialog())
            .check(matches(isDisplayed()))
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())
        TestSupport.waitUntil(5000) { repository.groups.value.isEmpty() }
    }

    /**
     * 走完整条链路：导出成一份文件 → 把本机数据清空 → 从那份文件读回来。
     *
     * 前面几条都是拿 [encodeStore] 现造一份存档塞给选择器，验的是「导入」这一半；
     * 这条的两头都是界面上真实发生的事——导出键写出的字节、导入键读回的字节同一个文件，
     * 覆盖不到的是只有用户点哪个文件那一步。
     */
    @Test
    fun exportThenWipeThenImportBringsEveryGroupBack() {
        repository.createGroup("阳台", 31.2304, 121.4737, 0.0, "Asia/Shanghai")
        repository.createGroup("主卧", 31.2305, 121.4738, 0.0, "Asia/Shanghai")
        launchGroups()

        val target = newTarget("d7-round-trip.json")
        stubPicker(Intent.ACTION_CREATE_DOCUMENT, target)
        onView(withId(R.id.exportStoreButton)).perform(click())
        TestSupport.waitFor { read(target)?.takeIf { it.contains("阳台") } }

        TestSupport.deleteAllGroups(repository)
        assertEquals(emptyList<String>(), repository.groups.value.map { it.name })

        stubPicker(Intent.ACTION_OPEN_DOCUMENT, target)
        onView(withId(R.id.importStoreButton)).perform(click())
        onView(withText(context.getString(R.string.import_store_message, 2, 0)))
            .inRoot(isDialog())
            .check(matches(isDisplayed()))
        onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click())

        TestSupport.waitUntil(5000) { repository.groups.value.size == 2 }
        assertEquals(listOf("阳台", "主卧"), repository.groups.value.map { it.name })
    }
}
