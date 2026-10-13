package io.github.hecate2.D7.data

import android.content.Context
import io.github.hecate2.D7.core.Angles
import io.github.hecate2.D7.util.sanitizeGroupName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors

/** 拍摄分区：外部建筑区 / 自家天花板区。 */
enum class Region { EXTERNAL, CEILING }

/** 一个拍摄点。gapAfter 表示该点与拍摄序下一个点之间为经地平线推断段。 */
@Serializable
data class PointRecord(
    val az: Double,
    val el: Double,
    val gapAfter: Boolean = false,
    val photoUri: String? = null,
    val takenAt: Long = 0L,
)

/** 一组照片：一个站位（窗、阳台）的全部测量。 */
@Serializable
data class GroupRecord(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val altitude: Double = 0.0,
    val zoneId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val external: List<PointRecord> = emptyList(),
    val ceiling: List<PointRecord> = emptyList(),
) {
    fun regionList(region: Region): List<PointRecord> =
        if (region == Region.EXTERNAL) external else ceiling
}

/** 落盘结构。 */
@Serializable
data class Store(
    val version: Int = 1,
    val groups: List<GroupRecord> = emptyList(),
)

/**
 * 存档的 JSON 配置，落盘与导入导出共用一份。
 *
 * `ignoreUnknownKeys` 让旧版本读得懂新版本写的文件（多出来的键直接跳过）；
 * `encodeDefaults` 保持默认的 false，等于默认值的字段不写出，所以文件里看不到
 * `version`、`altitude`、`gapAfter` 这些缺省项。导入导出必须与落盘逐字节同源，
 * 否则「导出的文件能被本机导入」就成了巧合而不是保证。
 */
internal val StoreJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

/**
 * 照片组仓库：全部组数据以 JSON 存放于 filesDir/groups.json。
 *
 * 落盘在单线程写队列上异步执行（先写临时文件再原子改名，文件系统不支持原子改名时回退普通改名），
 * 不阻塞调用线程；组记录按值快照处理，任何修改都生成新记录并经 [groups] 重新发射，界面据此刷新。
 * 存档读不动时先把原文件挪成 `.bad` 留档再当空库用，绝不让一份空库把用户的数据覆盖掉（见 [readStore]）。
 *
 * 构造函数是 `internal`：除了 [get] 走 Context/filesDir 这条路，纯 JVM 单测也可直接传临时文件，
 * 从而把「读改写不串行化就丢点」这条回归护栏放进 `./gradlew test`，不必每次起设备。
 */
class GroupRepository internal constructor(private val file: File) {

    /** 单线程写队列：全部落盘异步执行，界面线程不被 IO 阻塞。 */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "D7-store-writer").apply { isDaemon = true }
    }

    private val _groups = MutableStateFlow<List<GroupRecord>>(emptyList())
    val groups: StateFlow<List<GroupRecord>> = _groups.asStateFlow()

    /** 守卫所有「读-改-写」整段：见 [update] 与 [publish] 的说明。 */
    private val stateLock = Any()

    init {
        _groups.value = readStore().groups
    }

    fun get(id: String): GroupRecord? = _groups.value.firstOrNull { it.id == id }

    fun createGroup(
        name: String,
        lat: Double,
        lon: Double,
        altitude: Double,
        zoneId: String,
    ): GroupRecord = synchronized(stateLock) {
        val now = System.currentTimeMillis()
        val group = GroupRecord(
            id = UUID.randomUUID().toString(),
            name = name,
            lat = lat,
            lon = lon,
            altitude = altitude,
            zoneId = zoneId,
            createdAt = now,
            updatedAt = now,
        )
        publish(_groups.value + group)
        group
    }

    /**
     * 改组名、经纬度与时区。**三样一起改**而不是各写一个 setter：界面上它们本来就是同一个
     * 对话框，拆开容易出现「改了名以为坐标也存了」。合法性由调用方先校验，这里不再设防，
     * 免得两处各写一遍规则再慢慢漂开。
     */
    fun editGroup(id: String, name: String, lat: Double, lon: Double, zoneId: String) =
        update(id) { it.copy(name = name, lat = lat, lon = lon, zoneId = zoneId) }

    fun deleteGroup(id: String) = synchronized(stateLock) {
        publish(_groups.value.filterNot { it.id == id })
    }

    /**
     * 用一份外部存档整份替换当前数据，导入用。
     *
     * **是替换不是合并**：合并要处理 id 撞车、同组两份点列谁为准这类规则，而那些规则没有
     * 一个能算「显然正确」，反倒会让用户在两次导入之后拿到一份自己也说不清的混合数据。
     * 对话里会把将被覆盖的组数讲明，用户确认过才走到这里。
     *
     * 落盘仍然是异步的，要等它落地见 [awaitWrites]。
     */
    fun replaceAll(groups: List<GroupRecord>) = synchronized(stateLock) { publish(groups) }

    /**
     * 等写队列把当前已排入的任务全部跑完，然后返回。
     *
     * 只给导入用：那是唯一一个「用户当面点过确认、而且一步就把旧数据换掉」的动作，
     * 成功提示必须晚于字节落盘。否则用户在提示弹出的瞬间划掉应用，进程一没，内存里的
     * 那份新数据就跟着没了，下次启动看到的是导入之前的旧数据，界面上却写着「已导入」。
     *
     * 队列是单线程 FIFO，所以排一个空任务再等它跑完，就等于等前面所有写都做完。
     * 写失败（磁盘满、目录只读）时 [persist] 的异常在队内被吞掉，这里不区分成败——那种
     * 情况下盘上留着的仍是导入之前的旧数据，是安全的落点，不需要为此让调用方多一个分支。
     */
    fun awaitWrites() {
        writer.submit { }.get()
    }

    /**
     * 该组照片在相册中的文件夹名：取清洗后的组名；同名组只有最早创建者（按 createdAt、id 排序）
     * 用裸名，后来者追加短 id 后缀，避免不同组在相册里混放。
     */
    fun photoFolderFor(group: GroupRecord): String {
        val base = sanitizeGroupName(group.name)
        val sameName = _groups.value.filter { it.name == group.name }
        if (sameName.size <= 1) return base
        val earliest = sameName.minWithOrNull(compareBy<GroupRecord>({ it.createdAt }, { it.id }))
        return if (earliest?.id == group.id) base else "$base-${group.id.takeLast(4)}"
    }

    fun deletePoint(groupId: String, region: Region, index: Int) = update(groupId) { group ->
        val list = group.regionList(region).toMutableList()
        if (index !in list.indices) return@update group
        list.removeAt(index)
        // 删除后前后两点改为直接连线，避免悬空的经地平线段
        if (index - 1 in list.indices) {
            list[index - 1] = list[index - 1].copy(gapAfter = false)
        }
        group.withRegion(region, list)
    }

    /**
     * 追加一个拍摄点，并同时决定它与上一点之间的连线模式：
     * viaHorizon 为 true（长按快门）则上一点的 gapAfter 置 true，否则清为直接连线。
     */
    fun appendPoint(
        groupId: String,
        region: Region,
        point: PointRecord,
        viaHorizon: Boolean,
    ) = update(groupId) { group ->
        val list = group.regionList(region).toMutableList()
        if (list.isNotEmpty()) {
            list[list.size - 1] = list[list.size - 1].copy(gapAfter = viaHorizon)
        }
        list.add(point.copy(gapAfter = false))
        group.withRegion(region, list)
    }

    /** 修改第 startIndex 个点与下一点之间的连线模式（经地平线或直接连线）。 */
    fun setSegmentViaHorizon(groupId: String, region: Region, startIndex: Int, viaHorizon: Boolean) =
        update(groupId) { group ->
            val list = group.regionList(region).toMutableList()
            if (startIndex !in list.indices) return@update group
            list[startIndex] = list[startIndex].copy(gapAfter = viaHorizon)
            group.withRegion(region, list)
        }

    /** 强行改写某点的角度（照片与拍摄时间保留）；方位规范化到 [0, 360)，仰角限制到 [0, 90]。 */
    fun updatePointAngles(groupId: String, region: Region, index: Int, az: Double, el: Double) =
        update(groupId) { group ->
            val list = group.regionList(region).toMutableList()
            if (index !in list.indices) return@update group
            list[index] = list[index].copy(
                az = Angles.normalize360(az),
                el = el.coerceIn(0.0, 90.0),
            )
            group.withRegion(region, list)
        }

    /** 整区替换点列（批量编辑用，是否保留照片由调用方决定）。 */
    fun setRegionPoints(groupId: String, region: Region, points: List<PointRecord>) =
        update(groupId) { group -> group.withRegion(region, points) }

    private fun update(id: String, transform: (GroupRecord) -> GroupRecord) {
        synchronized(stateLock) {
            val current = _groups.value
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return
            val updated = transform(current[index]).copy(updatedAt = System.currentTimeMillis())
            publish(current.toMutableList().also { it[index] = updated })
        }
    }

    /**
     * 发射新状态并排入落盘。**只允许在持有 [stateLock] 时调用**：
     * 所有修改都是「读当前列表 → 改一处 → 写回」，不串行化就会丢修改
     * （拍照协程与界面/后台线程同时改同一组时实测丢过 19/400 个点）。
     */
    private fun publish(groups: List<GroupRecord>) {
        _groups.value = groups
        val store = Store(groups = groups)
        writer.execute {
            try {
                persist(store)
            } catch (_: Exception) {
                // 极端失败（如磁盘满）保留内存态，下一次修改会再试
            }
        }
    }

    private fun GroupRecord.withRegion(region: Region, list: List<PointRecord>): GroupRecord =
        if (region == Region.EXTERNAL) copy(external = list) else copy(ceiling = list)

    private fun readStore(): Store {
        if (!file.exists()) return Store()
        return try {
            StoreJson.decodeFromString(Store.serializer(), file.readText())
        } catch (_: Exception) {
            backupUnreadableStore()
            Store()
        }
    }

    /**
     * 把读不动的存档挪成 `groups.json.<毫秒时间戳>.bad`。
     *
     * 原先读失败直接返回空 `Store()`，而紧接着的第一次修改就会把这份空库整份写回同一路径
     * （见 [publish]）：内存里看着一切正常，用户的照片组却没了，盘上也看不出发生过什么。
     * 挪走之后，即使后续写了新数据，旧数据仍然躺在同一个目录里，可以人工抢救。
     *
     * 带时间戳而不是固定名，是为了永远不覆盖更早的坏档——这类文件只增不减，而读失败本就罕见。
     * 挪不动（目录只读、磁盘满）时静默退回原行为：那种文件本来也读不出来，新写入会覆盖它。
     */
    private fun backupUnreadableStore() {
        val stamp = System.currentTimeMillis()
        runCatching { file.renameTo(File(file.parentFile, "${file.name}.$stamp.bad")) }
    }

    private fun persist(store: Store) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(StoreJson.encodeToString(Store.serializer(), store))
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            // 文件系统不支持原子改名时回退普通改名
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        @Volatile
        private var instance: GroupRepository? = null

        fun get(context: Context): GroupRepository =
            instance ?: synchronized(this) {
                instance ?: GroupRepository(File(context.filesDir, "groups.json"))
                    .also { instance = it }
            }
    }
}