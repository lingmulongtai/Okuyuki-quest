package com.okuyuki.quest.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FormatTest {
    @Test fun `a Latin name keeps a space before Japanese`() {
        assertEquals("SM-S938Q につないでいます…", named("SM-S938Q", "につないでいます…"))
        assertEquals("Pixel 9 Pro に同じ番号", named("Pixel 9 Pro", "に同じ番号"))
        assertEquals("前回のスマホにつないでいます…", named("前回のスマホ", "につないでいます…"))
    }

    @Test fun `days read like the phone app`() {
        val today = LocalDate.of(2026, 10, 12)
        assertEquals("今日", dayLabel(today, today))
        assertEquals("昨日", dayLabel(today.minusDays(1), today))
        assertEquals("10月9日（金）", dayLabel(LocalDate.of(2026, 10, 9), today))
        assertEquals("2025年12月31日（水）", dayLabel(LocalDate.of(2025, 12, 31), today))
    }

    @Test fun `sizes are rounded for reading`() {
        assertEquals("0 MB", sizeLabel(0))
        assertEquals("1 KB", sizeLabel(10))
        assertEquals("7 MB", sizeLabel(6_880_000))
        assertEquals("1.0 GB", sizeLabel(1L shl 30))
    }
}
