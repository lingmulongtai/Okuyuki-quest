package com.okuyuki.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object Protocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_okuyuki._tcp."
    const val PAGE_SIZE = 48
    const val MAX_ORIGINAL_BYTES = 256L * 1024 * 1024
    const val MAX_PANORAMA_PHOTO_BYTES = 64L * 1024 * 1024
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

@Serializable
data class Hello(
    val deviceId: String,
    val deviceName: String,
    val appVersion: String,
    val photoCount: Int,
    val protocolVersion: Int = Protocol.VERSION,
)

@Serializable
data class Photo(
    val id: String,
    val name: String,
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

@Serializable
data class LibraryPage(val items: List<Photo>, val next: String? = null)

/** One explicitly shared panoramic image, isolated from the v1 stereo library. */
@Serializable
data class PanoramaPhotoShare(val id: String, val sizeBytes: Long) {
    fun isValid(): Boolean = id.matches(Regex("[0-9a-f]{32}")) && sizeBytes in 1..Protocol.MAX_PANORAMA_PHOTO_BYTES
}

@Serializable
data class PairRequest(val clientId: String, val clientName: String, val requestId: String? = null)

@Serializable
data class PairResponse(
    val requestId: String,
    val code: String,
    val state: String,
    val token: String? = null,
)

@Serializable
data class ApiError(val message: String)

@Serializable
data class Event(val type: String, val itemId: String? = null)

/** Inclusive HTTP byte interval. Invalid/unsatisfiable and multiple ranges are rejected. */
data class ByteRange(val first: Long, val last: Long) {
    val length: Long get() = last - first + 1

    companion object {
        fun parse(header: String?, size: Long): ByteRange? {
            if (size <= 0) return null
            if (header == null) return ByteRange(0, size - 1)
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header) ?: return null
            val (start, end) = match.destructured
            if (start.isEmpty()) {
                val suffix = end.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return ByteRange((size - suffix).coerceAtLeast(0), size - 1)
            }
            val first = start.toLongOrNull()?.takeIf { it in 0 until size } ?: return null
            val last = if (end.isEmpty()) size - 1 else end.toLongOrNull() ?: return null
            if (last < first) return null
            return ByteRange(first, last.coerceAtMost(size - 1))
        }
    }
}
