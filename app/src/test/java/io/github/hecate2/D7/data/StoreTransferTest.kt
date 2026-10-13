package io.github.hecate2.D7.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 存档导出与导入的纯 JVM 单测（秒级，不需要设备）。
 *
 * 这一层的输入来自应用之外，是本工程唯一一处「别人给的字节直接进模型」的入口，
 * 所以用例的重点不在happy path，而在**那些必须被整份拒绝的东西**：缺键、非有限数、
 * 时区解析不了、经纬度超出范围。放进去的代价不是当场崩，是等到算日照时才炸在别处。
 */
class StoreTransferTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun group(
        id: String = "g1",
        name: String = "阳台",
        lat: Double = 31.2304,
        lon: Double = 121.4737,
        altitude: Double = 0.0,
        zoneId: String = "Asia/Shanghai",
        createdAt: Long = 1L,
        updatedAt: Long = 2L,
        external: List<PointRecord> = listOf(PointRecord(az = 182.5, el = 24.0, takenAt = 3L)),
        ceiling: List<PointRecord> = emptyList(),
    ) = GroupRecord(
        id = id,
        name = name,
        lat = lat,
        lon = lon,
        altitude = altitude,
        zoneId = zoneId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        external = external,
        ceiling = ceiling,
    )

    /** 改写真实文本里的某个片段，用来构造一份「像存档但某处不对」的文件。 */
    private fun edited(groups: List<GroupRecord>, from: String, to: String): String {
        val text = encodeStore(groups)
        assertTrue("构造用例失败：文本里找不到 $from", text.contains(from))
        return text.replace(from, to)
    }

    /** 依次改写多处片段。 */
    private fun editedAll(groups: List<GroupRecord>, vararg pairs: Pair<String, String>): String =
        pairs.fold(encodeStore(groups)) { text, (from, to) ->
            assertTrue("构造用例失败：文本里找不到 $from", text.contains(from))
            text.replace(from, to)
        }

    /**
     * 往返一致，只有照片引用例外：导入这条路一律不把 `photoUri` 带进来（理由见下一条用例）。
     * 所以期望值是把照片清掉之后的那一份，而不是原样那一份。
     */
    @Test
    fun roundTripKeepsEveryFieldExceptPhotoReferences() {
        val groups = listOf(
            group(),
            group(
                id = "g2",
                name = "主卧 ☀️",
                lat = -33.8688,
                lon = 151.2093,
                altitude = 12.5,
                zoneId = "Australia/Sydney",
                external = listOf(
                    PointRecord(az = 0.0, el = 0.0, gapAfter = true, photoUri = null, takenAt = 7L),
                    PointRecord(az = 359.9, el = 90.0, photoUri = "content://x/1", takenAt = 8L),
                ),
                ceiling = listOf(PointRecord(az = 90.0, el = 45.0, takenAt = 9L)),
            ),
        )
        val withoutPhotos = groups.map { group ->
            group.copy(
                external = group.external.map { it.copy(photoUri = null) },
                ceiling = group.ceiling.map { it.copy(photoUri = null) },
            )
        }
        assertEquals(Store(groups = withoutPhotos), decodeStore(encodeStore(groups)))
    }

    /**
     * 导入进来的照片引用一个都不留。
     *
     * 这不是洁癖：`photoUri` 是本机的东西（MediaStore 的行号、私有目录的路径），而它指向的字节
     * 不在文件里。跨设备导入时那个行号大体上会命中另一台设备上另一张照片，而删点与删组会照着
     * 它去删，把与本应用无关的照片从相册里抹掉；`file://` 那种更直接，删除实现就是删那个路径。
     * 四种写法一起验：清空是按「一律丢掉」做的，不是按方案挑。
     */
    @Test
    fun importedPointsCarryNoPhotoReference() {
        val uris = listOf(
            "content://media/external/images/media/4242",
            "file:///storage/emulated/0/Pictures/D7/阳台/D7_1.jpg",
            "https://example.com/a.jpg",
            "乱七八糟",
        )
        val groups = uris.mapIndexed { index, uri ->
            group(
                id = "g$index",
                name = "组$index",
                external = listOf(PointRecord(az = 1.0, el = 2.0, photoUri = uri, takenAt = 1L)),
                ceiling = listOf(PointRecord(az = 3.0, el = 4.0, photoUri = uri, takenAt = 2L)),
            )
        }
        val decoded = decodeStore(encodeStore(groups))
        assertEquals(uris.size, decoded?.groups?.size)
        assertTrue(decoded!!.groups.flatMap { it.external + it.ceiling }.all { it.photoUri == null })
        // 清掉的只是照片引用，角度与拍摄时间照旧
        assertEquals(1.0, decoded.groups[0].external.single().az, 1e-9)
        assertEquals(4.0, decoded.groups[0].ceiling.single().el, 1e-9)
        assertEquals(2L, decoded.groups[0].ceiling.single().takenAt)
    }

    /**
     * 导出的文本必须与仓库落盘的是同一种写法。这条不靠肉眼比对，而是把导出物写进
     * 仓库真正读的那个文件，看它认不认——两边共用 [StoreJson] 才成立，一旦哪天谁单改了一处，
     * 「导出的文件能被本机导入」就从保证退化成巧合。
     */
    @Test
    fun exportedTextIsWhatTheRepositoryItselfWrites() {
        val file = File(temp.root, "groups.json")
        val repository = GroupRepository(file)
        repository.createGroup("阳台", 31.2304, 121.4737, 0.0, "Asia/Shanghai")

        // 写队列是异步的，等磁盘追上内存态
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && !file.exists()) Thread.sleep(10)
        assertTrue("仓库没把文件写出来", file.exists())

        val fromDisk = decodeStore(file.readText())
        assertEquals(1, fromDisk?.groups?.size)
        assertEquals("阳台", fromDisk?.groups?.single()?.name)

        // 反过来：导出的那份写回仓库，读出来还是一样
        val reimported = File(temp.root, "reimported.json")
        reimported.writeText(encodeStore(fromDisk!!.groups))
        assertEquals("阳台", GroupRepository(reimported).groups.value.single().name)
    }

    @Test
    fun emptyStoreIsAccepted() {
        assertEquals(emptyList<GroupRecord>(), decodeStore(encodeStore(emptyList()))?.groups)
    }

    /**
     * 导入把内存态换掉之后，[GroupRepository.awaitWrites] 返回时字节必须已经在盘上。
     *
     * 这条盯的是「成功提示早于落盘」这条路：落盘本来排在异步写队列上，界面提示若不等它，
     * 用户在提示弹出的瞬间划掉应用，进程一没，这次导入就凭空消失了——下次启动读到的是
     * 导入之前的旧数据，而界面上明明写着「已导入」。用一个全新的仓库读同一个文件来验证，
     * 而不是去问内存态（内存态在 replaceAll 的那一刻就已经是新数据了，问它等于没问）。
     */
    @Test
    fun replaceAllIsOnDiskOnceAwaitWritesReturns() {
        val file = File(temp.root, "groups.json")
        val repository = GroupRepository(file)
        repository.createGroup("旧的", 31.0, 121.0, 0.0, "Asia/Shanghai")

        repository.replaceAll(listOf(group(id = "imported", name = "导入的")))
        repository.awaitWrites()

        assertEquals(listOf("导入的"), GroupRepository(file).groups.value.map { it.name })
    }

    @Test
    fun missingRequiredKeyIsRejected() {
        assertNull(decodeStore(edited(listOf(group()), "\"id\": \"g1\",", "")))
        assertNull(decodeStore(edited(listOf(group()), "\"zoneId\": \"Asia/Shanghai\",", "")))
    }

    @Test
    fun nonFiniteNumbersAreRejected() {
        // 这一层是序列化器在挡（默认不允许 NaN / Infinity），用例锁的是 decodeStore 的对外契约：
        // 非有限数不得穿过这道边界。挡在哪一层不重要，穿透才要命——它会一路走到落盘才炸。
        for (bad in listOf("NaN", "Infinity", "-Infinity", "1e999")) {
            assertNull("$bad 应当被拒绝", decodeStore(edited(listOf(group()), "182.5", bad)))
        }
    }

    @Test
    fun unparseableZoneIsRejected() {
        // 键在、值也是字符串，但拿去做 ZoneId.of 会抛。这正是导入这条路上最像坑的一种坏数据：
        // 格式完全合法，炸点却在算日照那一步。
        assertNull(decodeStore(edited(listOf(group()), "Asia/Shanghai", "Shanghai/Asia")))
        assertNull(decodeStore(edited(listOf(group()), "Asia/Shanghai", "")))
    }

    @Test
    fun outOfRangeCoordinatesAreRejected() {
        assertNull(decodeStore(edited(listOf(group()), "31.2304", "90.1")))
        assertNull(decodeStore(edited(listOf(group()), "121.4737", "-180.1")))
        assertNull(decodeStore(edited(listOf(group(altitude = 12.5)), "12.5", "1e6")))
        // 边界值本身要放过，别把合法的极点误判成坏数据
        assertEquals(1, decodeStore(edited(listOf(group()), "31.2304", "90.0"))?.groups?.size)
        assertEquals(1, decodeStore(edited(listOf(group()), "121.4737", "-180.0"))?.groups?.size)
    }

    @Test
    fun tooManyGroupsAreRejected() {
        val many = List(MAX_GROUPS + 1) { index -> group(id = "g$index", name = "组$index") }
        assertNull(decodeStore(encodeStore(many)))
        // 刚好卡在上限的规模要放过：上限是拦畸形文件的，不是产品限制
        assertEquals(MAX_GROUPS, decodeStore(encodeStore(many.dropLast(1)))?.groups?.size)
    }

    @Test
    fun tooManyPointsAreRejected() {
        val points = List(MAX_POINTS + 1) { PointRecord(az = 1.0, el = 1.0, takenAt = 1L) }
        assertNull(decodeStore(encodeStore(listOf(group(external = points)))))
    }

    @Test
    fun blankIdOrNameIsRejected() {
        assertNull(decodeStore(edited(listOf(group()), "\"g1\"", "\"\"")))
        assertNull(decodeStore(edited(listOf(group()), "\"阳台\"", "\"\"")))
    }

    @Test
    fun duplicateIdsAreRejected() {
        // 两份存档首尾相接是常见的错用方式，撞了 id 之后 get(id) 只认第一个、
        // deleteGroup(id) 又会一起删掉，行为没法向用户解释，索性拒绝
        assertNull(decodeStore(encodeStore(listOf(group(), group(name = "另一个")))))
    }

    @Test
    fun anglesAreNormalizedOnImportInsteadOfRejected() {
        val text = editedAll(
            listOf(group()),
            "182.5" to "-10.0",
            "\"el\": 24.0" to "\"el\": 120.0",
        )
        val point = decodeStore(text)?.groups?.single()?.external?.single()
        assertEquals(350.0, point!!.az, 1e-9)
        assertEquals(90.0, point.el, 1e-9)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val text = encodeStore(listOf(group())).replaceFirst("{", """{"futureField":123,""")
        assertEquals(1, decodeStore(text)?.groups?.size)
    }

    @Test
    fun anythingThatIsNotAnObjectIsRejected() {
        for (bad in listOf("", "  ", "null", "[]", "not json at all", "{\"groups\":{}}")) {
            assertNull("「$bad」应当被拒绝", decodeStore(bad))
        }
    }

    @Test
    fun leadingBomIsStripped() {
        // 记事本之类的编辑器会在 UTF-8 开头补一个 BOM，序列化器不认它
        val text = "\uFEFF" + encodeStore(listOf(group()))
        val bytes = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        assertEquals(1, decodeStore(readStoreText(bytes)!!)?.groups?.size)
    }

    @Test
    fun oversizedStreamIsRejectedBeforeParsing() {
        val big = ByteArray(STORE_MAX_BYTES + 1) { 'a'.code.toByte() }
        assertNull(readStoreText(ByteArrayInputStream(big)))
    }

    @Test
    fun readStoreTextKeepsEveryByteUnderTheLimit() {
        val text = encodeStore(listOf(group(name = "阳台 ☀️ \"引号\" \\反斜杠")))
        val bytes = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        val read = readStoreText(bytes)
        assertEquals(text, read)
        assertEquals("阳台 ☀️ \"引号\" \\反斜杠", decodeStore(read!!)?.groups?.single()?.name)
    }
}
