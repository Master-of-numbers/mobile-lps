package dev.mobilelps.gnss

import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Time is expressed as continuous seconds of the GPS time scale since the GPS epoch (1980-01-06 00:00:00).
 * Galileo system time uses the same week boundaries, so it shares this representation.
 */
object GnssTime {
    const val SPEED_OF_LIGHT_MPS = 299_792_458.0
    const val SECONDS_PER_WEEK = 604_800L
    const val NANOS_PER_SECOND = 1_000_000_000L
    const val NANOS_PER_WEEK = SECONDS_PER_WEEK * NANOS_PER_SECOND

    /** GPS-UTC offset. Changes only when a leap second is announced (none since 2017). */
    const val LEAP_SECONDS = 18L

    private const val GPS_EPOCH_UNIX_SECONDS = 315_964_800L

    /** Converts a calendar date expressed in GPS time (as in RINEX navigation records) to GPS seconds. */
    fun fromCalendar(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Double,
    ): Double {
        val unix = LocalDateTime.of(year, month, day, hour, minute).toEpochSecond(ZoneOffset.UTC)
        return (unix - GPS_EPOCH_UNIX_SECONDS) + second
    }

    fun gpsSecondsToUnixMillis(gpsSeconds: Double): Long =
        ((gpsSeconds + GPS_EPOCH_UNIX_SECONDS - LEAP_SECONDS) * MILLIS_PER_SECOND).toLong()

    fun unixMillisToGpsSeconds(unixMillis: Long): Double =
        unixMillis / MILLIS_PER_SECOND - GPS_EPOCH_UNIX_SECONDS + LEAP_SECONDS

    fun secondsOfWeek(gpsSeconds: Double): Double = gpsSeconds.mod(SECONDS_PER_WEEK.toDouble())

    private const val MILLIS_PER_SECOND = 1000.0
}
