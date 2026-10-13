package io.github.hecate2.D7.core

import java.time.LocalDate
import java.time.Year
import java.time.ZoneId

/** 计算档：只算外部建筑（默认）、外部 + 天花板、只算天花板。 */
enum class CalcMode { EXTERNAL_ONLY, EXTERNAL_AND_CEILING, CEILING_ONLY }

/**
 * 某日的日照评估结果。分钟序号自当地 0 时起，日出日落为当地钟表时间分钟。
 *
 * @param sunriseMinute 日出（全天有光时为 null，界面显示「极昼」）
 * @param sunsetMinute 日落（全天有光时为 null）
 * @param directMinutes 当日直射日照分钟数
 * @param visibleIntervals 可见直射的分钟区间（闭区间）
 * @param allFromGap 全部可见分钟都来自经地平线推断段（楼间空隙）
 * @param daylightMinutes 当日白天分钟数（几何高度角高于 -0.833 度）
 */
data class DailySunlight(
    val date: LocalDate,
    val sunriseMinute: Int?,
    val sunsetMinute: Int?,
    val directMinutes: Int,
    val visibleIntervals: List<IntRange>,
    val allFromGap: Boolean,
    val daylightMinutes: Int,
)

/**
 * 日照评估：对一组天际线数据逐分钟采样太阳位置，判定可见直射。
 *
 * 可见判定使用几何高度角（不含折射）：太阳在地平线阈值之上、且满足计算档的全部约束。
 * 未拍到的方位由 [Skyline] 的未覆盖语义决定：外部列未拍到视为开阔，天花板列未拍到视为全遮挡；
 * 点列为空即「全周未拍到」，按同一规则退化（界面在未拍天花板时禁用涉及天花板的档位）。
 */
object SunlightEvaluator {

    const val MINUTES_PER_DAY = 1440

    /**
     * 一天的「当地墙钟分钟 → UTC 瞬时」映射。
     *
     * 夏令时切换日本地一天不一定有 24 小时（拨快只有 23 小时，拨回有 25 小时），
     * 所以不能拿「本地 0 时 + 分钟数 × 60 秒」推进：拨快日最后 60 个采样会落到次日
     * 0-1 点，把次日的日出算进今天；拨回日则把回拨重复的那一小时算两遍、并漏掉
     * 当天 23:00-23:59。
     *
     * 本类把采样严格定义在[当地钟表分钟]上，用 [java.time.zone.ZoneRules] 逐分钟求解：
     * 拨快日 02:00-02:59 根本不存在，返回 null 跳过（那天只有 1380 个有效分钟）；
     * 拨回日 02:00-02:59 出现两次，取较早偏移只算一次。一年只有两天走慢路径。
     *
     * 可见性为 internal 仅为让 [SunlightTest] 能直接断言分钟→瞬时映射：这个错不影响
     * [DailySunlight.daylightMinutes] 与 [DailySunlight.directMinutes]（切换都发生在深夜），
     * 但会让 [DailySunlight.sunriseMinute] / [DailySunlight.sunsetMinute] 与
     * [DailySunlight.visibleIntervals] 整体偏移一小时，界面与导出 CSV 都读错钟面时间。
     */
    internal class DayClock(date: LocalDate, private val zone: ZoneId) {
        // 取不带时区的午夜，不是 `date.atStartOfDay(zone)`。后者返回的是「那一天最早的合法当地时刻」，
        // 而在「当地 0 时正好是拨快缺口」的时区那一天，它给的是 01:00：2026 年的 America/Havana
        // （3 月 8 日）、America/Santiago（9 月 6 日）、Asia/Beirut（3 月 29 日）、Africa/Cairo
        // （4 月 24 日）都是 0 时直接跳到 1 时。于是整个钟面被整体挪前一小时、全天多出 60 个采样
        // 并溢出到次日，正是本类注释里声称要修掉的症状。那天的 00:00-00:59 也确实不存在，
        // 交给 getValidOffsets 逐分钟判空跳过（见 instantAt）。
        // 受影响的是哪些时区随 tzdb 版本变，所以这里不按名单判，只按钟面时间定义。
        private val localStart = date.atStartOfDay()
        private val dayStartMillis = localStart.atZone(zone).toInstant().toEpochMilli()

        /** 当日无夏令时切换：本地一天正好 24 小时，墙钟分钟与偏移一一对应。 */
        private val wholeDay =
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - dayStartMillis ==
                86_400_000L

        val startMillis: Long get() = dayStartMillis

        /**
         * 当地第 [wallClockMinute] 个钟表分钟（0 为当地 0 时）对应的 UTC 瞬时。
         * 该钟面时间落在拨快缺口里时返回 null。
         */
        fun instantAt(wallClockMinute: Int): Long? {
            if (wholeDay) return dayStartMillis + wallClockMinute * 60_000L
            val local = localStart.plusMinutes(wallClockMinute.toLong())
            // 缺口返回空数组、重叠返回两个偏移（较早的在首位），普通时返回单个
            val offsets = zone.rules.getValidOffsets(local)
            return if (offsets.isEmpty()) null else local.toInstant(offsets[0]).toEpochMilli()
        }
    }

    /**
     * 评估某日直射日照。
     *
     * @param stepMinutes 采样步长（分钟），默认逐分钟
     */
    fun evaluate(
        latDeg: Double,
        lonDeg: Double,
        zoneId: String,
        external: List<ShotPoint>,
        ceiling: List<ShotPoint>,
        mode: CalcMode,
        date: LocalDate,
        stepMinutes: Int = 1,
    ): DailySunlight {
        val clock = DayClock(date, ZoneId.of(zoneId))
        val externalSky = Skyline(external)
        val ceilingSky = Skyline(ceiling)
        val track = Solar.DayTrack.ofDayStart(clock.startMillis, latDeg, lonDeg)

        var daylight = 0
        var direct = 0
        var sampled = 0
        var firstUp: Int? = null
        var lastUp: Int? = null
        val visibleMinutes = ArrayList<Int>()
        val visibleAzimuths = ArrayList<Double>()

        var m = 0
        while (m < MINUTES_PER_DAY) {
            val instant = clock.instantAt(m)
            if (instant != null) {
                sampled += stepMinutes
                val pos = track.position(instant, refraction = false)
                if (pos.elevationDeg > Solar.SUNRISE_THRESHOLD_DEG) {
                    daylight += stepMinutes
                    if (firstUp == null) firstUp = m
                    lastUp = m
                    if (isVisible(pos, mode, externalSky, ceilingSky)) {
                        direct += stepMinutes
                        visibleMinutes.add(m)
                        visibleAzimuths.add(pos.azimuthDeg)
                    }
                }
            }
            m += stepMinutes
        }

        // 拨快日只有 1380 个有效分钟，极昼要按「全部采样都有光」判定而不是凑满 1440
        val polarDay = sampled > 0 && daylight >= sampled
        return DailySunlight(
            date = date,
            sunriseMinute = if (polarDay) null else firstUp,
            sunsetMinute = if (polarDay) null else lastUp?.plus(stepMinutes),
            directMinutes = direct,
            visibleIntervals = buildIntervals(visibleMinutes, stepMinutes),
            allFromGap = isAllFromGap(visibleMinutes, visibleAzimuths, externalSky),
            daylightMinutes = daylight,
        )
    }

    /**
     * 全年每日直射分钟数，按日期顺序。
     */
    fun yearlyCurve(
        latDeg: Double,
        lonDeg: Double,
        zoneId: String,
        external: List<ShotPoint>,
        ceiling: List<ShotPoint>,
        mode: CalcMode,
        year: Int,
        stepMinutes: Int = 1,
    ): List<Double> {
        val first = LocalDate.of(year, 1, 1)
        val result = ArrayList<Double>(Year.of(year).length())
        for (i in 0 until Year.of(year).length()) {
            result.add(
                evaluate(
                    latDeg, lonDeg, zoneId, external, ceiling, mode,
                    first.plusDays(i.toLong()), stepMinutes,
                ).directMinutes.toDouble()
            )
        }
        return result
    }

    /**
     * 指定日期在「真太阳时时间带」内的有效直射分钟数（用于 GB 50180 辅助卡）。
     *
     * @param fromSolarMinute 时间带起点（真太阳时分钟，8:00 为 480）
     * @param toSolarMinute 时间带终点（真太阳时分钟，16:00 为 960）
     */
    fun windowMinutes(
        latDeg: Double,
        lonDeg: Double,
        zoneId: String,
        external: List<ShotPoint>,
        ceiling: List<ShotPoint>,
        mode: CalcMode,
        date: LocalDate,
        fromSolarMinute: Int,
        toSolarMinute: Int,
        stepMinutes: Int = 1,
    ): Int {
        val clock = DayClock(date, ZoneId.of(zoneId))
        val externalSky = Skyline(external)
        val ceilingSky = Skyline(ceiling)
        val track = Solar.DayTrack.ofDayStart(clock.startMillis, latDeg, lonDeg)

        var count = 0
        var m = 0
        while (m < MINUTES_PER_DAY) {
            val instant = clock.instantAt(m)
            if (instant != null) {
                val solarMinute = track.trueSolarMinutes(instant)
                if (solarMinute >= fromSolarMinute && solarMinute < toSolarMinute) {
                    val pos = track.position(instant, refraction = false)
                    if (pos.elevationDeg > Solar.SUNRISE_THRESHOLD_DEG &&
                        isVisible(pos, mode, externalSky, ceilingSky)
                    ) {
                        count += stepMinutes
                    }
                }
            }
            m += stepMinutes
        }
        return count
    }

    /**
     * 可见判定。obstructionAt 是「天际线边缘仰角」：外部区太阳须高于边缘；天花板区太阳
     * 须低于边缘。未拍到（含整列为空）的方位返回 -∞，在外部区等价于开阔、在天花板区
     * 等价于全遮挡，与需求的两条未覆盖语义恰好吻合。
     */
    private fun isVisible(
        pos: SolarPosition,
        mode: CalcMode,
        externalSky: Skyline,
        ceilingSky: Skyline,
    ): Boolean = when (mode) {
        CalcMode.EXTERNAL_ONLY ->
            pos.elevationDeg > externalSky.obstructionAt(pos.azimuthDeg)

        CalcMode.CEILING_ONLY ->
            pos.elevationDeg < ceilingSky.obstructionAt(pos.azimuthDeg)

        CalcMode.EXTERNAL_AND_CEILING ->
            pos.elevationDeg > externalSky.obstructionAt(pos.azimuthDeg) &&
                pos.elevationDeg < ceilingSky.obstructionAt(pos.azimuthDeg)
    }

    private fun buildIntervals(minutes: List<Int>, stepMinutes: Int): List<IntRange> {
        if (minutes.isEmpty()) return emptyList()
        val intervals = ArrayList<IntRange>()
        var start = minutes[0]
        var prev = start
        for (i in 1 until minutes.size) {
            val cur = minutes[i]
            if (cur == prev + stepMinutes) {
                prev = cur
            } else {
                intervals.add(start..(prev + stepMinutes - 1))
                start = cur
                prev = cur
            }
        }
        intervals.add(start..(prev + stepMinutes - 1))
        return intervals
    }

    private fun isAllFromGap(
        visibleMinutes: List<Int>,
        visibleAzimuths: List<Double>,
        externalSky: Skyline,
    ): Boolean {
        if (visibleMinutes.isEmpty()) return false
        val gaps = externalSky.gapArcs()
        if (gaps.isEmpty()) return false
        return visibleAzimuths.all { az ->
            gaps.any { (start, end) -> Angles.shortArcContainsOpen(start, end, az) }
        }
    }
}