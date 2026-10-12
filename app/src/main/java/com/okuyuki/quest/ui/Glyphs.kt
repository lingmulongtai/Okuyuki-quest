package com.okuyuki.quest.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The app's line icons, drawn like the phone app's on a 24 grid with a 1.8 stroke. They take the tint of the surrounding content. */
object Glyphs {
    val Phone by lazy { line("phone") { roundRect(6.5f, 2.5f, 17.5f, 21.5f, 2.8f); moveTo(10.5f, 18.2f); horizontalLineTo(13.5f) } }
    val Tune by lazy { line("tune") {
        moveTo(4f, 7f); horizontalLineTo(7.6f); moveTo(12.4f, 7f); horizontalLineTo(20f); circle(10f, 7f, 2.2f)
        moveTo(4f, 12f); horizontalLineTo(13.6f); moveTo(18.4f, 12f); horizontalLineTo(20f); circle(16f, 12f, 2.2f)
        moveTo(4f, 17f); horizontalLineTo(5.6f); moveTo(10.4f, 17f); horizontalLineTo(20f); circle(8f, 17f, 2.2f)
    } }
    val Refresh by lazy { line("refresh") {
        moveTo(19.05f, 14.57f); arcTo(7.5f, 7.5f, 0f, true, true, 17.75f, 7.18f)
        moveTo(17.44f, 3.59f); lineTo(17.75f, 7.18f); lineTo(14.27f, 6.25f)
    } }
    val Trash by lazy { line("trash") {
        moveTo(4.5f, 6.5f); horizontalLineTo(19.5f)
        moveTo(9.5f, 6.5f); verticalLineTo(5f); arcTo(1.5f, 1.5f, 0f, false, true, 11f, 3.5f); horizontalLineTo(13f)
        arcTo(1.5f, 1.5f, 0f, false, true, 14.5f, 5f); verticalLineTo(6.5f)
        moveTo(6.5f, 6.5f); lineTo(7.3f, 18.6f); arcTo(2f, 2f, 0f, false, false, 9.3f, 20.5f); horizontalLineTo(14.7f)
        arcTo(2f, 2f, 0f, false, false, 16.7f, 18.6f); lineTo(17.5f, 6.5f)
        moveTo(10.2f, 10.5f); verticalLineTo(16.5f); moveTo(13.8f, 10.5f); verticalLineTo(16.5f)
    } }
    val Headset by lazy { line("headset") {
        moveTo(6f, 7f); horizontalLineTo(18f); arcTo(3.5f, 3.5f, 0f, false, true, 21.5f, 10.5f); verticalLineTo(13.5f)
        arcTo(3.5f, 3.5f, 0f, false, true, 18f, 17f); horizontalLineTo(15.6f)
        curveTo(15f, 17f, 14.6f, 16.7f, 14.3f, 16.2f); lineTo(13.4f, 14.8f)
        arcTo(1.6f, 1.6f, 0f, false, false, 10.6f, 14.8f); lineTo(9.7f, 16.2f)
        curveTo(9.4f, 16.7f, 9f, 17f, 8.4f, 17f); horizontalLineTo(6f)
        arcTo(3.5f, 3.5f, 0f, false, true, 2.5f, 13.5f); verticalLineTo(10.5f); arcTo(3.5f, 3.5f, 0f, false, true, 6f, 7f); close()
    } }
    val Check by lazy { line("check") { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) } }
    val Close by lazy { line("close") { moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f); moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f) } }
    val Lock by lazy { line("lock") {
        roundRect(5f, 10.5f, 19f, 20.5f, 2.5f)
        moveTo(8.5f, 10.5f); verticalLineTo(8f); arcTo(3.5f, 3.5f, 0f, false, true, 15.5f, 8f); verticalLineTo(10.5f)
    } }
    val Wifi by lazy { line("wifi") {
        moveTo(2.04f, 10.14f); arcTo(13f, 13f, 0f, false, true, 21.96f, 10.14f)
        moveTo(5.11f, 12.71f); arcTo(9f, 9f, 0f, false, true, 18.89f, 12.71f)
        moveTo(8.17f, 15.29f); arcTo(5f, 5f, 0f, false, true, 15.83f, 15.29f)
        dot(12f, 18.5f)
    } }
    val ChevronLeft by lazy { line("chevron-left") { moveTo(14.5f, 6f); lineTo(8.5f, 12f); lineTo(14.5f, 18f) } }
    val ChevronRight by lazy { line("chevron-right") { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) } }
    val ChevronDown by lazy { line("chevron-down") { moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f) } }
    val Info by lazy { line("info") { circle(12f, 12f, 9f); moveTo(12f, 11f); verticalLineTo(16.5f); dot(12f, 7.8f) } }
    val Cube by lazy { line("cube") {
        moveTo(12f, 3f); lineTo(20f, 7.5f); verticalLineTo(16.5f); lineTo(12f, 21f); lineTo(4f, 16.5f); verticalLineTo(7.5f); close()
        moveTo(4f, 7.5f); lineTo(12f, 12f); lineTo(20f, 7.5f); moveTo(12f, 12f); verticalLineTo(21f)
    } }
    val Photo by lazy { line("photo") {
        roundRect(3.5f, 4.5f, 20.5f, 19.5f, 2.5f)
        moveTo(3.8f, 16.2f); lineTo(8.5f, 11.5f); lineTo(13.2f, 16.2f)
        moveTo(11.6f, 14.6f); lineTo(14.5f, 11.8f); lineTo(20.2f, 17.2f)
        circle(15.5f, 8.3f, 1.3f)
    } }
}

private fun line(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block).build()

private fun PathBuilder.roundRect(left: Float, top: Float, right: Float, bottom: Float, r: Float) {
    moveTo(left + r, top); horizontalLineTo(right - r); arcTo(r, r, 0f, false, true, right, top + r)
    verticalLineTo(bottom - r); arcTo(r, r, 0f, false, true, right - r, bottom)
    horizontalLineTo(left + r); arcTo(r, r, 0f, false, true, left, bottom - r)
    verticalLineTo(top + r); arcTo(r, r, 0f, false, true, left + r, top); close()
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy); arcTo(r, r, 0f, true, true, cx + r, cy); arcTo(r, r, 0f, true, true, cx - r, cy); close()
}

/** A round dot the size of the stroke: a very short line with round caps. */
private fun PathBuilder.dot(x: Float, y: Float) { moveTo(x, y); lineTo(x + 0.01f, y) }
