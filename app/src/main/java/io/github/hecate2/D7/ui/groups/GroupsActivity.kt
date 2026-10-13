package io.github.hecate2.D7.ui.groups

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.hecate2.D7.R
import io.github.hecate2.D7.data.GroupRecord
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.decodeStore
import io.github.hecate2.D7.data.encodeStore
import io.github.hecate2.D7.data.readStoreText
import io.github.hecate2.D7.databinding.ActivityGroupsBinding
import io.github.hecate2.D7.databinding.DialogDeleteGroupBinding
import io.github.hecate2.D7.databinding.DialogNewGroupBinding
import io.github.hecate2.D7.databinding.ItemGroupBinding
import io.github.hecate2.D7.export.Exporter
import io.github.hecate2.D7.sensor.FixLocation
import io.github.hecate2.D7.sensor.LocationProvider
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.bindHemisphereSuffix
import io.github.hecate2.D7.ui.capture.CaptureActivity
import io.github.hecate2.D7.ui.Settings
import io.github.hecate2.D7.ui.result.ResultActivity
import io.github.hecate2.D7.ui.setToggleTint
import io.github.hecate2.D7.ui.showGroupEditor
import io.github.hecate2.D7.util.CardSummary
import io.github.hecate2.D7.util.deletePhoto
import io.github.hecate2.D7.util.Format
import io.github.hecate2.D7.util.Locales
import io.github.hecate2.D7.util.applyKeepScreenOn
import io.github.hecate2.D7.util.Summaries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 照片组管理（应用入口）。
 * 卡片列表来自仓库的 StateFlow；点卡片进结果页，长按编辑（组名 / 经纬度 / 时区）、删除或导出；
 * 右上角加号建组后直接进采集页；一个组都没有时，主显示区正中那枚大加号是同一个动作。
 */
class GroupsActivity : ComponentActivity() {

    private lateinit var binding: ActivityGroupsBinding
    private lateinit var settings: Settings
    private lateinit var repository: GroupRepository
    private val locationProvider by lazy { LocationProvider(this) }

    /** 最近一次 GPS 定位结果，建组时若坐标未再手改则沿用其海拔与时区。 */
    private var lastFix: FixLocation? = null

    /** 定位权限刚获批后要继续执行的定位动作。 */
    private var pendingLocate: (() -> Unit)? = null

    /**
     * 是否有导出在飞。这里的入口是长按弹的菜单项，没有按钮可以置灰，
     * 用户连点就会并发跑两份导出（精算 + 写文件都在Dispatchers.IO），
     * 既白耗 CPU 又可能同时写同一个文件名。故直接拒绝重复请求。
     */
    private var exportInFlight = false

    /**
     * 是否有整份存档的导出在飞。与 [exportInFlight]（单组 CSV / PNG）不是一回事：
     * 这里的入口是标题栏那颗托盘键，同样没有按钮可以置灰，两次导出会各弹一次选择器。
     */
    private var exportStoreInFlight = false

    /**
     * 是否有整份存档的导入在读。选择器一旦返回，读是异步的，而这一段没有任何按钮可以置灰，
     * 连点两下会弹出两个确认对话框，两份存档各自覆盖一遍。
     *
     * 与 [exportStoreInFlight] 互斥：导入是要把内存态整份换掉、导出是要把内存态整份写出去，
     * 让它们交错只会得到一份自己也说不清是谁的存档。
     */
    private var importStoreInFlight = false

    /**
     * 存档导出的落点由用户当场选（SAF），app 不指定目录。这样既不需要任何存储权限，
     * 也把「存到网盘还是存到下载」这个只有用户知道答案的问题交回给用户。
     */
    private val createStoreDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument(STORE_MIME)) { uri ->
            // 用户在选择器里取消：没有后续的写，标志在这里就得放掉
            if (uri == null) exportStoreInFlight = false else writeStoreTo(uri)
        }

    /**
     * 导入来源同样由用户选。放进 mime 列表的不止 `application/json`：不少文件管理器把 `.json`
     * 报成 `text/plain` 或 `application/octet-stream`，只认一种会让用户在自家文件上看见灰条选不中。
     * 真正把关的是 [decodeStore] 那套校验，不是选择器给的类型。
     */
    private val openStoreDoc =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) importStoreInFlight = false else readStoreFrom(uri)
        }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingLocate
            pendingLocate = null
            if (granted) {
                locationProvider.warmUp()
                action?.invoke()
            } else {
                toast(getString(R.string.gps_failed))
            }
        }

    /** 界面语言按用户选择覆写（见 [Locales]）：不引 AppCompat 的做法，minSdk 26 起行为一致。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        applyKeepScreenOn(settings.keepScreenOn)

        repository = GroupRepository.get(this)
        binding.newButton.setOnClickListener { showNewGroupDialog() }
        binding.emptyNewButton.setOnClickListener { showNewGroupDialog() }
        binding.langButton.setOnClickListener { showLanguageDialog() }
        binding.keepOnButton.setOnClickListener { toggleKeepScreenOn() }
        binding.exportStoreButton.setOnClickListener { startExportStore() }
        binding.importStoreButton.setOnClickListener { startImportStore() }
        updateKeepOnUi()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 卡片汇总含每组一次冬至日照精算（约 0.3ms/组），必须在后台线程算，
                // 否则组数一多，每次采集/改名都会在主线程卡住整个列表。
                repository.groups
                    .map { groups -> withContext(Dispatchers.Default) { groups.map { Summaries.cardSummary(it) } } }
                    .collect { summaries ->
                        renderGroups(summaries)
                        binding.emptyView.isVisible = summaries.isEmpty()
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 前台期间在后台尝试定位（无权限时静默跳过），让建组对话框能秒回结果
        locationProvider.warmUp()
    }

    override fun onStop() {
        super.onStop()
        // 退到后台即注销预热监听，避免 GPS 耗电与上下文泄漏
        locationProvider.shutdown()
    }

    override fun onDestroy() {
        // 定位权限回调可能晚于对话框消亡，清掉以免持有已失效的视图
        pendingLocate = null
        super.onDestroy()
    }

    /**
     * 屏幕常亮开关：这是全应用共用的偏好，但入口只在根页面这一处。
     *
     * 写进偏好后当场把本窗口的标志也改掉——`FLAG_KEEP_SCREEN_ON` 是逐窗口的，
     * 只写偏好不重设标志的话，要等下一次进页面才生效。
     */
    private fun toggleKeepScreenOn() {
        settings.keepScreenOn = !settings.keepScreenOn
        applyKeepScreenOn(settings.keepScreenOn)
        updateKeepOnUi()
        binding.keepOnButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun updateKeepOnUi() = binding.keepOnButton.setToggleTint(settings.keepScreenOn)

    private fun openResult(group: GroupRecord) {
        startActivity(Intent(this, ResultActivity::class.java).putExtra(Extras.GROUP_ID, group.id))
    }

    /**
     * 点卡片：已经拍过点的看结果；一个点都没有的新组直接进采集页——
     * 空组的结果页没有任何信息量，而新建组后想接着拍还要再点一次「回采集续拍」。
     */
    private fun openGroup(group: GroupRecord) {
        if (group.external.isEmpty() && group.ceiling.isEmpty()) {
            startActivity(Intent(this, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, group.id))
        } else {
            openResult(group)
        }
    }

    /** 卡片列表行数很少，直接逐张 inflate（无需回收复用）。 */
    private fun renderGroups(summaries: List<CardSummary>) {
        binding.groupList.removeAllViews()
        for (summary in summaries) {
            val item = ItemGroupBinding.inflate(layoutInflater, binding.groupList, false)
            bindGroupCard(item, summary)
            binding.groupList.addView(item.root)
        }
    }

    private fun bindGroupCard(item: ItemGroupBinding, summary: CardSummary) {
        val group = summary.group
        val context = item.root.context
        item.name.text = group.name

        val winter = summary.winterMinutes
        if (winter == null) {
            item.winterResult.setText(R.string.group_untested)
            item.winterResult.setTextColor(ContextCompat.getColor(context, R.color.smoke))
        } else {
            item.winterResult.text =
                context.getString(R.string.group_winter, Format.durationShort(context.resources, winter))
            item.winterResult.setTextColor(ContextCompat.getColor(context, R.color.paper))
        }

        val hasPoints = summary.externalCount > 0 || summary.ceilingCount > 0
        val stamp = if (hasPoints) group.updatedAt else group.createdAt
        val date = Format.dateShort(stamp, group.zoneId)
        item.meta.text = if (hasPoints) {
            context.getString(
                R.string.group_meta_captured,
                date,
                Format.coordinate(group.lat, group.lon),
            )
        } else {
            context.getString(R.string.group_meta_unshot, date)
        }

        if (summary.externalCount >= 2) {
            item.externalPill.setBackgroundResource(R.drawable.bg_pill)
            item.externalPill.setTextColor(ContextCompat.getColor(context, R.color.smoke))
            val prefix = context.getString(R.string.group_external_prefix, summary.externalCount)
            val percent =
                context.getString(R.string.group_external_percent, summary.externalCoveredPercent)
            item.externalPill.text = SpannableStringBuilder(prefix + percent).apply {
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(context, R.color.paper)),
                    prefix.length,
                    prefix.length + percent.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        } else {
            item.externalPill.setBackgroundResource(R.drawable.bg_pill_dashed)
            item.externalPill.setTextColor(ContextCompat.getColor(context, R.color.smoke))
            item.externalPill.setText(R.string.group_external_none)
        }

        if (summary.ceilingCount > 0) {
            item.ceilingPill.setBackgroundResource(R.drawable.bg_pill_moon)
            item.ceilingPill.setTextColor(ContextCompat.getColor(context, R.color.moon))
            item.ceilingPill.text =
                context.getString(R.string.group_ceiling_pill, summary.ceilingCount)
        } else {
            item.ceilingPill.setBackgroundResource(R.drawable.bg_pill_dashed)
            item.ceilingPill.setTextColor(ContextCompat.getColor(context, R.color.smoke))
            item.ceilingPill.setText(R.string.group_ceiling_none)
        }

        item.root.setOnClickListener { openGroup(group) }
        item.root.setOnLongClickListener { view ->
            showGroupMenu(group, view)
            true
        }
    }

    private fun showGroupMenu(group: GroupRecord, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_EDIT, 0, R.string.menu_edit)
            menu.add(0, MENU_DELETE, 1, R.string.menu_delete)
            menu.add(0, MENU_EXPORT, 2, R.string.menu_export)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT -> {
                        // 限定到 Activity：apply 里的 this 是 PopupMenu
                        showGroupEditor(this@GroupsActivity, group)
                        true
                    }

                    MENU_DELETE -> {
                        confirmDelete(group)
                        true
                    }

                    MENU_EXPORT -> {
                        showExportDialog(group)
                        true
                    }

                    else -> false
                }
            }
        }.show()
    }

    private fun confirmDelete(group: GroupRecord) {
        val photos = (group.external + group.ceiling).mapNotNull { it.photoUri }
        val view = DialogDeleteGroupBinding.inflate(layoutInflater)
        view.deleteMessage.text = getString(R.string.delete_group_message, group.name)
        view.deletePhotos.isVisible = photos.isNotEmpty()
        if (photos.isNotEmpty()) {
            view.deletePhotos.text = getString(R.string.delete_group_with_photos, photos.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_group_title)
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                deleteGroup(group, photos, view.deletePhotos.isChecked)
            }
            .show()
    }

    /** 删组；勾选「同时删照片」时先尽力删除相册中的照片（URI 可能已失效），失败不影响删组。 */
    private fun deleteGroup(group: GroupRecord, photos: List<String>, alsoPhotos: Boolean) {
        lifecycleScope.launch {
            if (alsoPhotos && photos.isNotEmpty()) {
                val failed = withContext(Dispatchers.IO) {
                    photos.count { !deletePhoto(this@GroupsActivity, Uri.parse(it)) }
                }
                if (failed > 0) toast(getString(R.string.delete_group_photos_failed, failed))
            }
            repository.deleteGroup(group.id)
        }
    }

    /**
     * 导出全部存档。两处先挡掉：空库（导出一个空文件出去，用户看不出这算成功还是算没做事）、
     * 以及已经有导入在飞（两边换的是同一份内存态）。
     */
    private fun startExportStore() {
        if (exportStoreInFlight || importStoreInFlight) return
        if (repository.groups.value.isEmpty()) {
            toast(getString(R.string.export_store_empty))
            return
        }
        val stamp = LocalDate.now().toString().replace("-", "")
        exportStoreInFlight = true
        // 选择器是系统提供的文档界面，正常设备上必定存在，这一句平时走不到。留着是因为它的
        // 失败方式特别难查：`launch` 抛异常会直接冒到 onClick 之外，而标志已经置成 true，
        // 那个键从此永久失灵——点它没有任何反应，也没有任何提示，重启才恢复。
        runCatching { createStoreDoc.launch(getString(R.string.export_store_file_name, stamp)) }
            .onFailure {
                exportStoreInFlight = false
                toast(getString(R.string.export_store_failed))
            }
    }

    /**
     * 写出的是当前内存态，不去读盘：内存态就是刚发射的那一份，而写队列是异步的，读盘反而
     * 可能撞上还没跑完的那次写入，导出前一版数据。`wt` 是截断模式——用户在系统选择器里
     * 选中一个已存在的文件时，不能把新内容接在旧内容的后面。
     *
     * 写失败时留在用户选的那个位置上的可能是一份截断的 JSON。这里不做补救：SAF 没有改名，
     * 而「先写临时文件再原子改名」那套正是因此用不了的。用户自己的数据不受影响——写的是
     * 他当场选的那个文件，本机的 groups.json 一个字节都没动。
     */
    private fun writeStoreTo(uri: Uri) {
        val groups = repository.groups.value
        lifecycleScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri, "wt")?.use { sink ->
                            sink.write(encodeStore(groups).toByteArray(Charsets.UTF_8))
                        } != null
                    }.getOrDefault(false)
                }
                if (ok) {
                    toast(getString(R.string.export_store_done, groups.size))
                } else {
                    toast(getString(R.string.export_store_failed))
                }
            } finally {
                // 协程被生命周期取消时也要放掉，否则回到这个页面就再也导不出去了
                exportStoreInFlight = false
            }
        }
    }

    private fun startImportStore() {
        if (importStoreInFlight || exportStoreInFlight) return
        importStoreInFlight = true
        // 同 [startExportStore]：拉不起来时要把标志放掉，否则导入这个入口永久失灵。
        // 文案用的是「这份文件不能导入」那一条，严格说不准确（文件根本还没选），
        // 取的是「宁可说得粗一点，也不要什么都不说」。
        runCatching { openStoreDoc.launch(STORE_MIME_CANDIDATES) }
            .onFailure {
                importStoreInFlight = false
                toast(getString(R.string.import_store_failed))
            }
    }

    /** 读进来先整份过一遍校验，通过了才问用户要不要覆盖——不合规的文件连对话框都不必弹。 */
    private fun readStoreFrom(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { readStoreText(it) }
                }.getOrNull()
            }
            importStoreInFlight = false
            val groups = text?.let { decodeStore(it) }?.groups
            if (groups == null) {
                toast(getString(R.string.import_store_failed))
                return@launch
            }
            // 读是异步的，这几毫秒里用户可能已经退出去了。对话框挂在已销毁的窗口上会漏，
            // 数据也已经没机会被替换，直接不发这一步。
            if (isFinishing || isDestroyed) return@launch
            confirmImport(groups)
        }
    }

    /**
     * 覆盖前的确认。**必须把两边的组数都写出来**：这是不可撤销的一步，用户要知道自己
     * 正在用几组换掉几组，而不是只知道「要导入」。
     *
     * 确认之后先等落盘再报成功。落盘本身是异步的，不等的话提示弹出的那一刻字节还在内存里，
     * 用户此时划掉应用就会让这次导入凭空消失——下次启动看到的是导入前的旧数据，而界面上
     * 明明写着「已导入」。
     */
    private fun confirmImport(groups: List<GroupRecord>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_store_title)
            .setMessage(
                getString(R.string.import_store_message, groups.size, repository.groups.value.size),
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                repository.replaceAll(groups)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repository.awaitWrites() }
                    toast(getString(R.string.import_store_done, groups.size))
                }
            }
            .show()
    }

    private fun showExportDialog(group: GroupRecord) {
        if (group.external.isEmpty() && group.ceiling.isEmpty()) {
            toast(getString(R.string.result_no_data))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_export)
            .setItems(
                arrayOf(
                    getString(R.string.result_export_csv),
                    getString(R.string.result_export_image),
                ),
            ) { _, which -> exportGroup(group, csv = which == 0) }
            .show()
    }

    /** 列表页快速导出：按默认档位与当地冬至日算一次，交给 Exporter。 */
    private fun exportGroup(group: GroupRecord, csv: Boolean) {
        if (exportInFlight) {
            toast(getString(R.string.result_export_busy))
            return
        }
        val mode = Summaries.defaultMode(group)
        val modeLabel = Format.calcMode(resources, mode)
        val year = LocalDate.now(ZoneId.of(group.zoneId)).year
        val date = Summaries.winterDate(year, group.lat)
        exportInFlight = true
        lifecycleScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    val daily = Summaries.evaluate(group, mode, date)
                    if (csv) {
                        Exporter.exportCsv(this@GroupsActivity, group, date, daily, modeLabel)
                    } else {
                        Exporter.exportImage(
                            this@GroupsActivity, group, getString(R.string.result_date_winter),
                            modeLabel, daily,
                            daily.directMinutes, Summaries.gbWindowMinutes(group, mode, year),
                        )
                    }
                }
                if (outcome.location != null) {
                    toast(getString(R.string.result_export_done, outcome.location))
                } else {
                    toast(getString(R.string.result_export_failed))
                }
            } finally {
                // 协程被生命周期取消时也会走到这里，不会把标志卡在 true
                exportInFlight = false
            }
        }
    }

    /**
     * 语言选择对话框。语言名一律用各自的写法（Deutsch、Русский…）而不翻译，
     * 否则在中文界面里「德语 / 俄语」对想切语言的人毫无用处。
     *
     * 选完直接 [recreate]：配置是在 `attachBaseContext` 里改的，重建一次就把整棵视图树
     * 换成新语言，不需要逐个控件 setText。
     */
    private fun showLanguageDialog() {
        val tags = Locales.choices()
        val labels = tags.map { getString(Locales.labelRes(it)) }.toTypedArray()
        val checked = tags.indexOf(Locales.currentTag(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                if (tags[which] != Locales.currentTag(this)) {
                    Locales.setTag(this, tags[which])
                    recreate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNewGroupDialog() {
        val view = DialogNewGroupBinding.inflate(layoutInflater)
        view.latInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateHemisphereHint(view)
        })
        // 先替用户起好名字；三个框都是进焦点即全选（见布局里的 selectAllOnFocus）
        view.nameInput.setText(getString(R.string.group_name_default))
        view.lonInput.bindHemisphereSuffix(view.lonSuffix, Format::longitudeSuffix)
        view.latInput.bindHemisphereSuffix(view.latSuffix, Format::latitudeSuffix)
        view.gpsButton.setOnClickListener { locateFromButton(view) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.new_group_title)
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.start_capture, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = view.nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    view.nameInput.error = getString(R.string.group_name_required)
                    return@setOnClickListener
                }
                val lat = view.latInput.text.toString().toDoubleOrNull()
                val lon = view.lonInput.text.toString().toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    toast(getString(R.string.invalid_latlon))
                    return@setOnClickListener
                }
                // 仅当坐标仍与 GPS 结果一致时沿用其海拔与时区，手改后退回系统默认
                val fix = lastFix?.takeIf { abs(it.lat - lat) < 1e-6 && abs(it.lon - lon) < 1e-6 }
                val group = repository.createGroup(
                    name = name,
                    lat = lat,
                    lon = lon,
                    altitude = fix?.altitude ?: 0.0,
                    zoneId = fix?.zoneId ?: ZoneId.systemDefault().id,
                )
                dialog.dismiss()
                startActivity(
                    Intent(this, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, group.id),
                )
            }
            // 光标与键盘都放进组名框：用户十有八九先动名字，动完再抬头看坐标填好没有。
            // 全选由布局里的 selectAllOnFocus 负责，获得焦点时自己就选了，不必再调一次
            view.nameInput.requestFocus()
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            )
            // 坐标是必填项：打开就替用户定位，省掉「先点一下 GPS 再等」这一步
            autoLocate(view)
        }
        dialog.show()
        updateHemisphereHint(view)
    }

    /**
     * 打开对话框时的自动定位：**只填用户还没动过的坐标**。
     *
     * 键盘是弹着的，用户很可能先改组名再手输经纬度，而定位结果要几秒才回来；
     * 不加这道门就会把人家刚敲进去的数字盖掉。手动点 GPS 则一律覆盖（见 [locateFromButton]）。
     */
    private fun autoLocate(view: DialogNewGroupBinding) {
        val latBefore = view.latInput.text.toString()
        val lonBefore = view.lonInput.text.toString()
        locate(
            onStart = { view.gpsStatus.setText(R.string.gps_locating) },
            onFix = { fix ->
                val untouched = view.latInput.text.toString() == latBefore &&
                    view.lonInput.text.toString() == lonBefore
                if (untouched) applyFix(view, fix)
            },
            onFail = { view.gpsStatus.setText(R.string.gps_failed) },
        )
    }

    /** 手动点 GPS：输入框里原来有什么都按定位结果覆盖。 */
    private fun locateFromButton(view: DialogNewGroupBinding) = locate(
        onStart = { view.gpsStatus.setText(R.string.gps_locating) },
        onFix = { applyFix(view, it) },
        onFail = { view.gpsStatus.setText(R.string.gps_failed) },
    )

    private fun applyFix(view: DialogNewGroupBinding, fix: FixLocation) {
        view.latInput.setText(String.format(Locale.US, "%.6f", fix.lat))
        view.lonInput.setText(String.format(Locale.US, "%.6f", fix.lon))
        view.gpsStatus.text = fixStatusText(fix)
    }

    private fun fixStatusText(fix: FixLocation): String {
        val coordinate = Format.coordinate(fix.lat, fix.lon)
        return if (fix.accuracyMeters > 0f) {
            getString(R.string.gps_done_accuracy, coordinate, fix.accuracyMeters.roundToInt())
        } else {
            getString(R.string.gps_done, coordinate)
        }
    }

    private fun locate(onStart: () -> Unit, onFix: (FixLocation) -> Unit, onFail: () -> Unit) {
        if (!locationProvider.hasPermission()) {
            pendingLocate = { locate(onStart, onFix, onFail) }
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        // 启动时已预热：命中新鲜缓存（5 分钟内）就秒回，不干等
        locationProvider.lastFresh()?.let {
            lastFix = it
            onFix(it)
            return
        }
        onStart()
        locationProvider.requestSingleUpdate(
            onResult = {
                lastFix = it
                onFix(it)
            },
            onTimeout = onFail,
        )
    }

    private fun updateHemisphereHint(view: DialogNewGroupBinding) {
        val lat = view.latInput.text.toString().toDoubleOrNull()
        view.hemisphereHint.setText(
            when {
                lat == null -> R.string.hemisphere_unknown
                abs(lat) <= 23.5 -> R.string.hemisphere_tropic
                lat > 0 -> R.string.hemisphere_north
                else -> R.string.hemisphere_south
            },
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MENU_EDIT = 1
        const val MENU_DELETE = 2
        const val MENU_EXPORT = 3

        /** 存档的 mime。写出去时用它，读进来时只用它一种会让部分文件管理器里的文件选不中。 */
        const val STORE_MIME = "application/json"

        val STORE_MIME_CANDIDATES =
            arrayOf(STORE_MIME, "text/plain", "application/octet-stream")
    }
}
