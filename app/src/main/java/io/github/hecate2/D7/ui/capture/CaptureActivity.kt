package io.github.hecate2.D7.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.animation.ValueAnimator
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.TextView
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
import io.github.hecate2.D7.camera.CameraController
import io.github.hecate2.D7.camera.PhotoMeta
import io.github.hecate2.D7.camera.PhotoStore
import io.github.hecate2.D7.core.Angles
import io.github.hecate2.D7.data.GroupRecord
import io.github.hecate2.D7.data.GroupRepository
import io.github.hecate2.D7.data.PointRecord
import io.github.hecate2.D7.data.Region
import io.github.hecate2.D7.databinding.ActivityCaptureBinding
import io.github.hecate2.D7.sensor.LocationProvider
import io.github.hecate2.D7.sensor.OrientationSensor
import io.github.hecate2.D7.sensor.Pose
import io.github.hecate2.D7.ui.CaptureLine
import io.github.hecate2.D7.ui.Extras
import io.github.hecate2.D7.ui.Grade
import io.github.hecate2.D7.ui.Settings
import io.github.hecate2.D7.ui.result.ResultActivity
import io.github.hecate2.D7.ui.colorRes
import io.github.hecate2.D7.ui.setPillSelected
import io.github.hecate2.D7.ui.setToggleTint
import io.github.hecate2.D7.util.Format
import io.github.hecate2.D7.util.deletePointPhoto
import io.github.hecate2.D7.util.Locales
import io.github.hecate2.D7.util.applyKeepScreenOn
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 采集页：取景器上直接画出太阳参考弧与已拍点连线。
 *
 * 快门 450 毫秒阈值区分短按（直接连线）与长按（经地平线推断段，天花板区不支持）；
 * 「不拍照」药丸开启时快门只落角度点、不存照片（photoUri 为空，下游已容错）；
 * 删除键长按生效，按下即锁定「十字线右侧、离当前方位最近」的一个候选点，按住只删一个；
 * 完成键跳转结果页。读数取融合瞬时值记录，显示值做轻度低通。
 */
class CaptureActivity : ComponentActivity() {

    companion object {
        private const val LONG_PRESS_MS = 450L
        private const val POSE_THROTTLE_MS = 30L

        /** 自动对焦的重复间隔：取景中每隔几秒重新对一次中心，兼顾耗电与“始终合焦”。 */
        private const val AUTO_FOCUS_INTERVAL_MS = 3000L

        /** 静置漂移的采样间隔（毫秒）：姿态每秒数十次，逐样本存窗没有额外价值。 */
        private const val DRIFT_SAMPLE_MS = 250L

        /** 静置漂移的采样窗口长度，约 6 秒。 */
        private const val DRIFT_SAMPLE_COUNT = 24

        /** 漂移样本少于这个数（约 3 秒）不作判定。 */
        private const val DRIFT_MIN_SAMPLES = 12

        // 读数精度（抖动）：最近窗口内极差的阈值与档位
        private const val JITTER_WINDOW = 24
        private const val JITTER_STEADY_DEG = 0.8
        private const val JITTER_OK_DEG = 2.5
        private const val LEVEL_UNKNOWN = -1
        private const val LEVEL_LOW = 1
        private const val LEVEL_MEDIUM = 2
        private const val LEVEL_HIGH = 3
    }

    private lateinit var binding: ActivityCaptureBinding
    private val repository by lazy { GroupRepository.get(this) }
    private lateinit var settings: Settings
    private lateinit var photoStore: PhotoStore
    private lateinit var camera: CameraController
    private var orientation: OrientationSensor? = null
    private var sensorRunning = false

    private var groupId: String = ""
    private var region = Region.EXTERNAL
    private var regionPoints: List<PointRecord> = emptyList()
    private var latestPose: Pose? = null
    private var lastAccuracy = OrientationSensor.ACCURACY_UNKNOWN
    private var lastUiAt = 0L
    private var capturing = false

    /** 手动对焦次数（仅短按触发），供仪器测试断言手势确实发起了对焦。 */
    private var focusActions = 0

    /** 快门发起拍照的次数（不管成没成），供仪器测试断言「不拍照」开关是否真的切断了拍照链路。 */
    private var photoAttempts = 0

    /** 删除键短按提示弹出的次数，供仪器测试断言「短按不生效但有提示」。 */
    private var deleteHintCount = 0

    /** 手电筒当前是否点亮，与 [CameraController] 实际状态同步。 */
    private var torchOn = false

    /** 瞄准警告条当前是否显示（带滞回，避免阈值附近闪烁）。 */
    private var aimWarnShown = false

    /** 滚转角显示用的低通值（NaN 表示尚未初始化）。 */
    private var smoothRoll = Double.NaN

    private val jitterAz = ArrayDeque<Double>()
    private val jitterEl = ArrayDeque<Double>()
    private var jitterLevel = LEVEL_UNKNOWN

    /**
     * 静置漂移的采样窗与最近一次判定值。
     *
     * 见 [CompassCheck]：平台精度在本机上恒为「高」，只能自己测。漂移值刻意保留上一次
     * 的结果而不随设备一动就清零——否则人在走动时药丸会掉回「高」，那还是一句谎话；
     * 粘住旧值则至少稳定，站稳三秒后自然被新数据覆盖。
     */
    private val driftAz = ArrayDeque<Double>()
    private var lastDriftSampleAt = 0L
    private var driftDeg: Double? = null

    /** 快门长按与删除长按共用的主线程延时队列。 */
    private val uiHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) startCamera() else showCameraNotice()
    }

    /** 界面语言按用户选择覆写（见 [Locales]）：不引 AppCompat 的做法，minSdk 26 起行为一致。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        applyKeepScreenOn(settings.keepScreenOn)

        groupId = intent.getStringExtra(Extras.GROUP_ID).orEmpty()
        val group = repository.get(groupId)
        if (group == null) {
            finish()
            return
        }

        photoStore = PhotoStore(this)
        camera = CameraController(this)

        binding.overlay.latDeg = group.lat
        binding.overlay.lonDeg = group.lon
        binding.overlay.focalPxProvider = { w, h ->
            camera.focalPx(w, h, binding.overlay.display?.rotation ?: Surface.ROTATION_0)
        }
        binding.coverageBar.latDeg = group.lat

        applyHemisphere(group)
        updatePlus180Ui()
        updateNoPhotoUi()
        applyLineSettings()
        updatePrecisionUi()
        setRegion(Region.EXTERNAL)
        setupShutter()
        setupDelete()
        setupTorch()
        setupFocus()
        startAutoFocusLoop()

        binding.backButton.setOnClickListener { finish() }
        binding.doneButton.setOnClickListener { openResult() }
        binding.plus180.setOnClickListener { togglePlus180() }
        binding.noPhoto.setOnClickListener { toggleNoPhoto() }
        binding.showLinesButton.setOnClickListener { showLinesDialog() }
        binding.chipExternal.setOnClickListener { setRegion(Region.EXTERNAL) }
        binding.chipCeiling.setOnClickListener { setRegion(Region.CEILING) }

        if (hasCameraPermission()) startCamera() else permissionLauncher.launch(requiredPermissions())
        observeGroup()
    }

    override fun onStart() {
        super.onStart()
        startSensor()
    }

    override fun onStop() {
        super.onStop()
        stopPressFeedback()
        orientation?.stop()
        // 传感器实例在 onStart/onStop 之间复用，测试用的仰角覆盖必须随停止清掉，
        // 否则下次 onStart 会带着它继续跑，掩盖真实读数
        orientation?.aimElevationOverrideDeg = null
        sensorRunning = false
        uiHandler.removeCallbacksAndMessages(null)
        jitterAz.clear()
        jitterEl.clear()
        jitterLevel = LEVEL_UNKNOWN
        driftAz.clear()
        driftDeg = null
        lastDriftSampleAt = 0L
    }

    override fun onDestroy() {
        super.onDestroy()
        camera.stop()
    }

    // ---------- 数据与界面同步 ----------

    private fun observeGroup() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.groups.collect { groups ->
                    val group = groups.firstOrNull { it.id == groupId }
                    if (group == null) {
                        finish()
                        return@collect
                    }
                    bindGroup(group)
                }
            }
        }
    }

    private fun bindGroup(group: GroupRecord) {
        binding.groupName.text = group.name
        // 地点能在结果页改（见 ui/GroupEditor.kt）：改完这一页的参考弧与此刻太阳都得跟着换，
        // 同值赋值由叠加层自己短路，不会白清缓存
        binding.overlay.latDeg = group.lat
        binding.overlay.lonDeg = group.lon
        regionPoints = group.regionList(region)
        binding.overlay.points = regionPoints
        binding.coverageBar.submitExternal(group.external)
        updateMeta()
    }

    private fun applyHemisphere(group: GroupRecord) {
        val lat = group.lat
        binding.hintText.text = when {
            abs(lat) <= 23.5 -> getString(R.string.capture_hint_tropic)
            lat >= 0 -> getString(R.string.capture_hint_north)
            else -> getString(R.string.capture_hint_south)
        }
        binding.dirLeft.text =
            getString(if (lat >= 0) R.string.capture_dir_last_east else R.string.capture_dir_last_west)
        binding.dirRight.text =
            getString(if (lat >= 0) R.string.capture_dir_first_west else R.string.capture_dir_first_east)
        binding.coverageLabel.text = getString(
            R.string.capture_coverage,
            getString(if (lat >= 0) R.string.capture_coverage_north else R.string.capture_coverage_south),
        )
    }

    private fun setRegion(next: Region) {
        region = next
        val external = next == Region.EXTERNAL
        binding.chipExternal.setBackgroundResource(
            if (external) R.drawable.bg_chip_on else R.drawable.bg_chip_off,
        )
        binding.chipExternal.setTextColor(
            ContextCompat.getColor(this, if (external) R.color.ink else R.color.paper),
        )
        binding.chipCeiling.setBackgroundResource(
            if (external) R.drawable.bg_chip_off else R.drawable.bg_chip_on,
        )
        binding.chipCeiling.setTextColor(
            ContextCompat.getColor(this, if (external) R.color.paper else R.color.ink),
        )
        regionPoints = repository.get(groupId)?.regionList(next) ?: emptyList()
        binding.overlay.points = regionPoints
        // 遮挡填充跟着分区换边：外部区楼挡的是折线以下，天花板区窗框挡的是折线以上
        binding.overlay.fillAbove = next == Region.CEILING
    }

    // ---------- 姿态 ----------

    private fun startSensor() {
        if (orientation == null) {
            val group = repository.get(groupId) ?: return
            orientation = OrientationSensor(
                context = this,
                declinationDeg = {
                    LocationProvider.declination(
                        group.lat,
                        group.lon,
                        group.altitude,
                        System.currentTimeMillis(),
                    )
                },
                displayRotation = { binding.overlay.display?.rotation ?: Surface.ROTATION_0 },
                onPose = ::onPose,
            )
        }
        val sensor = orientation ?: return
        if (!sensor.available) {
            if (!binding.notice.isVisible) showNotice(getString(R.string.capture_sensor_missing), false)
            return
        }
        if (!sensorRunning) sensorRunning = sensor.start()
    }

    private fun onPose(pose: Pose) {
        latestPose = pose
        sampleJitter(pose)
        if (pose.accuracy != lastAccuracy) {
            lastAccuracy = pose.accuracy
            updateCompassUi()
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastUiAt < POSE_THROTTLE_MS) return
        lastUiAt = now
        sampleDrift(pose, now)
        val level = computeJitterLevel()
        if (level != jitterLevel) {
            jitterLevel = level
            updateJitterUi()
        }
        updateCompassUi()
        updateReading(pose)
        updateAimWarning(pose)
        binding.overlay.pose = pose
        binding.overlay.aimAzDeg = aimAz(pose, smoothed = true)
        binding.overlay.invalidate()
    }

    /**
     * 仅供仪器测试：锁定瞄准仰角（度），使模拟器的固定姿态也能测到手势。
     *
     * 模拟器的合成姿态约为 -4.7 度（略微下倾），会被 [AimGuard] 当成瞄地面拒绝记录；
     * 真机竖持朝楼顶时仰角为正，走不到那个分支，故生产逻辑不动，只给测试留个口子。
     * 传 null 恢复真实读数。
     *
     * 返回是否真的设上——传感器在 onStart 才创建，调用过早会落在 null 上，
     * 调用方据此重试即可，不必自己猜时机。
     */
    @androidx.annotation.VisibleForTesting
    fun setAimElevationForTest(elevationDeg: Double?): Boolean {
        val sensor = orientation ?: return false
        sensor.aimElevationOverrideDeg = elevationDeg
        return true
    }

    /**
     * 瞄准低于地平线时常驻红条。阈值与滞回见 [AimGuard]。
     */
    private fun updateAimWarning(pose: Pose) {
        val el = aimEl(pose, smoothed = true)
        val on = AimGuard.shouldWarn(el, aimWarnShown)
        if (on != aimWarnShown) {
            aimWarnShown = on
            binding.aimWarn.isVisible = on
        }
    }

    /**
     * 显示/记录的方位角：姿态读数为屏幕外法线（机身朝向读数），
     * +180° 药丸默认叠加后即后摄视轴方向（十字线所指）。
     */
    private fun aimAz(pose: Pose, smoothed: Boolean): Double {
        val base = if (smoothed) pose.smoothAzDeg else pose.frontAzDeg
        return Angles.normalize360(if (settings.plus180) base + 180.0 else base)
    }

    private fun aimEl(pose: Pose, smoothed: Boolean): Double {
        val base = if (smoothed) pose.smoothElDeg else pose.frontElDeg
        return if (settings.plus180) -base else base
    }

    private fun updateReading(pose: Pose) {
        binding.readingText.text = getString(
            R.string.capture_reading,
            Format.degree(aimEl(pose, smoothed = true)),
            Format.degree(aimAz(pose, smoothed = true)),
        )
        smoothRoll = if (smoothRoll.isNaN()) pose.rollDeg else smoothRoll * 0.7 + pose.rollDeg * 0.3
        binding.rollText.text = getString(R.string.capture_roll, Format.degree(smoothRoll))
    }

    private fun updateMeta() {
        val group = repository.get(groupId) ?: return
        binding.metaText.text = getString(
            R.string.capture_meta,
            Format.coordinate(group.lat, group.lon),
        )
    }

    // ---------- 精度指示（罗盘校准 + 读数抖动） ----------

    /** 把每个原始样本的机身朝向角放进滑动窗口，供抖动评估。 */
    private fun sampleJitter(pose: Pose) {
        jitterAz.addLast(pose.frontAzDeg)
        jitterEl.addLast(pose.frontElDeg)
        while (jitterAz.size > JITTER_WINDOW) jitterAz.removeFirst()
        while (jitterEl.size > JITTER_WINDOW) jitterEl.removeFirst()
    }

    /** 窗口内方位/仰角相对最新样点的极差（度）取大者定档；样本不足返回未知档。方位差走短弧以处理环绕。 */
    private fun computeJitterLevel(): Int {
        if (jitterAz.size < JITTER_WINDOW) return LEVEL_UNKNOWN
        val baseAz = jitterAz.last()
        val baseEl = jitterEl.last()
        var azMin = 0.0
        var azMax = 0.0
        var elMin = 0.0
        var elMax = 0.0
        for (i in jitterAz.indices) {
            val dAz = Angles.shortArcDelta(baseAz, jitterAz.elementAt(i))
            if (dAz < azMin) azMin = dAz
            if (dAz > azMax) azMax = dAz
            val dEl = jitterEl.elementAt(i) - baseEl
            if (dEl < elMin) elMin = dEl
            if (dEl > elMax) elMax = dEl
        }
        val jitter = maxOf(azMax - azMin, elMax - elMin)
        return when {
            jitter <= JITTER_STEADY_DEG -> LEVEL_HIGH
            jitter <= JITTER_OK_DEG -> LEVEL_MEDIUM
            else -> LEVEL_LOW
        }
    }

    /** 刷新两枚精度药丸：罗盘校准取定档结果，读数精度取抖动档位。 */
    private fun updatePrecisionUi() {
        updateCompassUi()
        updateJitterUi()
    }

    /**
     * 罗盘校准药丸：平台精度与静置漂移取较差的一档（见 [CompassCheck]）。
     *
     * 真正要紧的是让它能变。本机 HAL 恒定上报「高」时，这一枚药丸过去从头到尾都是绿的，
     * 用户只能当装饰；漂移这一路补上它才重新有信息量。
     */
    private fun updateCompassUi() {
        val grade = CompassCheck.grade(lastAccuracy, driftDeg)
        paintPill(
            binding.calibPill,
            R.string.capture_calib_label,
            when (grade) {
                Grade.GOOD -> R.string.capture_calib_high
                Grade.MID -> R.string.capture_calib_mid
                Grade.LOW -> R.string.capture_calib_low
                Grade.UNKNOWN -> R.string.capture_calib_unknown
            },
            grade.colorRes(),
        )
    }

    /** 读数精度药丸：最近窗口内读数的极差定档。 */
    private fun updateJitterUi() {
        val jitterGrade = when (jitterLevel) {
            LEVEL_HIGH -> Grade.GOOD
            LEVEL_MEDIUM -> Grade.MID
            LEVEL_LOW -> Grade.LOW
            else -> Grade.UNKNOWN
        }
        paintPill(
            binding.jitterPill,
            R.string.capture_jitter_label,
            when (jitterGrade) {
                Grade.GOOD -> R.string.capture_jitter_high
                Grade.MID -> R.string.capture_jitter_mid
                Grade.LOW -> R.string.capture_jitter_low
                Grade.UNKNOWN -> R.string.capture_jitter_unknown
            },
            jitterGrade.colorRes(),
        )
    }

    /**
     * 静置漂移采样：设备基本不动（读数抖动已达中或更好）时，每 [DRIFT_SAMPLE_MS] 收一个
     * 方位角，窗口满 [DRIFT_SAMPLE_COUNT] 个后取出相对最新样本的最大偏移。
     *
     * 人拿着手机转一圈与罗盘在飘，从方位角上根本分不开，所以「没动」是前提；一动就清空
     * 窗口，已算出的漂移值则按 [driftDeg] 的说明粘住。
     */
    private fun sampleDrift(pose: Pose, now: Long) {
        if (jitterLevel == LEVEL_LOW) {
            driftAz.clear()
            lastDriftSampleAt = 0L
            return
        }
        if (now - lastDriftSampleAt < DRIFT_SAMPLE_MS) return
        lastDriftSampleAt = now
        driftAz.addLast(pose.frontAzDeg)
        while (driftAz.size > DRIFT_SAMPLE_COUNT) driftAz.removeFirst()
        if (driftAz.size < DRIFT_MIN_SAMPLES) return
        val newest = driftAz.last()
        var max = 0.0
        for (i in driftAz.indices) {
            val d = abs(Angles.shortArcDelta(newest, driftAz.elementAt(i)))
            if (d > max) max = d
        }
        driftDeg = max
    }

    /** 药丸文案 = 标签 + 空格 + 档位措辞。拆成两条资源是为了让每种语言各自挑最短的写法。 */
    private fun paintPill(view: TextView, labelRes: Int, valueRes: Int, colorRes: Int) {
        view.text = getString(labelRes) + " " + getString(valueRes)
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    // ---------- 按钮手势（快门 / 删除键 / 对焦键共用）与快门动作 ----------

    /**
     * 按住快门时的进度动画：450ms 走完即触发长按记录。
     * 动画帧只改一个 float，overlay 收到后 invalidate，不做额外测量与分配。
     */
    private val pressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = LONG_PRESS_MS
        addUpdateListener { binding.overlay.pressProgress = it.animatedValue as Float }
    }

    /**
     * 给一个按钮装「短按 / 长按 / 滑出取消」三态手势。三个相邻按钮共用这一份，
     * 免得各有各的手感（曾经快门、删除键、对焦键各写一遍，改一处忘两处）。
     *
     * 按下即压暗并开始计时；按住满 [LONG_PRESS_MS] 就地触发 [onLongPress]，此后按多久都不再触发；
     * 抬手时长按没触发过，就按实际按住时长走 [onShortPress]；手指滑出按钮即整场作废，双方都不触发。
     *
     * @param arm 按下那一刻的准入检查：返回 false 表示本次只留按压反馈，不计时也不响应抬手
     *   （删除键右侧没有可删的点时就是这样）。
     * @param withProgress 是否连带准星进度环与提示文字那套按住反馈，只有快门要。
     */
    private fun bindPressGesture(
        view: View,
        onLongPress: () -> Unit,
        onShortPress: (heldMillis: Long) -> Unit = {},
        arm: () -> Boolean = { true },
        withProgress: Boolean = false,
    ) {
        var downAt = 0L
        var canceled = false
        var longFired = false
        val longPress = Runnable {
            if (canceled) return@Runnable
            longFired = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }
        val cancelPress = {
            canceled = true
            view.alpha = 1f
            uiHandler.removeCallbacks(longPress)
            if (withProgress) stopPressFeedback()
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis()
                    canceled = false
                    longFired = false
                    v.alpha = 0.6f
                    if (withProgress) startPressFeedback()
                    if (arm()) uiHandler.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!canceled && !insideView(v, event)) cancelPress()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    uiHandler.removeCallbacks(longPress)
                    if (withProgress) stopPressFeedback()
                    if (!canceled) {
                        // 自行处理了按下/抬起，故需补一次 performClick，
                        // 否则 TalkBack 与开关控制等辅助服务认为该按钮不可用
                        v.performClick()
                        // 长按已在阈值处触发过；这里只处理还没触发的那次短按
                        if (!longFired) onShortPress(SystemClock.uptimeMillis() - downAt)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelPress()
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 快门手势：短按在抬手时记录连线点；长按在按住满 [LONG_PRESS_MS] 时就地记录经地平线推断的
     * 空隙点，之后无论按多久都不再记录，抬手也不补记——必须松手再按才算下一次。
     * 按住期间手指滑出按钮即取消本次操作：未到阈值时什么也不记。
     */
    private fun setupShutter() = bindPressGesture(
        view = binding.shutter,
        withProgress = true,
        onLongPress = { onShutter(true) },
        // 长按已在阈值处记过点。计时器偶尔会被主线程拖过阈值才跑到，此时抬手仍按实际
        // 按住时长判定，免得把一次长按降级成直接连线的短按。
        onShortPress = { held -> onShutter(held >= LONG_PRESS_MS) },
    )

    /** 事件坐标是否仍在视图范围内（滑出按钮即取消本次操作）。 */
    private fun insideView(view: View, event: MotionEvent): Boolean =
        event.x >= 0f && event.y >= 0f && event.x <= view.width && event.y <= view.height

    /** 按下即给反馈：准星下方提示 + 准星进度环 + 快门转圈。 */
    private fun startPressFeedback() {
        binding.pressHint.isVisible = true
        binding.shutterProgress.isVisible = true
        pressAnimator.start()
    }

    /** 松手/滑出/被打断：收掉全部按住反馈，不留下半截进度环。 */
    private fun stopPressFeedback() {
        pressAnimator.cancel()
        binding.overlay.pressProgress = Float.NaN
        binding.pressHint.isVisible = false
        binding.shutterProgress.isVisible = false
    }

    private fun onShutter(longPress: Boolean) {
        if (capturing) return
        val group = repository.get(groupId) ?: return
        if (region == Region.CEILING && longPress) {
            toast(R.string.capture_ceiling_longpress)
            return
        }
        val pose = latestPose
        if (pose == null) {
            toast(R.string.capture_no_pose)
            return
        }
        // 记录瞬时融合值；仰角裁到需求域 0..90
        val rawEl = aimEl(pose, smoothed = false)
        // 瞄到地面时拒绝记录：负仰角若直接夹到 0 会伪装成「贴地的墙」，
        // 数据里看不出这一点本来是错的，且 coerceIn 之后无法复原。
        if (!AimGuard.canCapture(rawEl)) {
            toast(R.string.capture_below_horizon)
            return
        }
        val az = aimAz(pose, smoothed = false)
        val el = rawEl.coerceIn(0.0, 90.0)
        val takenAt = System.currentTimeMillis()

        // 「不拍照」模式：只落角度点，photoUri 留空。appendPoint 本身是同步的（落盘排到
        // 单线程写队列），所以这条路径不进协程、不锁快门、也不必打断在飞的定时对焦。
        if (settings.noPhoto) {
            repository.appendPoint(
                groupId,
                region,
                PointRecord(az = az, el = el, photoUri = null, takenAt = takenAt),
                viaHorizon = longPress,
            )
            return
        }

        val seq = group.regionList(region).size + 1
        val meta = PhotoMeta(
            groupName = group.name,
            photoFolder = repository.photoFolderFor(group),
            region = region,
            seq = seq,
            azDeg = az,
            elDeg = el,
            gapAfter = longPress,
            lat = group.lat,
            lon = group.lon,
            takenAt = takenAt,
        )
        capturing = true
        binding.shutter.alpha = 0.4f
        lifecycleScope.launch {
            // 快门优先：先掐掉在飞的定时对焦，否则拍照会排在它后面等
            camera.cancelFocusMetering()
            val imageCapture = camera.imageCapture
            photoAttempts++
            val uri = if (imageCapture != null) photoStore.capture(imageCapture, meta) else null
            repository.appendPoint(
                groupId,
                region,
                PointRecord(az = az, el = el, photoUri = uri?.toString(), takenAt = takenAt),
                viaHorizon = longPress,
            )
            capturing = false
            binding.shutter.alpha = 1f
            if (uri == null && imageCapture != null) toast(R.string.capture_photo_failed)
        }
    }

    // ---------- 删除（长按删十字线右侧最近点） ----------

    /**
     * 删除键手势：长按删掉十字线右侧离当前方位最近的那个点。按下即锁定候选点，按住只删一个；
     * 右侧没有点时只给一句提示，不参与这次手势。
     */
    private fun setupDelete() {
        var candidate = -1
        bindPressGesture(
            view = binding.deleteButton,
            onLongPress = { deleteCandidate(candidate) },
            // 短按不删任何东西，但必须说一声「这是长按才生效的」——原先这句提示常驻在
            // 删除键下方，占掉一整行高度，现在改成按需弹一次。
            onShortPress = {
                deleteHintCount++
                toast(R.string.capture_delete_hint)
            },
            arm = {
                candidate = findDeleteCandidate()
                if (candidate < 0) toast(R.string.capture_delete_no_candidate)
                candidate >= 0
            },
        )
    }

    /**
     * 删掉一个拍摄点连带它的照片。删点不可撤销，故另给文字确认（触觉由手势骨架统一给）。
     *
     * 文案分两档：这点带着照片就说照片一并删了，本来没有照片（「不拍照」模式、批量编辑新增的点）
     * 就只说删了点。原先一律写「照片保留在相册」，与删除实现正好相反。
     *
     * 删照片是否真成功不去回读：[util/MediaFiles.kt] 的 `deletePhoto` 对「本来就没有这条」
     * 与「没权限」都返回 0，分不开，报错必带假警报。宁可少说一句，也不说一句假的。
     */
    private fun deleteCandidate(index: Int) {
        if (index < 0) return
        // 先取照片再来删点：删完这一点就从列表里没了，拿不到它的 photoUri
        val photo = repository.get(groupId)?.regionList(region)?.getOrNull(index)?.photoUri
        repository.deletePoint(groupId, region, index)
        // 与结果页删单点同一套语义（见 util/MediaFiles.kt）
        lifecycleScope.launch { deletePointPhoto(this@CaptureActivity, photo) }
        val message = if (photo.isNullOrEmpty()) R.string.capture_deleted else R.string.capture_deleted_with_photo
        toast(getString(message, index + 1))
    }

    /** 当前分区内「十字线右侧、离当前方位最近」的点：顺时针角距落在 (0°, 180°) 内取最小者。 */
    private fun findDeleteCandidate(): Int {
        val group = repository.get(groupId) ?: return -1
        val pose = latestPose ?: return -1
        val azNow = aimAz(pose, smoothed = false)
        var best = -1
        var bestDelta = Double.MAX_VALUE
        group.regionList(region).forEachIndexed { index, point ->
            val delta = Angles.normalize360(point.az - azNow)
            if (delta > 1e-6 && delta < 180.0 - 1e-6 && delta < bestDelta) {
                bestDelta = delta
                best = index
            }
        }
        return best
    }

    // ---------- 闪光灯与对焦 ----------

    /**
     * 闪光灯键：单击切换。灯的初始状态由 [CameraController] 在绑定相机前从系统读到并复原，
     * 这里只负责之后的切换与着色。无闪光灯的机型整个位次隐藏。
     */
    private fun setupTorch() {
        binding.torchButton.setOnClickListener {
            torchOn = !torchOn
            camera.setTorch(torchOn)
            binding.torchButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            binding.torchButton.setToggleTint(torchOn)
        }
        binding.torchButton.setToggleTint(false)
    }

    /** 对焦键：短按立即对准取景中心对焦一次，长按开关自动对焦。 */
    private fun setupFocus() {
        bindPressGesture(
            view = binding.focusButton,
            onLongPress = { toggleAutoFocus() },
            onShortPress = { focusNow() },
        )
        binding.focusButton.setToggleTint(true)
    }

    /** 自动对焦循环：仅在前台跑（[repeatOnLifecycle]），关掉自动对焦时空转不动作。 */
    private fun startAutoFocusLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(AUTO_FOCUS_INTERVAL_MS)
                    val vf = binding.viewfinder
                    if (camera.autoFocusEnabled) camera.focusAtCenter(vf.width, vf.height)
                }
            }
        }
    }

    /** 短按对焦：只计手动这一次，定时循环不计入，免得测试断言被后台动作干扰。 */
    private fun focusNow() {
        focusActions++
        val vf = binding.viewfinder
        camera.focusAtCenter(vf.width, vf.height)
        binding.focusButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun toggleAutoFocus() {
        val next = !camera.autoFocusEnabled
        camera.setAutoFocusEnabled(next)
        binding.focusButton.setToggleTint(next)
    }

    /** 仅供仪器测试：自动对焦是否开启。 */
    @androidx.annotation.VisibleForTesting
    fun autoFocusEnabledForTest(): Boolean = camera.autoFocusEnabled

    /** 仅供仪器测试：手动对焦累计次数。 */
    @androidx.annotation.VisibleForTesting
    fun focusActionsForTest(): Int = focusActions

    /** 仅供仪器测试：闪光灯键是否可见（无闪光灯机型为 false）。 */
    @androidx.annotation.VisibleForTesting
    fun torchVisibleForTest(): Boolean = binding.torchSlot.isVisible

    /** 仅供仪器测试：「不拍照」是否开启。 */
    @androidx.annotation.VisibleForTesting
    fun noPhotoForTest(): Boolean = settings.noPhoto

    /** 仅供仪器测试：删除键短按提示的累计弹出次数。 */
    @androidx.annotation.VisibleForTesting
    fun deleteHintCountForTest(): Int = deleteHintCount

    /** 仅供仪器测试：快门发起拍照的累计次数。 */
    @androidx.annotation.VisibleForTesting
    fun photoAttemptsForTest(): Int = photoAttempts

    /** 仅供仪器测试：本机后摄是否有闪光灯。 */
    @androidx.annotation.VisibleForTesting
    fun hasFlashUnitForTest(): Boolean = camera.hasFlashUnit

    /** 仅供仪器测试：相机是否已绑定成功（闪光灯能力只在绑定后才有意义）。 */
    @androidx.annotation.VisibleForTesting
    fun cameraReadyForTest(): Boolean = camera.isBound

    /** 仅供仪器测试：强制指定本机有无闪光灯并重跑能力对齐；传 null 恢复真实值。 */
    @androidx.annotation.VisibleForTesting
    fun setFlashUnitOverrideForTest(hasFlash: Boolean?) {
        camera.flashUnitOverride = hasFlash
        applyCameraCapabilities()
    }

    // ---------- 设置与顶部按钮 ----------

    private fun togglePlus180() {
        settings.plus180 = !settings.plus180
        updatePlus180Ui()
        latestPose?.let { pose ->
            updateReading(pose)
            binding.overlay.aimAzDeg = aimAz(pose, smoothed = true)
            binding.overlay.invalidate()
        }
    }

    private fun updatePlus180Ui() {
        binding.plus180.setPillSelected(settings.plus180)
    }

    /**
     * 「不拍照」开关：切换后快门只记角度点、不存照片。
     *
     * 切换时给一句 Toast 而不是只靠药丸变色：这个模式的后果（相册里没有照片）在按下
     * 快门之前完全看不见，只用颜色表示的话，用户往往拍完半天才发现。
     */
    private fun toggleNoPhoto() {
        settings.noPhoto = !settings.noPhoto
        updateNoPhotoUi()
        binding.noPhoto.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        toast(if (settings.noPhoto) R.string.capture_no_photo_on else R.string.capture_no_photo_off)
    }

    private fun updateNoPhotoUi() {
        binding.noPhoto.setPillSelected(settings.noPhoto)
    }

    private fun applyLineSettings() {
        binding.overlay.show = settings.lines()
    }

    private fun showLinesDialog() {
        val lines = CaptureLine.entries
        val labels: Array<CharSequence> = Array(lines.size) { index ->
            val line = lines[index]
            val text = getString(line.labelRes)
            SpannableString(text).apply {
                // 多选对话框只收文案，文字着色只能靠 span，勾选与布局仍走平台实现
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(this@CaptureActivity, line.colorRes)),
                    0,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.lines_title)
            .setMultiChoiceItems(labels, BooleanArray(lines.size) { settings.isOn(lines[it]) }) { _, which, checked ->
                settings.setOn(lines[which], checked)
                applyLineSettings()
            }
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun openResult() {
        startActivity(Intent(this, ResultActivity::class.java).putExtra(Extras.GROUP_ID, groupId))
        finish()
    }

    // ---------- 权限与相机 ----------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        binding.notice.isVisible = false
        camera.start(this, binding.preview) { ok ->
            if (!ok) {
                if (!binding.notice.isVisible) showNotice(getString(R.string.capture_camera_failed), false)
            } else {
                applyCameraCapabilities()
            }
        }
    }

    /**
     * 相机就绪后对齐两个开关键：无闪光灯则隐藏手电筒位次；
     * 手电筒初始为绑定前读到的系统状态（[CameraController] 已经把它恢复回去了）。
     */
    private fun applyCameraCapabilities() {
        val hasFlash = camera.hasFlashUnit
        binding.torchSlot.isVisible = hasFlash
        torchOn = hasFlash && camera.torchStateBeforeBind == true
        binding.torchButton.setToggleTint(torchOn)
        binding.focusButton.setToggleTint(camera.autoFocusEnabled)
    }

    private fun showCameraNotice() {
        showNotice(getString(R.string.capture_camera_needed), true)
    }

    private fun showNotice(text: String, requestOnTap: Boolean) {
        binding.notice.text = text
        binding.notice.isVisible = true
        binding.notice.isClickable = requestOnTap
        binding.notice.setOnClickListener(
            if (requestOnTap) {
                View.OnClickListener { permissionLauncher.launch(requiredPermissions()) }
            } else {
                null
            },
        )
    }

    private fun toast(resId: Int) = toast(getString(resId))

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}