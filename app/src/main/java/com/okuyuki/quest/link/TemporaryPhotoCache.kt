package com.okuyuki.quest.link

import java.io.File
import java.security.MessageDigest

/** Only this dedicated app-private directory is managed. It is wiped at session start and end. */
class TemporaryPhotoCache(val directory: File, val limitBytes: Long = LIMIT_BYTES) {
    companion object { const val LIMIT_BYTES = 1024L * 1024 * 1024 }
    init { require(limitBytes > 0); directory.mkdirs(); clear() }
    @Synchronized fun clear() { directory.listFiles()?.filter { it.isFile }?.forEach { check(it.delete()) } }
    @Synchronized fun usage() = directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
    @Synchronized fun get(key: String): File? = File(directory, name(key)).takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }
    @Synchronized fun partial(key: String): File = File(directory, "${name(key)}.part")
    @Synchronized fun reserve(key: String, expectedBytes: Long) {
        require(expectedBytes in 1..limitBytes)
        val active = partial(key)
        val additional = (expectedBytes - active.length()).coerceAtLeast(0)
        directory.listFiles()?.filter { it.isFile && it != active }?.sortedBy { it.lastModified() }?.forEach {
            if (usage() + additional > limitBytes) check(it.delete())
        }
        check(usage() + additional <= limitBytes) { "Temporary storage is full" }
    }
    @Synchronized fun commit(key: String, expectedBytes: Long): File {
        val partial = partial(key)
        require(expectedBytes in 1..limitBytes && partial.length() == expectedBytes) { "Incomplete or oversized photo" }
        val result = File(directory, name(key))
        check(partial.renameTo(result)) { "Could not complete photo cache" }
        result.setLastModified(System.currentTimeMillis())
        directory.listFiles()?.filter { it.isFile && it != result }?.sortedBy { it.lastModified() }?.forEach {
            if (usage() > limitBytes) check(it.delete())
        }
        return result
    }
    private fun name(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".heic"
}
