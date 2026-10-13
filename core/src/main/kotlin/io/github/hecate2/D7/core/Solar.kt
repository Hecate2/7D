package io.github.hecate2.D7.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 太阳位置：方位角（正北起顺时针 0..360）与仰角（地平线 0、天顶 90）。
 */
data class SolarPosition(val azimuthDeg: Double, val elevationDeg: Double)

/**
 * NOAA 简化太阳位置算法（与高精度星历偏差小于 0.02 度）。
 *
 * 时间参数一律为 UTC 瞬时（毫秒）；方位角从正北起顺时针。
 * 默认返回含大气折射的视高度角；遮挡判定请用 refraction = false 的几何高度角，
 * 日出日落判定用几何高度角与 [SUNRISE_THRESHOLD_DEG] 阈值（等效上缘 + 折射）。
 */
object Solar {

    /** 日出日落阈值：太阳中心几何高度角低于此值视为在地平线下（等效上缘与折射）。 */
    const val SUNRISE_THRESHOLD_DEG = -0.833

    private const val DAY_MILLIS = 86_400_000.0
    private const val J2000_JD = 2_451_545.0
    private const val JULIAN_CENTURY_DAYS = 36_525.0
    private const val UNIX_EPOCH_JD = 2_440_587.5

    /** 儒略日。 */
    fun julianDay(utcMillis: Long): Double = utcMillis / DAY_MILLIS + UNIX_EPOCH_JD

    /**
     * 给定 UTC 瞬时与经纬度，计算太阳方位角与仰角。
     *
     * @param refraction true 返回视高度角（含大气折射修正），false 返回几何高度角。
     */
    fun position(
        utcMillis: Long,
        latDeg: Double,
        lonDeg: Double,
        refraction: Boolean = true,
    ): SolarPosition {
        // 时角
        val jd = julianDay(utcMillis)
        val hourAngle = trueSolarMinutes(utcMillis, lonDeg, jd) / 4.0 - 180.0
        return positionFrom(latDeg, declinationAt(jd), hourAngle, refraction)
    }

    /** 太阳赤纬（度）：太阳直射点纬度，北正南负，全年在 ±23.44 度内。 */
    fun declinationDeg(utcMillis: Long): Double = declinationAt(julianDay(utcMillis))

    /** 同上，直接以儒略日为参数，供单日轨迹复用。 */
    private fun declinationAt(jd: Double): Double {
        val t = (jd - J2000_JD) / JULIAN_CENTURY_DAYS

        // 几何平黄经、平近点角、中心差
        val l0 = Angles.normalize360(280.46646 + t * (36_000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val mRad = m * PI / 180.0
        val center = sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mRad) * (0.019993 - 0.000101 * t) +
            sin(3 * mRad) * 0.000289
        val trueLong = l0 + center

        // 视黄经（含章动近似）
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(omega * PI / 180.0)

        // 黄赤交角
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(omega * PI / 180.0)

        return asin(sin(eps * PI / 180.0) * sin(lambda * PI / 180.0)) * 180.0 / PI
    }

    /**
     * 由纬度、赤纬、时角（度）计算太阳方位角与仰角。
     * 方位角使用 atan2 公式：先求相对正南的偏角（西为正），再加 180 度归一。
     */
    fun positionFrom(
        latDeg: Double,
        declDeg: Double,
        hourAngleDeg: Double,
        refraction: Boolean = true,
    ): SolarPosition {
        val latRad = latDeg * PI / 180.0
        val declRad = declDeg * PI / 180.0
        val hRad = hourAngleDeg * PI / 180.0

        val sinEl = sin(latRad) * sin(declRad) + cos(latRad) * cos(declRad) * cos(hRad)
        // 这里的返回值必须有限，所以先夹到 [-1, 1] 再 asin：太阳正过天顶时（纬度与赤纬相等、
        // 时角为 0）这个和的理论值是 1，而 IEEE754 下两个乘积各自舍入之后可以给出 1 + 1 ulp，
        // asin(>1) 返回 NaN。实测把 lat 与 decl 取同一个值、时角取 0，再让 lat 从 -90 扫到 90
        // （步长 1e-4，180 万个取值），其中 101,262 个（5.6%）算出的和是 1.0000000000000002；
        // 只看热带（|lat| ≤ 23.44）也有 23,115 个命中，最小命中纬度是 0.0072 度。
        // NaN 一旦进了仰角，`elevationDeg > -0.833` 恒为 false，那一分钟会被当成黑夜。
        // 现实中要撞上得纬度正好等于赤纬、时角正好是 0（热带一年两天里的某一毫秒），
        // 所以这是纯粹的防御性夹紧：代价为零，换掉一整类 NaN。
        val geometricEl = asin(sinEl.coerceIn(-1.0, 1.0)) * 180.0 / PI

        val azFromSouth = atan2(
            sin(hRad),
            cos(hRad) * sin(latRad) - tan(declRad) * cos(latRad),
        ) * 180.0 / PI
        val azimuth = Angles.normalize360(azFromSouth + 180.0)

        val elevation = if (refraction) {
            geometricEl + refractionCorrectionDeg(geometricEl)
        } else {
            geometricEl
        }
        return SolarPosition(azimuth, elevation)
    }

    private fun trueSolarMinutes(utcMillis: Long, lonDeg: Double, jd: Double): Double =
        normalizeMinutes(utcMinutesOf(utcMillis) + equationOfTimeMinutes(jd) + 4.0 * lonDeg)

    /** 当日 UTC 零点起的分钟数，对负毫秒取模也安全。 */
    private fun utcMinutesOf(utcMillis: Long): Double =
        ((utcMillis % 86_400_000L + 86_400_000L) % 86_400_000L) / 60_000.0

    /**
     * 某一天全天共用的太阳轨迹：赤纬与均时差各按当天算一次，之后每次采样只做时角换算。
     *
     * 取「当日中点」而不是日初：这两样一天内都在变（赤纬最多 0.4 度，二分点前后最快；
     * 均时差最多约 0.3 分钟），从日初起算会让离日初越远的采样偏得越多。取中点则两端各差
     * 半天，实测相对逐次重算的最大偏差从 0.57 度降到 0.23 度（方位）、0.15 度（仰角），
     * 仍远小于罗盘那 2~4 度，而这个改动本身不花一分钱。
     *
     * 一次采样省下的只是均时差那约九次三角函数，但单日要采 1440 次、全年曲线要采
     * 365 × 1440 次，全年就是四百多万次——实测全年曲线 149.5 → 111 毫秒。
     */
    class DayTrack private constructor(
        private val jd: Double,
        private val latDeg: Double,
        private val lonDeg: Double,
    ) {
        private val declDeg = declinationAt(jd)
        private val eotMinutes = equationOfTimeMinutes(jd)

        /** 当日某 UTC 瞬时的太阳位置。 */
        fun position(utcMillis: Long, refraction: Boolean = true): SolarPosition =
            positionFrom(latDeg, declDeg, trueSolarMinutes(utcMillis) / 4.0 - 180.0, refraction)

        /** 当日某 UTC 瞬时的真太阳时（分钟，0..1440）。用建轨时算好的均时差，不逐次重算。 */
        fun trueSolarMinutes(utcMillis: Long): Double =
            normalizeMinutes(utcMinutesOf(utcMillis) + eotMinutes + 4.0 * lonDeg)

        companion object {
            /** 以当日 0 时（UTC 毫秒）为基准建立轨迹；赤纬与均时差取该日中点（见类注释）。 */
            fun ofDayStart(dayStartMillis: Long, latDeg: Double, lonDeg: Double): DayTrack =
                DayTrack(julianDay(dayStartMillis) + 0.5, latDeg, lonDeg)
        }
    }

    /** NOAA 分段大气折射修正（度），输入几何高度角。 */
    fun refractionCorrectionDeg(elevationDeg: Double): Double {
        val e = elevationDeg
        val arcSeconds = when {
            e > 85.0 -> 0.0
            e > 5.0 -> {
                val te = tan(e * PI / 180.0)
                58.1 / te - 0.07 / (te * te * te) + 0.000086 / (te * te * te * te * te)
            }
            e > -0.575 -> 1735.0 + e * (-518.2 + e * (103.4 + e * (-12.79 + e * 0.711)))
            else -> -20.772 / tan(e * PI / 180.0)
        }
        return arcSeconds / 3600.0
    }

    /** 均时差（分钟，NOAA）。 */
    private fun equationOfTimeMinutes(jd: Double): Double {
        val t = (jd - J2000_JD) / JULIAN_CENTURY_DAYS
        val l0 = Angles.normalize360(280.46646 + t * (36_000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val ecc = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val omega = 125.04 - 1934.136 * t

        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(omega * PI / 180.0)
        val y = tan(eps * PI / 360.0).let { it * it }

        val l0Rad = l0 * PI / 180.0
        val mRad = m * PI / 180.0
        return 4.0 * (
            y * sin(2 * l0Rad) - 2 * ecc * sin(mRad) + 4 * ecc * y * sin(mRad) * cos(2 * l0Rad) -
                0.5 * y * y * sin(4 * l0Rad) - 1.25 * ecc * ecc * sin(2 * mRad)
            ) * 180.0 / PI
    }

    private fun normalizeMinutes(minutes: Double): Double {
        var m = minutes % 1440.0
        if (m < 0.0) m += 1440.0
        return m
    }
}