package io.github.hecate2.D7.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** 组名转安全的文件名/目录名片段：替换非法字符、限长 24、空名回落为 group。 */
fun sanitizeGroupName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(24).ifBlank { "group" }

/**
 * 删除一张照片，返回是否成功。content URI 走 MediaStore（失败返回 0 而非抛异常，
 * 必须看返回值）；file URI（低版本公共目录或私有目录回退）直接删文件。
 *
 * 「照片已经不在了」也归入失败：`delete` 对「本来就没有这条」与「没权限」一律返回 0，
 * 分不开。调用方要是想报错，得自己接受会有假警报——用户在相册里手动清过图就撞上。
 * 删组与删单点共用这一份，语义必须一致，所以这里不猜、只如实返回。
 */
fun deletePhoto(context: Context, uri: Uri): Boolean = if (uri.scheme == "file") {
    val file = uri.path?.let(::File) ?: return true
    !file.exists() || file.delete()
} else {
    runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}

/**
 * 删除一个拍摄点带的照片。**uri 为空是常态，不是异常**：「不拍照」模式录的点、结果页
 * 批量编辑新增的点、继承不到照片的点，photoUri 本来就是空的，直接跳过。
 *
 * 删不掉不提示，理由见 [deletePhoto]；调用方在自己的作用域里 launch 它即可。
 */
suspend fun deletePointPhoto(context: Context, uri: String?) {
    if (uri.isNullOrEmpty()) return
    val target = Uri.parse(uri)
    runCatching { withContext(Dispatchers.IO) { deletePhoto(context, target) } }
}

/**
 * 降采样解码一张图，最长边不超过 [maxPx]。URI 失效（照片被外部清理、条目已不在相册）
 * 时返回 null，由调用方决定显示占位框还是提示。
 *
 * 缩略图与点开看大图共用这一份：两处的差别只有 [maxPx]。按**最长边**降采样而不是宽度：
 * 竖拍照片的高远大于宽，只看宽度会解出一张高得多的图，大图尤其吃亏。
 *
 * 必须在 IO 线程调用：读流与解码都是真 IO 与 CPU。
 */
fun decodeDownsampled(context: Context, uri: Uri, maxPx: Int): Bitmap? = try {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) {
        null
    } else {
        var sample = 1
        while (longest / (sample * 2) >= maxPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
} catch (_: Exception) {
    null
}

/**
 * Android 10+ 的 MediaStore 发布流程：insert（IS_PENDING=1）→ 写入 → 解除 pending；
 * 任一步失败则删除条目并返回 null。
 */
fun publishPendingMediaStore(
    context: Context,
    collection: Uri,
    displayName: String,
    mime: String,
    relativeDir: String,
    write: (OutputStream) -> Unit,
): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = try {
        resolver.insert(collection, values)
    } catch (_: Exception) {
        null
    } ?: return null
    return try {
        resolver.openOutputStream(uri)?.use(write) ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        // 必须看返回值，理由与 [deletePhoto] 同源：`update` 对「这一行已经不在了」「provider
        // 没让改」都回 0，而这里确实换掉了一个值，所以回 0 只能是没改成。改不了的后果是这一条
        // 永远停在待发布状态——相册里看不见它，本应用却已经把 URI 记进点里，用户以为照片进了
        // 相册，实际只有本应用认得。按本函数开头那句约定处理：失败就删掉条目并回 null，
        // 让上层的三级兜底退到应用私有目录重新写一份。
        if (resolver.update(uri, values, null, null) <= 0) {
            resolver.delete(uri, null, null)
            return null
        }
        uri
    } catch (_: Exception) {
        resolver.delete(uri, null, null)
        null
    }
}

/** 落盘去向：公共目录（相册、下载）还是应用私有目录。 */
enum class PublishWhere { PUBLIC, APP_PRIVATE }

/** 落盘结果。[where] 说明去了哪，[uri] 是它的地址。 */
class PublishResult(val where: PublishWhere, val uri: Uri)

/**
 * 公共目录的落点。同一处地方要用两种说法描述：MediaStore 要 [relativeDir] 与 [collection]，
 * Android 9 及以下要 [legacyPublicDir]。三者必须指向同一个目录，否则同一个文件在不同系统版本
 * 上会分头落进两个地方；合成一个类型就是为了让这个约束只有一处可写错。
 */
class PublicDestination(
    /** MediaStore 的 `RELATIVE_PATH`，例如 `Pictures/D7/阳台`。 */
    val relativeDir: String,
    /** MediaStore 的集合，图片用 Images、CSV 用 Downloads。 */
    val collection: Uri,
    /** Android 9 及以下直接写的公共目录，与 [relativeDir] 指向同一处。 */
    val legacyPublicDir: File,
)

/**
 * 把一份内容落进公共目录，三级兜底：Android 10 及以上走 MediaStore 待发布条目；
 * 10 以下有写权限就写公共目录、需要时用媒体扫描登记；两条都不成则退到应用私有目录。
 *
 * [write] 只负责把字节写进给定的流，三级落盘都走它。拍照（`PhotoStore`）与导出
 * （`Exporter`）原本各写了一遍这套兜底，差别只有目录、文件名与调用方怎么包装返回值，
 * 于是「哪一级该扫媒体库」「失败退到哪」这类事有两份可以各自走偏的说法。
 *
 * 私有目录那一级不再登记媒体库：它本来就不在媒体库的扫描范围里。
 *
 * @param scan 写进公共目录后是否登记媒体库。CSV 这类非媒体文件不需要，登记了反而在
 *   相册里多出看不懂的条目。
 * @return 落盘结果；连私有目录都没写成时返回 null。
 */
fun publishToPublicOrPrivate(
    context: Context,
    displayName: String,
    mime: String,
    public: PublicDestination,
    privateDir: File,
    scan: Boolean,
    write: (OutputStream) -> Unit,
): PublishResult? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        publishPendingMediaStore(
            context, public.collection, displayName, mime, public.relativeDir, write,
        )?.let { return PublishResult(PublishWhere.PUBLIC, it) }
    } else if (hasWritePermission(context)) {
        try {
            if (public.legacyPublicDir.exists() || public.legacyPublicDir.mkdirs()) {
                val dest = File(public.legacyPublicDir, displayName)
                dest.outputStream().use(write)
                if (scan) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(dest.absolutePath),
                        arrayOf(mime),
                        null,
                    )
                }
                return PublishResult(PublishWhere.PUBLIC, Uri.fromFile(dest))
            }
        } catch (_: Exception) {
            // 落入应用私有目录回退
        }
    }
    return try {
        if (!privateDir.exists() && !privateDir.mkdirs()) return null
        val dest = File(privateDir, displayName)
        dest.outputStream().use(write)
        PublishResult(PublishWhere.APP_PRIVATE, Uri.fromFile(dest))
    } catch (_: Exception) {
        null
    }
}

/** 写公共目录需要的外部存储权限；Android 10 及以上走 MediaStore，不查这个。 */
private fun hasWritePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
