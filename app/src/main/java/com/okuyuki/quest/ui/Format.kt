package com.okuyuki.quest.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthDay = DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE)
private val yearMonthDay = DateTimeFormatter.ofPattern("yyyy年M月d日（E）", Locale.JAPANESE)
private val clock = DateTimeFormatter.ofPattern("H:mm", Locale.JAPANESE)

fun localDay(time: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

/** 今日, 昨日, 10月9日（木）, or with the year when it is not this year. The phone app labels days the same way. */
fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
    today -> "今日"
    today.minusDays(1) -> "昨日"
    else -> (if (day.year == today.year) monthDay else yearMonthDay).format(day)
}

fun timeLabel(time: Long, zone: ZoneId = ZoneId.systemDefault()): String = clock.format(Instant.ofEpochMilli(time).atZone(zone))

fun dateTimeLabel(time: Long, zone: ZoneId = ZoneId.systemDefault()): String = "${dayLabel(localDay(time, zone))} ${timeLabel(time, zone)}"

fun sizeLabel(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(Locale.ROOT, bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(Locale.ROOT, bytes / (1L shl 20).toDouble())
    bytes > 0 -> "%.0f KB".format(Locale.ROOT, bytes.coerceAtLeast(1024) / 1024.0)
    else -> "0 MB"
}

/** [name] then Japanese [rest], with the space Japanese text keeps after a Latin name such as SM-S938Q or Pixel 9. */
fun named(name: String, rest: String): String = if ((name.lastOrNull()?.code ?: 0x3000) < 0x2E80) "$name $rest" else "$name$rest"
