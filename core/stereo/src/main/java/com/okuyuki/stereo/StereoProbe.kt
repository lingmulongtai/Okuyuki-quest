package com.okuyuki.stereo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class StereoIds(val left: Long, val right: Long)

/** Bounded ISO-BMFF metadata inspection. mdat is skipped, never loaded while indexing. */
object StereoProbe {
    private const val MAX_METADATA = 4 * 1024 * 1024

    fun inspect(channel: FileChannel): StereoIds? {
        val size = channel.size()
        var position = 0L
        var boxes = 0
        while (position + 8 <= size && boxes++ < 4096) {
            val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            channel.position(position)
            if (channel.read(header) < 8) return null
            header.flip()
            var length = header.int.toLong() and 0xffffffffL
            val type = header.int
            var prefix = 8
            if (length == 1L) {
                if (header.remaining() < 8) return null
                length = header.long
                prefix = 16
            } else if (length == 0L) length = size - position
            if (length < prefix || length > size - position) return null
            if (type == fourcc("meta") && length - prefix <= MAX_METADATA) {
                val bytes = ByteBuffer.allocate((length - prefix).toInt())
                channel.position(position + prefix)
                while (bytes.hasRemaining()) if (channel.read(bytes) <= 0) return null
                return parseMetadata(bytes.array())
            }
            position += length
        }
        return null
    }

    fun parseMetadata(bytes: ByteArray): StereoIds? = find(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN), 4, bytes.size, 0)

    private fun find(data: ByteBuffer, start: Int, end: Int, depth: Int): StereoIds? {
        if (depth > 4 || start > end || end > data.capacity()) return null
        var at = start
        while (at <= end - 8) {
            val length = data.getInt(at).toLong() and 0xffffffffL
            val type = data.getInt(at + 4)
            if (length < 8 || length > end - at) return null
            val next = at + length.toInt()
            if (type == fourcc("grpl")) find(data, at + 8, next, depth + 1)?.let { return it }
            if (type == fourcc("ster") && length >= 28 && data.getInt(at + 16) == 2) {
                val left = data.getInt(at + 20).toLong() and 0xffffffffL
                val right = data.getInt(at + 24).toLong() and 0xffffffffL
                if (left != 0L && right != 0L && left != right) return StereoIds(left, right)
            }
            at = next
        }
        return null
    }

    private fun fourcc(value: String): Int = value.fold(0) { n, c -> (n shl 8) or c.code }
}
