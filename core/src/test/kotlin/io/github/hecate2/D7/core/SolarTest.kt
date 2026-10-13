package io.github.hecate2.D7.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

class SolarTest {

    private val beijingLat = 39.9
    private val beijingLon = 116.4
    private val beijingZone = "Asia/Shanghai"

    /** 扫出当地一天中太阳最高点（几何高度角）及其钟表分钟。 */
    private fun peakElevation(date: LocalDate, lat: Double, lon: Double, zoneId: String): Pair<Double, Int> {
        val start = date.atStartOfDay(ZoneId.of(zoneId)).toInstant().toEpochMilli()
        var best = -180.0
        var bestMinute = 0
        for (m in 0 until 1440) {
            val p = Solar.position(start + m * 60_000L, lat, lon, refraction = false)
            if (p.elevationDeg > best) {
                best = p.elevationDeg
                bestMinute = m
            }
        }
        return best to bestMinute
    }

    /** 扫出当地一天中的最低几何高度角。 */
    private fun minElevation(date: LocalDate, lat: Double, lon: Double, zoneId: String): Double {
        val start = date.atStartOfDay(ZoneId.of(zoneId)).toInstant().toEpochMilli()
        var lowest = 180.0
        for (m in 0 until 1440) {
            val p = Solar.position(start + m * 60_000L, lat, lon, refraction = false)
            if (p.elevationDeg < lowest) lowest = p.elevationDeg
        }
        return lowest
    }

    @Test
    fun julianDayAtJ2000() {
        val millis = ZonedDateTime.of(2000, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(2451545.0, Solar.julianDay(millis), 1e-6)
    }

    @Test
    fun beijingNoonElevationMatchesLatitude() {
        // 夏至正午高度角 ≈ 90 - 纬度 + 23.44
        val (summerPeak, _) = peakElevation(LocalDate.of(2026, 6, 21), beijingLat, beijingLon, beijingZone)
        assertEquals(90.0 - beijingLat + 23.44, summerPeak, 0.7)

        // 冬至正午高度角 ≈ 90 - 纬度 - 23.44
        val (winterPeak, _) = peakElevation(LocalDate.of(2026, 12, 21), beijingLat, beijingLon, beijingZone)
        assertEquals(90.0 - beijingLat - 23.44, winterPeak, 0.7)
    }

    @Test
    fun beijingNoonAzimuthIsSouth() {
        val (_, winterNoonMinute) = peakElevation(LocalDate.of(2026, 12, 21), beijingLat, beijingLon, beijingZone)
        val start = LocalDate.of(2026, 12, 21).atStartOfDay(ZoneId.of(beijingZone)).toInstant().toEpochMilli()
        val az = Solar.position(start + winterNoonMinute * 60_000L, beijingLat, beijingLon).azimuthDeg
        assertEquals(180.0, az, 0.5)

        // 正午钟表时间应在 12:13 附近（经度修正 - 均时差）
        assertTrue("正午钟表分钟=$winterNoonMinute", winterNoonMinute in 725..745)
    }

    @Test
    fun southernHemisphereNoonAzimuthIsNorth() {
        // 悉尼冬至（6 月 21 日）：太阳正午在正北
        val (peak, noonMinute) = peakElevation(LocalDate.of(2026, 6, 21), -33.87, 151.21, "Australia/Sydney")
        assertEquals(90.0 - 33.87 - 23.44, peak, 0.7)
        val start = LocalDate.of(2026, 6, 21).atStartOfDay(ZoneId.of("Australia/Sydney")).toInstant().toEpochMilli()
        val az = Solar.position(start + noonMinute * 60_000L, -33.87, 151.21).azimuthDeg
        assertTrue("南半球正午方位=$az", min(az, 360.0 - az) < 0.5)
    }

    @Test
    fun equinoxSunriseAzimuthIsEast() {
        // 赤道春分日出方位 ≈ 90 度
        val date = LocalDate.of(2026, 3, 20)
        val start = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        var sunriseAz = -1.0
        for (m in 0 until 1440) {
            val p = Solar.position(start + m * 60_000L, 0.1, 0.0, refraction = false)
            if (p.elevationDeg > Solar.SUNRISE_THRESHOLD_DEG) {
                sunriseAz = p.azimuthDeg
                break
            }
        }
        assertTrue("赤道春分日出方位=$sunriseAz", abs(sunriseAz - 90.0) < 1.5)
    }

    @Test
    fun polarDayMidnightSunStaysUp() {
        // 斯瓦尔巴（78.22N）夏至前后太阳全天不落
        val lowest = minElevation(LocalDate.of(2026, 6, 21), 78.22, 15.65, "UTC")
        assertTrue("极昼最低高度角=$lowest", lowest > 0.0)
    }

    @Test
    fun declinationCrossCheckWithLowPrecisionFormula() {
        // 与独立低精度日下点公式对拍：δ ≈ 23.44° · sin(360°/365.24 · (N-81))
        val cases = listOf(
            LocalDate.of(2026, 3, 20),
            LocalDate.of(2026, 6, 21),
            LocalDate.of(2026, 9, 23),
            LocalDate.of(2026, 12, 21),
            LocalDate.of(2026, 1, 20),
        )
        for (date in cases) {
            val (peak, _) = peakElevation(date, beijingLat, beijingLon, beijingZone)
            val declFromModel = beijingLat - (90.0 - peak)
            val n = date.dayOfYear.toDouble()
            val declApprox = 23.44 * sin(Math.toRadians(360.0 / 365.24 * (n - 81.0)))
            assertEquals("日期=$date 模型赤纬=$declFromModel", declApprox, declFromModel, 1.5)
        }
    }

    @Test
    fun refractionCorrectionIsPositiveNearHorizon() {
        assertTrue(Solar.refractionCorrectionDeg(0.0) > 0.4)
        assertTrue(Solar.refractionCorrectionDeg(0.0) < 0.6)
        assertEquals(0.0, Solar.refractionCorrectionDeg(88.0), 1e-9)
    }

    @Test
    fun declinationMatchesSeasons() {
        // 夏至 ±23.44、冬至 ∓23.44、春秋分接近 0
        val summer = Solar.declinationDeg(1750507200000L) // 2025-06-21T12:00Z
        val winter = Solar.declinationDeg(1766318400000L) // 2025-12-21T12:00Z
        val spring = Solar.declinationDeg(1742472000000L) // 2025-03-20T12:00Z
        assertEquals(23.44, summer, 0.1)
        assertEquals(-23.44, winter, 0.1)
        assertTrue("春分赤纬=$spring", abs(spring) < 0.5)
    }

    /**
     * 太阳过天顶时仰角是 90 度，不能是 NaN。
     *
     * `positionFrom` 里那个和（sin·sin + cos·cos·cos h）在纬度等于赤纬、时角为 0 时的理论值是 1，
     * 而 IEEE754 下它可能是 1 + 1 ulp：实测让 lat 与 decl 取同一个值、时角取 0，再从 -90 扫到 90
     * 共 180 万个取值（步长 1e-4），有 101,262 个算出的和是 1.0000000000000002，光看热带
     * （|lat| ≤ 23.44）也有 23,115 个。`asin(>1)` 返回 NaN，而 NaN 一旦进了仰角，
     * `elevationDeg > -0.833` 恒为 false，那一分钟会被当成黑夜，等于正午没有太阳。
     */
    @Test
    fun zenithElevationStaysFiniteInsteadOfNaN() {
        // 实测最容易溢出的那个取值
        val worst = -89.9999
        assertEquals(90.0, Solar.positionFrom(worst, worst, 0.0, refraction = false).elevationDeg, 1e-9)

        // 天顶构型全域扫一遍：仰角必须有限且就是 90 度。
        // 纬度按整数下标除出来，不用累加：累加到末尾会越过 90 度，扫到的点数就变成 18 万个。
        var checked = 0
        for (i in -90_000..90_000) {
            val lat = i / 1_000.0
            val p = Solar.positionFrom(lat, lat, 0.0, refraction = false)
            assertTrue(
                "lat=$lat 的仰角是 ${p.elevationDeg}（应为 90）",
                p.elevationDeg.isFinite() && p.elevationDeg in 89.99..90.01,
            )
            checked++
        }
        assertEquals(180_001, checked)
    }
}