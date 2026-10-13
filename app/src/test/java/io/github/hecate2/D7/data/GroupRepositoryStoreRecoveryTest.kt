package io.github.hecate2.D7.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 存档读不动时的兜底：把坏文件挪到一旁留档，而不是让一份空库把它覆盖掉（纯 JVM，秒级）。
 *
 * 原先 `readStore` 读失败直接返回空 `Store()`，紧接着的第一次修改就把这份空库整份写回同一路径：
 * 内存里一切正常，用户的照片组却没了，盘上也看不出发生过什么。这里钉住新语义三条：
 * 坏文件逐字节留档、主路径让开、之后的写入照常进行。
 */
class GroupRepositoryStoreRecoveryTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 惰性取：字段初始化早于 `@Rule` 建出临时目录。 */
    private val store: File get() = File(temp.root, "groups.json")

    /** 一份正常存档，字段与序列化器实际写出的东西一致（等于默认值的键都省略）。 */
    private val good = """{"groups":[{"id":"g1","name":"阳台","lat":31.23,"lon":121.47,""" +
        """"zoneId":"Asia/Shanghai","createdAt":1,"updatedAt":1,""" +
        """"external":[{"az":182.5,"el":24.0,"takenAt":1}],"ceiling":[]}]}"""

    private fun backups(): List<File> =
        temp.root.listFiles { f -> f.name.endsWith(".bad") }?.sortedBy { it.name }.orEmpty()

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    /** 四种读不动的文件：都要留档，留档内容与原文逐字节一致，主路径让开且能接着写新数据。 */
    @Test
    fun unreadableStoreIsBackedUpVerbatim() {
        val cases = listOf(
            "缺必填键" to good.replace(""""id":"g1",""", ""),
            "数字写成非数字" to good.replace("31.23", "\"abc\""),
            "JSON 被截断" to good.substring(0, 60),
            "含 NaN（编码器不认）" to good.replace("182.5", "NaN"),
        )
        for ((label, text) in cases) {
            store.writeText(text)
            val repository = GroupRepository(store)
            assertEquals("$label：读不动就该当空库", 0, repository.groups.value.size)
            val saved = backups()
            assertEquals("$label：应恰好留档一份", 1, saved.size)
            assertEquals("$label：留档必须与原文一致", text, saved[0].readText())
            assertFalse("$label：主路径应让开，好让新数据落得下来", store.exists())

            repository.createGroup("新组", 31.0, 121.0, 0.0, "Asia/Shanghai")
            assertTrue(
                "$label：留档之后主路径该能正常写新数据",
                waitUntil { store.exists() && store.readText().contains("新组") },
            )

            // 清场，让下一种情况从零开始
            backups().forEach { it.delete() }
            store.delete()
        }
    }

    /** 首次安装（没有存档）不该凭空留档，第一次修改要能建出文件。 */
    @Test
    fun missingStoreIsNotBackedUp() {
        val repository = GroupRepository(store)
        assertEquals(0, repository.groups.value.size)
        assertEquals(0, backups().size)

        repository.createGroup("第一组", 31.0, 121.0, 0.0, "Asia/Shanghai")
        assertTrue(waitUntil { store.exists() && store.readText().contains("第一组") })
        assertEquals("没有旧文件就无从留档", 0, backups().size)
    }

    /** 正常存档不许被当成坏的：数据照读，原文件原封不动。 */
    @Test
    fun readableStoreIsLeftAlone() {
        store.writeText(good)
        val repository = GroupRepository(store)
        val group = repository.groups.value.single()
        assertEquals("阳台", group.name)
        assertEquals(182.5, group.external.single().az, 1e-9)
        assertEquals(0, backups().size)
        assertEquals("原文件不该被动过", good, store.readText())
    }

    /** 连着两次读失败要留下两份坏档：新的不许把旧的那份盖掉。 */
    @Test
    fun secondFailureDoesNotOverwriteTheFirstBackup() {
        store.writeText(good.replace("182.5", "NaN"))
        GroupRepository(store)
        assertEquals(1, backups().size)

        // 名字带毫秒时间戳，隔开一毫秒以上才验得出「不覆盖」
        Thread.sleep(20)
        store.writeText(good.substring(0, 60))
        GroupRepository(store)

        val both = backups()
        assertEquals("两份坏档都要在", 2, both.size)
        assertTrue("第一份坏档被盖掉了", both.any { it.readText().contains("NaN") })
    }
}
