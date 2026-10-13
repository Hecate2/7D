package io.github.hecate2.D7.data

import io.github.hecate2.D7.core.Angles
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.ZoneId

/**
 * 存档的导出与导入。
 *
 * 落盘的 [StoreJson] 直接复用，导出即是落盘那份文本，导入走同一套解析器，两处不会各自漂移。
 *
 * 导入这条路的输入来自应用之外，**整份当作不可信数据**：文件可以是别的 app 写的、半路被改过的、
 * 或者干脆是另一个 JSON。所以除了格式（由序列化器把关），还要过一遍语义校验——经纬度范围、
 * 时区能不能解析、规模上限。这里出问题不会当场崩，而是等到算日照时才炸在一处离现场很远的地方，
 * 所以宁可整份拒绝，也不要放一份「看起来像存档」的东西进去。
 */

/** 导入时接受的存档字节数上限。先于解析判，免得一份畸形大文件把内存吃光。 */
const val STORE_MAX_BYTES = 4 * 1024 * 1024

/**
 * 组数与总点数上限，和字节上限一起把导入物占用的内存封住：4MB 的文本可以写成一堆极短的组，
 * 展开成对象之后的开销远大于文本本身。两个数都远超正常使用（一窗一组、每组几十个点），
 * 只用来拦住畸形文件，不是产品上的限制。
 */
internal const val MAX_GROUPS = 1_000
internal const val MAX_POINTS = 20_000

/** 把组数据编码成可导出的存档文本。与落盘同一份配置，所以导出的就是盘上那份。 */
fun encodeStore(groups: List<GroupRecord>): String =
    StoreJson.encodeToString(Store.serializer(), Store(groups = groups))

/** 从流里读存档文本；超过 [STORE_MAX_BYTES] 返回 null。开头的 UTF-8 BOM 会被丢掉。 */
fun readStoreText(input: InputStream): String? {
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    while (true) {
        val read = input.read(chunk)
        if (read < 0) break
        buffer.write(chunk, 0, read)
        if (buffer.size() > STORE_MAX_BYTES) return null
    }
    return String(buffer.toByteArray(), Charsets.UTF_8).removePrefix("\uFEFF")
}

/**
 * 解析一份外部存档。格式不符、或任何一项语义校验不通过，都整份拒绝并返回 null。
 *
 * 不抛异常：调用方拿到 null 就够判断「这份文件不能用」，它也不需要知道是缺了哪个键。
 *
 * 点的角度按仓库一贯的口径**归一到合法区间**（方位 0..360、仰角 0..90），与
 * `updatePointAngles` 用同一套规则，而不是拒绝——手改过的角度常见，但一个 370 度的方位角
 * 意思明确，没必要让整份存档不可用。照片引用则一律丢弃，理由见 [PointRecord.sanitized]。
 */
fun decodeStore(text: String): Store? {
    val store = runCatching {
        StoreJson.decodeFromString(Store.serializer(), text)
    }.getOrNull() ?: return null

    if (store.groups.size > MAX_GROUPS) return null
    if (store.groups.sumOf { it.external.size + it.ceiling.size } > MAX_POINTS) return null
    if (store.groups.map { it.id }.distinct().size != store.groups.size) return null

    val groups = store.groups.map { group -> group.sanitized() ?: return null }
    return Store(groups = groups)
}

/** 单组校验 + 点归一。任一项不过就返回 null，由调用方整份拒绝。 */
private fun GroupRecord.sanitized(): GroupRecord? {
    if (id.isBlank() || name.isBlank()) return null
    // 三处都用范围而不是先查有限性：`NaN !in a..b` 本来就是 true，区间判断把非有限数一并挡了，
    // 再补一句 isFinite 是永远不会执行到的死代码。上界取的是真实世界的极值再放宽一截。
    if (lat !in -90.0..90.0) return null
    if (lon !in -180.0..180.0) return null
    if (altitude !in -500.0..10_000.0) return null
    if (createdAt < 0L || updatedAt < 0L) return null
    // 时区字符串是个真实的崩溃点：写进去时随便写，等到算日照才 ZoneId.of 抛异常
    if (runCatching { ZoneId.of(zoneId) }.isFailure) return null

    val points = external.map { it.sanitized() ?: return null }
    val ceilings = ceiling.map { it.sanitized() ?: return null }
    return copy(external = points, ceiling = ceilings)
}

/**
 * 单点校验 + 角度归一，照片引用一律清空。
 *
 * 这里的有限性判断在正常路径上不会触发：序列化器默认就不放 NaN 与 Infinity 进来（见
 * `nonFiniteNumbersAreRejected`）。留着是因为下面两处归一都是「原样返还」式的——
 * `normalize360(NaN)` 仍是 NaN，`coerceIn` 也放 NaN 过去——一旦哪天 JSON 配置改成允许
 * 特殊浮点值，这两行就是唯一的拦截点，而它拦不住的话，NaN 会一路走到落盘才炸。
 *
 * 照片引用不进导入数据：`photoUri` 只在本机有意义（MediaStore 的行号、私有目录的路径），
 * 而它指向的字节不在文件里。跨设备导入时那个行号大体上会命中**别人另一张照片**——后果不止
 * 缩略图张冠李戴，删点与删组会照着自己记下的 URI 去删，把与本应用无关的照片从相册里抹掉。
 * `file://` 那种更直接：删除实现直接删那个路径，未校验的路径等于一个可删任意本地文件的入口。
 * 所以这里不是「校验一下方案」而是整类清空——存档本来就不含照片，导入后没有照片才是如实状态。
 */
private fun PointRecord.sanitized(): PointRecord? {
    if (!az.isFinite() || !el.isFinite()) return null
    if (takenAt < 0L) return null
    return copy(
        az = Angles.normalize360(az),
        el = el.coerceIn(0.0, 90.0),
        photoUri = null,
    )
}
