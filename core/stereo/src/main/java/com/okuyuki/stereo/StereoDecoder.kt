package com.okuyuki.stereo

import android.graphics.Bitmap
import java.io.File

data class StereoPair(val left: Bitmap, val right: Bitmap) {
    fun recycle() { left.recycle(); right.recycle() }
}

object StereoDecoder {
    init { System.loadLibrary("okuyuki_stereo") }

    /** Decode only explicitly grouped eyes. No primary-image/index guessing is allowed. */
    fun decode(file: File): StereoPair {
        require(file.length() in 1..256L * 1024 * 1024) { "写真のサイズが対応範囲外です" }
        val eyes = decodeNative(file.absolutePath)
        return StereoPair(eyes[0], eyes[1])
    }

    private external fun decodeNative(path: String): Array<Bitmap>
}
