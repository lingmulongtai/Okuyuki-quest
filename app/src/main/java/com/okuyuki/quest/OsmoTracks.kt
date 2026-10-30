package com.okuyuki.quest

/** Identify the two square HEVC camera tracks; ignore cover images and other media. */
internal data class OsmoTrack(val group: Int, val index: Int, val mime: String?, val width: Int, val height: Int, val supported: Boolean)
internal fun osmoTracks(tracks: List<OsmoTrack>): List<OsmoTrack>? {
    val cameras = tracks.filter { it.mime == "video/hevc" && it.width >= 1024 && it.width == it.height }
    if (cameras.size != 2 || cameras.any { !it.supported }) return null
    if (cameras[0].width != cameras[1].width) return null
    return cameras
}
