package com.example.app.data

import java.io.File
import java.security.MessageDigest

/** Disposable, bounded cache. Failed/corrupt reads must never prevent network loading. */
class BoundedDiskCache(private val directory: File, private val maxBytes: Long,
    private val clock: () -> Long = System::currentTimeMillis) {
    @Synchronized fun remove(key: String) { runCatching { file(key).delete() } }
    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, hash)
    }

    @Synchronized fun read(key: String, maxAgeMs: Long): ByteArray? = runCatching {
        val entry = file(key)
        val age = clock() - entry.lastModified()
        if (!entry.isFile || age !in 0..maxAgeMs || entry.length() > maxBytes) {
            entry.delete()
            null
        } else entry.readBytes()
    }.getOrNull()

    @Synchronized fun write(key: String, bytes: ByteArray) {
        if (bytes.size > maxBytes) return
        runCatching {
            directory.mkdirs()
            val entry = file(key)
            val temporary = File.createTempFile("pending-", ".tmp", directory)
            try {
                temporary.writeBytes(bytes)
                if (entry.exists() && !entry.delete()) return@runCatching
                if (temporary.renameTo(entry)) entry.setLastModified(clock())
            } finally { temporary.delete() }
            val entries = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
            var total = entries.sumOf { it.length() }
            entries.forEach { candidate ->
                if (total > maxBytes) { val size = candidate.length(); if (candidate.delete()) total -= size }
            }
        }
    }
}
