package io.github.hecate2.D7.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SunlightTest {

    private val bjLat = 39.9
    private val bjLon = 116.4
    private val bjZone = "Asia/Shanghai"
    private val winterSolstice = LocalDate.of(2026, 12, 21)

    private fun evaluateWinter(
        mode: CalcMode,
        external: List<ShotPoint>,
        ceiling: List<ShotPoint>,
    ): DailySunlight = SunlightEvaluator.evaluate(
        bjLat, bjLon, bjZone, external, ceiling, mode, winterSolstice,
    )

    @Test
    fun equatorEquinoxDayLengthIsTwelveHoursSeven() {
        val r = SunlightEvaluator.evaluate(
            0.1, 0.0, "UTC", emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, LocalDate.of(2026, 3, 20),
        )
        assertEquals(727.0, r.daylightMinutes.toDouble(), 8.0)
        assertEquals(r.daylightMinutes, r.directMinutes)
    }

    @Test
    fun polarNightGivesZero() {
        val r = SunlightEvaluator.evaluate(
            78.22, 15.65, "UTC", emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, winterSolstice,
        )
        assertEquals(0, r.directMinutes)
        assertEquals(0, r.daylightMinutes)
        assertNull(r.sunriseMinute)
        assertNull(r.sunsetMinute)
        assertTrue(r.visibleIntervals.isEmpty())
    }

    @Test
    fun polarDayGivesFullDay() {
        val r = SunlightEvaluator.evaluate(
            78.22, 15.65, "UTC", emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, LocalDate.of(2026, 6, 21),
        )
        assertEquals(SunlightEvaluator.MINUTES_PER_DAY, r.daylightMinutes)
        assertEquals(SunlightEvaluator.MINUTES_PER_DAY, r.directMinutes)
        assertNull(r.sunriseMinute)
    }

    @Test
    fun externalWallBlocksWholeDay() {
        // 南侧 45 度高的整面墙：冬至全天被挡
        val wall = listOf(ShotPoint(100.0, 45.0), ShotPoint(260.0, 45.0))
        val blocked = evaluateWinter(CalcMode.EXTERNAL_ONLY, wall, emptyList())
        assertEquals(0, blocked.directMinutes)
        assertTrue("白天分钟=${blocked.daylightMinutes}", blocked.daylightMinutes > 500)
    }

    @Test
    fun emptyExternalIsOpen() {
        val r = evaluateWinter(CalcMode.EXTERNAL_ONLY, emptyList(), emptyList())
        assertEquals(r.daylightMinutes, r.directMinutes)
        assertTrue(r.directMinutes > 500)
    }

    @Test
    fun emptyCeilingBlocksCeilingModes() {
        // 天花板列空 = 全周未拍到 = 全遮挡
        assertEquals(0, evaluateWinter(CalcMode.CEILING_ONLY, emptyList(), emptyList()).directMinutes)
        assertEquals(0, evaluateWinter(CalcMode.EXTERNAL_AND_CEILING, emptyList(), emptyList()).directMinutes)
    }

    @Test
    fun ceilingOnlyRestrictsToCoveredArc() {
        // 天花板 80 度平沿仅覆盖 170..190 度；外侧方位视为全遮挡
        val ceiling = listOf(ShotPoint(170.0, 80.0), ShotPoint(190.0, 80.0))
        val r = evaluateWinter(CalcMode.CEILING_ONLY, emptyList(), ceiling)
        assertTrue("直射分钟=${r.directMinutes}", r.directMinutes in 40..150)
        assertTrue(r.visibleIntervals.all { it.first > 600 && it.last < 900 })
    }

    @Test
    fun gapPunchThroughMarkedAllFromGap() {
        // 两栋 45 度高的楼，中间 170..190 度为长按产生的空隙
        val towers = listOf(
            ShotPoint(100.0, 45.0),
            ShotPoint(170.0, 45.0, viaHorizonAfter = true),
            ShotPoint(190.0, 45.0),
            ShotPoint(260.0, 45.0),
        )
        val r = evaluateWinter(CalcMode.EXTERNAL_ONLY, towers, emptyList())
        assertTrue("直射分钟=${r.directMinutes}", r.directMinutes in 40..150)
        assertTrue(r.allFromGap)
        assertTrue(r.visibleIntervals.isNotEmpty())
    }

    @Test
    fun withoutGapSameTowersBlockFully() {
        val towers = listOf(ShotPoint(100.0, 45.0), ShotPoint(260.0, 45.0))
        val r = evaluateWinter(CalcMode.EXTERNAL_ONLY, towers, emptyList())
        assertEquals(0, r.directMinutes)
        assertTrue(!r.allFromGap)
    }

    @Test
    fun southHemisphereWinterHasSun() {
        // 悉尼冬至是 6 月 21 日（南半球），白天约 9 小时 55 分，无遮挡应全为直射
        val winter = SunlightEvaluator.evaluate(
            -33.87, 151.21, "Australia/Sydney", emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, LocalDate.of(2026, 6, 21),
        )
        assertTrue("悉尼冬至白天=${winter.daylightMinutes}", winter.daylightMinutes in 550..650)
        assertEquals(winter.daylightMinutes, winter.directMinutes)

        // 12 月 21 日是南半球夏至，白天约 14 小时 25 分
        val summer = SunlightEvaluator.evaluate(
            -33.87, 151.21, "Australia/Sydney", emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, winterSolstice,
        )
        assertTrue("悉尼夏至白天=${summer.daylightMinutes}", summer.daylightMinutes in 800..900)
        assertEquals(summer.daylightMinutes, summer.directMinutes)
    }

    @Test
    fun windowMinutesRespectsTrueSolarTime() {
        // 大寒日真太阳时 8:00-16:00：无遮挡时应接近 480 分钟
        val r = SunlightEvaluator.windowMinutes(
            bjLat, bjLon, bjZone, emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, LocalDate.of(2026, 1, 20), 8 * 60, 16 * 60,
        )
        assertTrue("窗口内有效直射分钟=$r", r in 440..510)
    }

    @Test
    fun dstDayMapsEveryWallClockMinuteToItsOwnInstant() {
        // 悉尼 2026-10-04 拨快（23 小时日）与 2026-04-05 拨回（25 小时日）：
        // 采样必须严格贴着当地钟表分钟铺开，不多不少。
        // 修复前是「本地 0 时 + n 分钟」，拨快日会把次日 00:00-00:59 算进来，
        // 拨回日则漏掉当天 23:00-23:59 并把回拨重复的那一小时算两遍。
        val zone = ZoneId.of("Australia/Sydney")

        // 拨快日：02:00-02:59 这个钟面时间不存在，那一天只有 1380 个有效分钟，
        // 最后一个有效瞬时必须是当地 23:59，而不是次日的 00:59。
        val gapDay = LocalDate.of(2026, 10, 4)
        val gapClock = SunlightEvaluator.DayClock(gapDay, zone)
        val gapInstants = (0 until SunlightEvaluator.MINUTES_PER_DAY)
            .map { gapClock.instantAt(it) }
        assertEquals("拨快日只剩 1380 个有效分钟", 1380, gapInstants.count { it != null })
        assertEquals("缺口分钟的钟面时间不存在", 60, (120 until 180).count { gapClock.instantAt(it) == null })
        assertEquals(
            "拨快日最后一个瞬时应是当地 23:59，不是次日 00:59",
            gapDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 60_000L,
            gapInstants.last(),
        )
        assertEquals(
            "拨快日的瞬时应互不重复", 1380, gapInstants.filterNotNull().toSet().size,
        )

        // 拨回日：02:00-02:59 出现两次，每个钟表分钟只算一次（取较早偏移），
        // 最后一个瞬时必须是当地 23:59，即次日本地 0 时前一分。
        val overlapDay = LocalDate.of(2026, 4, 5)
        val overlapClock = SunlightEvaluator.DayClock(overlapDay, zone)
        val overlapInstants = (0 until SunlightEvaluator.MINUTES_PER_DAY)
            .map { overlapClock.instantAt(it) }
        assertTrue("拨回日每个钟表分钟都有瞬时", overlapInstants.none { it == null })
        assertEquals(
            "拨回日最后一个瞬时应是当地 23:59",
            overlapDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 60_000L,
            overlapInstants.last(),
        )
        assertEquals(
            "拨回日的瞬时应互不重复", 1440, overlapInstants.filterNotNull().toSet().size,
        )

        // 端到端：切换日报告的日出分钟必须就是那一刻的真实钟面读数。
        // 修复前这里整整差 60 分钟（例：06:30 的日出被报成 05:30）。
        for (day in listOf(gapDay, overlapDay)) {
            val clock = SunlightEvaluator.DayClock(day, zone)
            for (minute in listOf(
                SunlightEvaluator.evaluate(
                    -33.87, 151.21, zone.id, emptyList(), emptyList(),
                    CalcMode.EXTERNAL_ONLY, day,
                ).sunriseMinute!!,
                SunlightEvaluator.evaluate(
                    -33.87, 151.21, zone.id, emptyList(), emptyList(),
                    CalcMode.EXTERNAL_ONLY, day,
                ).sunsetMinute!!,
            )) {
                val reading = Instant.ofEpochMilli(clock.instantAt(minute)!!).atZone(zone)
                assertEquals(
                    "$day 的第 $minute 分钟应读作 $minute 分钟",
                    minute, reading.hour * 60 + reading.minute,
                )
            }
        }
    }

    @Test
    fun dstDayLengthIsNotAlwaysTwentyFourHours() {
        // 守住上面那条回归的前提：Sydney 2026 真的有一个 23 小时日和一个 25 小时日，
        // 否则分钟映射测试会退化成与普通日期无异
        fun dayHours(date: LocalDate): Long {
            val zone = ZoneId.of("Australia/Sydney")
            return (date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() -
                date.atStartOfDay(zone).toInstant().toEpochMilli()) / 3_600_000L
        }
        assertEquals(23L, dayHours(LocalDate.of(2026, 10, 4)))
        assertEquals(25L, dayHours(LocalDate.of(2026, 4, 5)))
        assertEquals(24L, dayHours(LocalDate.of(2026, 6, 21)))
    }

    /**
     * 缺口落在午夜的时区（当地 0 时直接跳到 1 时）也要把钟面钉在自己那一天。
     *
     * 这一类与悉尼那个 02:00 的缺口不是一回事：`date.atStartOfDay(zone)` 返回的是「那一天最早的
     * 合法当地时刻」，在缺口日就是 01:00。原先拿它当「当地 0 时」，整个钟面因此被整体挪前
     * 一小时、全天多出 60 个采样并溢出到次日，报出来的日出日落与可见区间全部差一小时。
     * 2026 年有这种情况的时区：America/Havana（3-08）、America/Santiago（9-06）、
     * Asia/Beirut（3-29）、Africa/Cairo（4-24），另加 Atlantic/Azores（3-29）。
     */
    @Test
    fun midnightGapZoneKeepsTheClockFaceOnItsOwnDay() {
        val zone = ZoneId.of("America/Havana")
        val day = LocalDate.of(2026, 3, 8)
        // 前提：这一天确实没有 0 时，否则下面那些断言全都退化成普通日期
        assertEquals("这一天的 0 时应当不存在", 1, day.atStartOfDay(zone).hour)

        val clock = SunlightEvaluator.DayClock(day, zone)
        val instants = (0 until SunlightEvaluator.MINUTES_PER_DAY).map { clock.instantAt(it) }
        assertEquals("00:00-00:59 不存在，当天只剩 1380 个有效分钟", 1380, instants.count { it != null })
        assertEquals("缺口就是开头的 60 分钟", 60, (0 until 60).count { clock.instantAt(it) == null })
        assertEquals(
            "最后一个有效瞬时必须是当地 23:59，不是次日 00:59",
            day.plusDays(1).atStartOfDay().atZone(zone).toInstant().toEpochMilli() - 60_000L,
            instants.last(),
        )
        assertEquals("瞬时不能重复", 1380, instants.filterNotNull().toSet().size)

        // 端到端：报出来的日出日落分钟必须就是那一刻的当地钟面读数。修复前这里差 60 分钟。
        val r = SunlightEvaluator.evaluate(
            23.13, -82.38, zone.id, emptyList(), emptyList(),
            CalcMode.EXTERNAL_ONLY, day,
        )
        for (minute in listOf(r.sunriseMinute!!, r.sunsetMinute!!)) {
            val reading = Instant.ofEpochMilli(clock.instantAt(minute)!!).atZone(zone)
            assertEquals("第 $minute 分钟应读作 $minute 分钟", minute, reading.hour * 60 + reading.minute)
        }
    }

    @Test
    fun ceilingCutsPartOfTheDay() {
        // 天花板 70 度平沿覆盖 150..210 度：正午前后一部分时间被自家窗框挡住
        val ceiling = listOf(ShotPoint(150.0, 70.0), ShotPoint(210.0, 70.0))
        val open = evaluateWinter(CalcMode.EXTERNAL_ONLY, emptyList(), emptyList())
        val cut = evaluateWinter(CalcMode.EXTERNAL_AND_CEILING, emptyList(), ceiling)
        assertTrue(cut.directMinutes < open.directMinutes)
        assertTrue(cut.directMinutes > 0)
    }
}