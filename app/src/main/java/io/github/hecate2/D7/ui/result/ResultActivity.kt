package io.github.hecate2.D7.ui.result

import android.app.AlertDialog
import android.app.Dialog
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.hecate2.D7.R
import io.github.hecate2.D7.core.Angles
import io.github.hecate2.D7.core.CalcMode
import io.github.hecate2.D7.core.DailySunlight
import io.github.hecate2.D7.core.SunlightEvaluator
import io.github.hecate2.D7.data.GroupRecord
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.databinding.ActivityResultBinding
import io.github.hecate2.D7.databinding.DialogPhotoBinding
import io.github.hecate2.D7.databinding.DialogPointAnglesBinding
import io.github.hecate2.D7.databinding.DialogPointsBulkBinding
import io.github.hecate2.D7.export.Exporter
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.Grade
import io.github.hecate2.D7.ui.capture.CaptureActivity
import io.github.hecate2.D7.ui.colorRes
import io.github.hecate2.D7.ui.Settings
import io.github.hecate2.D7.ui.setPillSelected
import io.github.hecate2.D7.ui.showGroupEditor
import io.github.hecate2.D7.util.Format
import io.github.hecate2.D7.util.Locales
import io.github.hecate2.D7.util.Zones
import io.github.hecate2.D7.util.applyKeepScreenOn
import io.github.hecate2.D7.util.Summaries
import io.github.hecate2.D7.util.decodeDownsampled
import io.github.hecate2.D7.util.deletePointPhoto
import io.github.hecate2.D7.util.toShotPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * 结果页：日期药丸与计算档 → 主结果卡、当天时间线、国标辅助卡、全年曲线 → 点列编辑（删点、切换连线、单点/批量角度编辑）→ 导出与续拍。
 * 元信息那行的「编辑」改的是组记录的组名、经纬度与时区，与列表页长按菜单进的是同一个对话框
 * （见 [showGroupEditor]）。计算在后台协程执行，数据变化（续拍、改点、改地点）经仓库 StateFlow 自动触发重算。
 */
class ResultActivity : ComponentActivity() {

    private enum class Preset { WINTER, DAHAN, EQUINOX, SUMMER, CUSTOM }

    private lateinit var binding: ActivityResultBinding
    private lateinit var repository: GroupRepository
    private lateinit var pointRows: PointRows

    private var group: GroupRecord? = null
    private var preset = Preset.WINTER
    private var customDate: LocalDate? = null
    private var mode = CalcMode.EXTERNAL_ONLY

    private var computeJob: Job? = null
    private var curveCacheKey: String? = null
    private var curveCache: List<Double>? = null

    private var daily: DailySunlight? = null
    private var winterMinutes: Int? = null
    private var gbWindowMinutes: Int? = null
    private var selectedDateValue: LocalDate? = null

    /** 界面语言按用户选择覆写（见 [Locales]）：不引 AppCompat 的做法，minSdk 26 起行为一致。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyKeepScreenOn(Settings(this).keepScreenOn)

        // 国标那句只给中文界面：GB 50180-2018 的气候区与时限出了中国没有对照意义，
        // 其余语言在各自的 strings.xml 里取空串。空的时候连那一行的高度与 6dp 间距一并收掉，
        // 否则卡片底部会多出一条谁也说不清来历的空白。
        binding.gbHint.isVisible = getString(R.string.result_gb_hint).isNotEmpty()

        repository = GroupRepository.get(this)
        pointRows = PointRows(
            container = binding.pointList,
            scope = lifecycleScope,
            onToggleSegment = ::toggleSegment,
            onDelete = ::deletePoint,
            onEdit = ::editPoint,
            onShowPhoto = ::showPhoto,
        )

        binding.backButton.setOnClickListener { finish() }
        binding.editGroup.setOnClickListener {
            group?.let { showGroupEditor(this, it) }
        }
        binding.pillWinter.setOnClickListener { choosePreset(Preset.WINTER) }
        binding.pillDahan.setOnClickListener { choosePreset(Preset.DAHAN) }
        binding.pillEquinox.setOnClickListener { choosePreset(Preset.EQUINOX) }
        binding.pillSummer.setOnClickListener { choosePreset(Preset.SUMMER) }
        binding.pillCustom.setOnClickListener { pickCustomDate() }
        binding.chipExternalOnly.setOnClickListener { chooseMode(CalcMode.EXTERNAL_ONLY) }
        binding.chipBoth.setOnClickListener { chooseMode(CalcMode.EXTERNAL_AND_CEILING) }
        binding.chipCeilingOnly.setOnClickListener { chooseMode(CalcMode.CEILING_ONLY) }
        binding.exportCsvButton.setOnClickListener { export(csv = true) }
        binding.exportImageButton.setOnClickListener { export(csv = false) }
        binding.captureAgainButton.setOnClickListener { openCapture() }
        binding.editPoints.setOnClickListener { editPointsBulk() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.groups.collect { groups -> onGroups(groups) }
            }
        }
    }

    private fun onGroups(groups: List<GroupRecord>) {
        val id = intent.getStringExtra(Extras.GROUP_ID).orEmpty()
        val g = groups.firstOrNull { it.id == id }
        if (g == null) {
            finish()
            return
        }
        group = g
        if (mode != CalcMode.EXTERNAL_ONLY && g.ceiling.isEmpty()) {
            // 天花板区被删空时档位失效：退回仅外部，并告知用户为什么结论变了
            mode = CalcMode.EXTERNAL_ONLY
            toast(getString(R.string.result_mode_ceiling_empty))
        }
        binding.titleResult.text = getString(R.string.result_title, g.name)
        binding.resultMeta.text = getString(
            R.string.result_meta,
            Format.coordinate(g.lat, g.lon),
            Format.dateShort(g.createdAt, g.zoneId),
            Zones.label(resources, g.zoneId),
        )
        updatePills()
        updateChips(g)
        rebuildPoints(g)
        recompute()
    }

    // ---------------- 选择与状态 ----------------

    private fun choosePreset(next: Preset) {
        if (preset == next) return
        preset = next
        updatePills()
        recompute()
    }

    private fun pickCustomDate() {
        val g = group ?: return
        val initial = selectedDate(g)
        DatePickerDialog(
            this,
            { _, year, month, day ->
                customDate = LocalDate.of(year, month + 1, day)
                preset = Preset.CUSTOM
                updatePills()
                recompute()
            },
            initial.year, initial.monthValue - 1, initial.dayOfMonth,
        ).show()
    }

    private fun chooseMode(next: CalcMode) {
        val g = group ?: return
        if (next != CalcMode.EXTERNAL_ONLY && g.ceiling.isEmpty()) {
            toast(getString(R.string.result_mode_ceiling_locked))
            return
        }
        if (mode == next) return
        mode = next
        updateChips(g)
        recompute()
    }

    private fun updatePills() {
        val pills = mapOf(
            binding.pillWinter to Preset.WINTER,
            binding.pillDahan to Preset.DAHAN,
            binding.pillEquinox to Preset.EQUINOX,
            binding.pillSummer to Preset.SUMMER,
            binding.pillCustom to Preset.CUSTOM,
        )
        for ((view, value) in pills) {
            view.setPillSelected(preset == value)
        }
    }

    private fun updateChips(g: GroupRecord) {
        val ceilingEmpty = g.ceiling.isEmpty()
        val chips = listOf(
            binding.chipExternalOnly to CalcMode.EXTERNAL_ONLY,
            binding.chipBoth to CalcMode.EXTERNAL_AND_CEILING,
            binding.chipCeilingOnly to CalcMode.CEILING_ONLY,
        )
        for ((view, value) in chips) {
            view.setPillSelected(mode == value)
            view.alpha = if (value != CalcMode.EXTERNAL_ONLY && ceilingEmpty) 0.35f else 1f
        }
    }

    private fun selectedDate(g: GroupRecord): LocalDate {
        val year = LocalDate.now(ZoneId.of(g.zoneId)).year
        return when (preset) {
            Preset.WINTER -> Summaries.winterDate(year, g.lat)
            Preset.DAHAN -> Summaries.dahanDate(year, g.lat)
            Preset.EQUINOX -> LocalDate.of(year, 3, 21)
            Preset.SUMMER -> if (g.lat >= 0) LocalDate.of(year, 6, 21) else LocalDate.of(year, 12, 21)
            Preset.CUSTOM -> customDate ?: Summaries.winterDate(year, g.lat)
        }
    }

    private fun modeLabel(): String = Format.calcMode(resources, mode)

    private fun dateLabel(date: LocalDate): String = when (preset) {
        Preset.WINTER -> getString(R.string.result_date_winter)
        Preset.DAHAN -> getString(R.string.result_date_dahan)
        Preset.EQUINOX -> getString(R.string.result_date_equinox)
        Preset.SUMMER -> getString(R.string.result_date_summer)
        Preset.CUSTOM -> Format.monthDay(date)
    }

    // ---------------- 计算 ----------------

    private data class Computed(
        val daily: DailySunlight,
        val winterMinutes: Int,
        val gbWindowMinutes: Int,
        val curve: List<Double>,
        val curveKey: String,
    )

    private fun recompute() {
        val g = group ?: return
        computeJob?.cancel()
        binding.mainValue.text = getString(R.string.result_computing)
        val date = selectedDate(g)
        val modeNow = mode
        val year = LocalDate.now(ZoneId.of(g.zoneId)).year
        val winterDate = Summaries.winterDate(year, g.lat)
        val key = curveKey(g, modeNow, year)
        computeJob = lifecycleScope.launch {
            val computed = withContext(Dispatchers.Default) {
                val external = g.external.toShotPoints()
                val ceiling = g.ceiling.toShotPoints()
                val dailyNow = Summaries.evaluate(g, modeNow, date)
                val winter = if (date == winterDate) {
                    dailyNow.directMinutes
                } else {
                    Summaries.evaluate(g, modeNow, winterDate).directMinutes
                }
                val gb = Summaries.gbWindowMinutes(g, modeNow, year)
                val curve = curveCache?.takeIf { key == curveCacheKey }
                    ?: SunlightEvaluator.yearlyCurve(
                        g.lat, g.lon, g.zoneId, external, ceiling, modeNow, year,
                    )
                Computed(dailyNow, winter, gb, curve, key)
            }
            curveCache = computed.curve
            curveCacheKey = computed.curveKey
            daily = computed.daily
            winterMinutes = computed.winterMinutes
            gbWindowMinutes = computed.gbWindowMinutes
            selectedDateValue = date
            renderComputed(g, date, computed)
        }
    }

    /**
     * 主半圆覆盖提示。**两档的未覆盖语义恰好相反，所以提示方向必须跟着档位变**：
     *
     * - 外部区（含「外部+天花板」）：未拍到的方位按需求视为开阔，拍得越少结论越偏向「有太阳」，
     *   即可能**虚高**。覆盖条与 [Summaries.coveragePercent] 都只统计外部区。
     * - 「仅天花板」：未拍到的方位视为全遮挡，拍得越少结论越偏向「没太阳」，即可能**偏保守**。
     *   此时外部区的覆盖率与结论无关，得改看天花板区自己的覆盖率，文案也要换个方向。
     *
     * 分级沿用精度药丸那套四态配色：<60% 红、60~85% 黄、>85% 烟灰。
     */
    private fun bindCoverageNote(g: GroupRecord) {
        val view = binding.coverageNote
        view.isVisible = true
        val ceilingOnly = mode == CalcMode.CEILING_ONLY
        val points = if (ceilingOnly) g.ceiling else g.external
        // 点数不足时连覆盖率都算不上，直接定性说明偏差方向，而不是给一个百分比
        if (points.size < 2) {
            view.setText(
                if (ceilingOnly) R.string.result_coverage_ceiling_toofew
                else R.string.result_coverage_toofew,
            )
            view.setTextColor(ContextCompat.getColor(this, R.color.precision_low))
            return
        }
        val percent = Summaries.coveragePercent(g.lat, points)
        val grade = when {
            percent < COVERAGE_LOW -> Grade.LOW
            percent < COVERAGE_MID -> Grade.MID
            else -> Grade.UNKNOWN
        }
        val textRes = when {
            ceilingOnly && grade == Grade.LOW -> R.string.result_coverage_ceiling_low
            ceilingOnly && grade == Grade.MID -> R.string.result_coverage_ceiling_mid
            grade == Grade.LOW -> R.string.result_coverage_low
            grade == Grade.MID -> R.string.result_coverage_mid
            else -> R.string.result_coverage_ok
        }
        view.text = getString(textRes, percent)
        view.setTextColor(ContextCompat.getColor(this, grade.colorRes()))
    }

    /**
     * 全年曲线的缓存键。
     *
     * **必须含纬度、经度与时区**：它们只影响曲线、不影响界面状态，但恰恰是结果页能改的三样
     * （元信息旁的「编辑」），漏掉就会出现「改了地点、主结果卡已经是新坐标、曲线还是旧城市」。
     * 挡在键里的只有与曲线无关的日期档，这样来回点日期药丸不必重算 365 天。
     */
    private fun curveKey(g: GroupRecord, mode: CalcMode, year: Int): String = buildString {
        append(mode.name).append('|').append(year)
        append('|').append(g.lat).append(',').append(g.lon).append(',').append(g.zoneId)
        for (p in g.external) append('|').append(p.az).append(',').append(p.el).append(',').append(p.gapAfter)
        append('#')
        for (p in g.ceiling) append('|').append(p.az).append(',').append(p.el)
    }

    private fun renderComputed(g: GroupRecord, date: LocalDate, computed: Computed) {
        val d = computed.daily
        binding.mainLabel.text = getString(R.string.result_main_label, dateLabel(date), modeLabel())
        binding.mainValue.text = Format.durationLong(resources, d.directMinutes)
        binding.gapNote.isVisible = d.allFromGap
        bindCoverageNote(g)
        val sunrise = d.sunriseMinute
        binding.sunLine.text = when {
            sunrise == null && d.directMinutes == 0 -> getString(R.string.result_polar_night)
            sunrise == null -> getString(R.string.result_polar_day)
            else -> getString(
                R.string.result_sunrise_sunset,
                Format.clockMinute(sunrise),
                Format.clockMinute(d.sunsetMinute ?: 0),
            )
        }
        binding.dayTimeline.submit(d.sunriseMinute, d.sunsetMinute, d.visibleIntervals)

        binding.gbValue.text = Format.durationLong(resources, computed.gbWindowMinutes)

        val year = LocalDate.now(ZoneId.of(g.zoneId)).year
        val marker = if (date.year == year) date.dayOfYear - 1 else -1
        binding.yearCurve.submit(computed.curve, marker)
    }

    // ---------------- 点列 ----------------

    private fun rebuildPoints(g: GroupRecord) {
        val rows = ArrayList<PointRows.Row>(g.external.size + g.ceiling.size)
        g.external.forEachIndexed { i, p ->
            rows.add(
                PointRows.Row(
                    region = Region.EXTERNAL,
                    index = i,
                    point = p,
                    segViaHorizon = i > 0 && g.external[i - 1].gapAfter,
                ),
            )
        }
        g.ceiling.forEachIndexed { i, p ->
            rows.add(PointRows.Row(Region.CEILING, i, p, segViaHorizon = false))
        }
        pointRows.submit(rows)
        binding.pointsCount.text = getString(
            R.string.result_points_count, g.external.size, g.ceiling.size,
        )
    }

    /** 切换该点与左邻点（拍摄序前一点）的连线模式，作用于前一点的 gapAfter。 */
    private fun toggleSegment(externalIndex: Int) {
        val g = group ?: return
        if (externalIndex !in 1 until g.external.size) return
        val prev = g.external[externalIndex - 1]
        repository.setSegmentViaHorizon(g.id, Region.EXTERNAL, externalIndex - 1, !prev.gapAfter)
    }

    /**
     * 删单点（结果页的「删除」）：与采集页长按删除同一套语义，照片一并删掉。
     * 删点前先把 photoUri 取出来：删完这一点就从列表里没了，拿不到。
     */
    private fun deletePoint(region: Region, index: Int) {
        val g = group ?: return
        val photo = g.regionList(region).getOrNull(index)?.photoUri
        repository.deletePoint(g.id, region, index)
        lifecycleScope.launch { deletePointPhoto(this@ResultActivity, photo) }
    }

    /**
     * 点缩略图看大图：整屏黑底、按比例缩到屏幕内，点任意处或返回键关闭。
     *
     * 用全屏 [Dialog] 而不是新开 Activity：不必再登记一个界面、不必再给 apply 一份语言覆写，
     * 也不会把结果页的滚动位置丢掉。
     *
     * 解码按屏幕最长边降采样：原图可能四千万像素，整张解出来就是几百兆内存。
     */
    private fun showPhoto(uri: String) {
        val view = DialogPhotoBinding.inflate(layoutInflater)
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(view.root)
        view.photoRoot.setOnClickListener { dialog.dismiss() }
        dialog.show()

        val maxPx = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeDownsampled(this@ResultActivity, Uri.parse(uri), maxPx)
            }
            // 用户可能在解码完成前就关掉了
            if (!dialog.isShowing) return@launch
            if (bitmap == null) {
                dialog.dismiss()
                toast(getString(R.string.result_photo_missing))
            } else {
                view.photoView.setImageBitmap(bitmap)
            }
        }
    }

    // ---------------- 角度编辑（强行改值；允许无图点） ----------------

    /** 单点编辑：改写方位与仰角，照片与拍摄时间保留。 */
    private fun editPoint(region: Region, index: Int) {
        val g = group ?: return
        val point = g.regionList(region).getOrNull(index) ?: return
        val view = DialogPointAnglesBinding.inflate(layoutInflater)
        view.azInput.setText(String.format(Locale.US, "%.1f", point.az))
        view.elInput.setText(String.format(Locale.US, "%.1f", point.el))
        val regionTag = Format.region(resources, region)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.point_edit_title, index + 1, regionTag))
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val az = view.azInput.text.toString().trim().toDoubleOrNull()
                val el = view.elInput.text.toString().trim().toDoubleOrNull()
                // 非有限数必须挡在入口：NaN / Infinity 一旦进了模型，JSON 编码器每次都会抛，
                // 而落盘异常在仓库里是被吞掉的——用户会以为一直在保存，其实从这一刻起什么都没写下去
                // （`"NaN".toDoubleOrNull()` 实测返回的就是 NaN，不是 null）。
                // el 的范围判断顺带挡住 NaN 与 Infinity（NaN 与任何区间比较都是 false）；
                // az 没有范围要求（归一化交给仓库），所以单独查一次有限性。
                if (az == null || el == null || !az.isFinite() || el !in 0.0..90.0) {
                    toast(getString(R.string.point_edit_invalid))
                    return@setPositiveButton
                }
                repository.updatePointAngles(g.id, region, index, az, el)
            }
            .show()
    }

    /**
     * 批量编辑：两区各一段文本，逐行「方位角 仰角 [horizon]」。
     * horizon 表示该行与上一行（右边相邻点）之间的段经地平线，首行无上一行、标记会被拒绝。
     * 照片优先按角度匹配继承（重排行序也能对上），匹配不上且行数不变时按序继承；可先整体偏移再确认。
     */
    private fun editPointsBulk() {
        val g = group ?: return
        val view = DialogPointsBulkBinding.inflate(layoutInflater)
        val initial = mapOf(
            Region.EXTERNAL to renderBulk(g.external.toBulkRows()),
            Region.CEILING to renderBulk(g.ceiling.toBulkRows()),
        )
        val texts = HashMap(initial)
        var current = Region.EXTERNAL
        view.bulkInput.setText(texts.getValue(current))
        paintBulkChips(view, current)

        fun selectRegion(next: Region) {
            if (next == current) return
            texts[current] = view.bulkInput.text.toString()
            current = next
            view.bulkInput.setText(texts.getValue(current))
            paintBulkChips(view, current)
        }
        view.chipExternal.setOnClickListener { selectRegion(Region.EXTERNAL) }
        view.chipCeiling.setOnClickListener { selectRegion(Region.CEILING) }

        view.applyOffset.setOnClickListener {
            val (rows, errLine) = parseBulk(view.bulkInput.text.toString())
            if (rows == null) {
                toast(getString(R.string.bulk_edit_parse_error, errLine))
                return@setOnClickListener
            }
            val dAz = view.azOffsetInput.text.toString().trim().ifEmpty { "0" }.toDoubleOrNull()
            val dEl = view.elOffsetInput.text.toString().trim().ifEmpty { "0" }.toDoubleOrNull()
            if (dAz == null || dEl == null) {
                toast(getString(R.string.bulk_edit_offset_invalid))
                return@setOnClickListener
            }
            view.bulkInput.setText(
                renderBulk(
                    rows.map { r ->
                        BulkRow(
                            Angles.normalize360(r.az + dAz),
                            (r.el + dEl).coerceIn(0.0, 90.0),
                            r.horizon,
                        )
                    },
                ),
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.bulk_edit_title)
            .setView(view.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .show()
        // 替换默认监听：默认监听点完必关弹窗；这里被拒绝时保持打开，便于就地修正，且两区都不落库
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            texts[current] = view.bulkInput.text.toString()
            val pending = ArrayList<Pair<Region, List<PointRecord>>>()
            for (region in listOf(Region.EXTERNAL, Region.CEILING)) {
                val text = texts.getValue(region)
                if (text == initial.getValue(region)) continue
                val (rows, errLine) = parseBulk(text)
                if (rows == null) {
                    toast(getString(R.string.bulk_edit_parse_error, errLine))
                    return@setOnClickListener
                }
                if (rows.isNotEmpty() && rows[0].horizon) {
                    toast(getString(R.string.bulk_edit_first_horizon))
                    return@setOnClickListener
                }
                pending.add(region to rowsToRecords(rows, g.regionList(region)))
            }
            // 删光整个分区的文本等同于删掉该区全部角度数据，代价不对称：多打一次「确认」拦截。
            // 照片不受影响（setRegionPoints 只换点列，相册里的 JPEG 另行保存）。
            val clearing = pending.count { (region, points) ->
                points.isEmpty() && g.regionList(region).isNotEmpty()
            }
            val apply = {
                for ((region, points) in pending) {
                    repository.setRegionPoints(g.id, region, points)
                }
                dialog.dismiss()
            }
            if (clearing == 0) {
                apply()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.bulk_edit_clear_title)
                    .setMessage(getString(R.string.bulk_edit_clear_message, clearing))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.confirm) { _, _ -> apply() }
                    .show()
            }
        }
    }

    private fun paintBulkChips(view: DialogPointsBulkBinding, selected: Region) {
        val chips = mapOf(
            view.chipExternal to Region.EXTERNAL,
            view.chipCeiling to Region.CEILING,
        )
        for ((chip, region) in chips) {
            chip.setPillSelected(region == selected)
        }
    }

    /** 批量文本的一行；horizon 表示该行与上一行（右边相邻点）之间经地平线。 */
    private data class BulkRow(val az: Double, val el: Double, val horizon: Boolean)

    /** 点列转批量文本行：第 i 行的 horizon 取前一点的 gapAfter（两行之间的段）。 */
    private fun List<PointRecord>.toBulkRows(): List<BulkRow> =
        mapIndexed { i, p -> BulkRow(p.az, p.el, i > 0 && this[i - 1].gapAfter) }

    private fun renderBulk(rows: List<BulkRow>): String = rows.joinToString("\n") { r ->
        val base = String.format(Locale.US, "%.1f %.1f", r.az, r.el)
        if (r.horizon) "$base horizon" else base
    }

    /** 解析批量文本；失败时 first 为 null、second 为出错行号（1 基）。 */
    private fun parseBulk(text: String): Pair<List<BulkRow>?, Int> {
        val rows = ArrayList<BulkRow>()
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            val az = parts.getOrNull(0)?.toDoubleOrNull()
            val el = parts.getOrNull(1)?.toDoubleOrNull()
            // 非有限数与单点编辑同一道闸，理由见那里：NaN / Infinity 会让之后的落盘永久静默失败。
            // 这一路 el 没有范围判断（归一化与夹取在 rowsToRecords 里做），而 coerceIn 挡不住 NaN。
            if (az == null || el == null || !az.isFinite() || !el.isFinite() || parts.size > 3) {
                return null to (i + 1)
            }
            val horizon = when {
                parts.size == 2 -> false
                else -> when (parts[2].lowercase()) {
                    "horizon", "h", "0" -> true
                    else -> return null to (i + 1)
                }
            }
            rows.add(BulkRow(az, el, horizon))
        }
        return rows to 0
    }

    /**
     * 文本行转点记录：gapAfter 取「下一行是否标 horizon」（该点与其右边相邻点之间的段）；
     * 照片与拍摄时间优先按 (az, el) 精确匹配旧点继承（重排行序也能对上），
     * 无法一一匹配时退回「行数不变按序继承」，增删行则为无图点。
     */
    private fun rowsToRecords(rows: List<BulkRow>, old: List<PointRecord>): List<PointRecord> {
        val angles = rows.map { Angles.normalize360(it.az) to it.el.coerceIn(0.0, 90.0) }
        val matched = matchOldByAngles(angles, old)
        val byOrder = rows.size == old.size
        return rows.mapIndexed { i, _ ->
            val (az, el) = angles[i]
            val source = matched?.get(i) ?: if (byOrder) old[i] else null
            val base = source?.copy(az = az, el = el) ?: PointRecord(az, el)
            base.copy(gapAfter = i + 1 < rows.size && rows[i + 1].horizon)
        }
    }

    /** 全部行都能与旧点按 (az, el) 一一对应时返回「行号 → 旧点」映射；否则 null。 */
    private fun matchOldByAngles(
        angles: List<Pair<Double, Double>>,
        old: List<PointRecord>,
    ): Map<Int, PointRecord>? {
        if (angles.size != old.size) return null
        val used = BooleanArray(old.size)
        val result = HashMap<Int, PointRecord>()
        angles.forEachIndexed { i, (az, el) ->
            val hit = old.indices.firstOrNull { j ->
                !used[j] && abs(old[j].az - az) < 1e-6 && abs(old[j].el - el) < 1e-6
            } ?: return null
            used[hit] = true
            result[i] = old[hit]
        }
        return result
    }

    // ---------------- 导出与续拍 ----------------

    private fun export(csv: Boolean) {
        val g = group ?: return
        if (g.external.isEmpty() && g.ceiling.isEmpty()) {
            toast(getString(R.string.result_no_data))
            return
        }
        val d = daily ?: run {
            toast(getString(R.string.result_computing))
            return
        }
        val date = selectedDateValue ?: selectedDate(g)
        binding.exportCsvButton.isEnabled = false
        binding.exportImageButton.isEnabled = false
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (csv) {
                    Exporter.exportCsv(this@ResultActivity, g, date, d, modeLabel())
                } else {
                    Exporter.exportImage(
                        this@ResultActivity, g, dateLabel(date), modeLabel(),
                        d, winterMinutes, gbWindowMinutes,
                    )
                }
            }
            binding.exportCsvButton.isEnabled = true
            binding.exportImageButton.isEnabled = true
            if (outcome.location != null) {
                toast(getString(R.string.result_export_done, outcome.location))
            } else {
                toast(getString(R.string.result_export_failed))
            }
        }
    }

    private fun openCapture() {
        val g = group ?: return
        startActivity(
            Intent(this, CaptureActivity::class.java).putExtra(Extras.GROUP_ID, g.id),
        )
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 主半圆覆盖率低于此值（%）判为红色警告。 */
        const val COVERAGE_LOW = 60

        /** 低于此值（%）判为黄色提醒，更高则不着色。 */
        const val COVERAGE_MID = 85
    }
}