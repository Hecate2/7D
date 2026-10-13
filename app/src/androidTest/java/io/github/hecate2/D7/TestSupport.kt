package io.github.hecate2.D7

import android.content.ContentResolver
import android.graphics.Bitmap
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.withId
import io.github.hecate2.D7.data.GroupRepository
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher

/** 仪器测试公共助手：清库、轮询等待、往相册插入/查询测试图片。 */
object TestSupport {

    /** 清空全部照片组（同时落盘），保证用例之间互不影响。 */
    fun deleteAllGroups(repository: GroupRepository) {
        repository.groups.value.forEach { repository.deleteGroup(it.id) }
    }

    /**
     * 轮询等待条件成立。**条件请返回可空值**：[waitFor] 见到非 null 就返回，
     * 而 Boolean 永远非 null，直接传 `{ x == 1 }` 会第一轮就返回 false 而不等待。
     * 只判条件的场合用 [waitUntil]。
     */
    fun <T : Any> waitFor(timeoutMs: Long = 5000, condition: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            condition()?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError("等待条件超时（${timeoutMs}ms）")
    }

    /** 轮询等待布尔条件变 true；超时抛 AssertionError。 */
    fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean) {
        waitFor(timeoutMs) { condition().takeIf { it } }
    }

    /**
     * 往 Pictures/D7/<组名>/ 插入一张测试图片，返回其 MediaStore URI。
     *
     * 写的是真正能解码的 PNG（8×8 实色）：只塞几个字节也能让「条目在不在」这类断言通过，
     * 但任何要真去解码它的用例（缩略图、点开看大图）都只能拿到 null，白跑一场。
     */
    fun insertTestImage(resolver: ContentResolver, groupName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "D7_test_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/D7/$groupName",
            )
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw AssertionError("MediaStore 插入测试图片失败")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366CC.toInt()) }
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return uri
    }

    /** 查询该 URI 在相册中是否仍存在。 */
    fun mediaExists(resolver: ContentResolver, uri: Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
            ?.use { it.count > 0 } ?: false

    /** 断言输入框里的文字全部被选中。建组与编辑两个对话框的用例共用。 */
    fun selectedAll(): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("输入框内文字全选")
        }

        override fun matchesSafely(item: View): Boolean {
            val edit = item as? EditText ?: return false
            return edit.text.isNotEmpty() &&
                edit.selectionStart == 0 &&
                edit.selectionEnd == edit.text.length
        }
    }

    /** 断言输入框里只有光标、没有选区。 */
    fun noSelection(): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("输入框内只有光标、没有选中任何文字")
        }

        override fun matchesSafely(item: View): Boolean {
            val edit = item as? EditText ?: return false
            return edit.selectionStart == edit.selectionEnd
        }
    }

    /**
     * 点列第 [index] 行。行是 pointList 的直接子视图，且每次刷新都会整表重建，
     * 所以只能按下标定位，不能留住 View 引用。
     */
    fun rowAt(index: Int): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("点列的第 $index 行")
        }

        override fun matchesSafely(item: View): Boolean {
            val parent = item.parent as? ViewGroup ?: return false
            return parent.id == R.id.pointList && parent.indexOfChild(item) == index
        }
    }

    /** 第 [row] 行内的某个控件。 */
    fun inRow(row: Int, viewId: Int): Matcher<View> =
        allOf(withId(viewId), isDescendantOfA(rowAt(row)))
}
