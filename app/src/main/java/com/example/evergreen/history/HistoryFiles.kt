package com.example.evergreen.history

import java.io.InputStream
import java.security.MessageDigest

/**
 * Working out what the user picked, and what inside it is worth reading.
 *
 * The export arrives as a zip containing a folder of JSON files, only some of
 * which are listening history. The rest — playlists, follower lists, inferred
 * advertising interests, search queries — are none of this app's business, and
 * are excluded by name rather than by trying to parse them and seeing what
 * happens.
 */
object HistoryFiles {

    /** `PK`, the first four bytes of every zip file. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /**
     * True when [header] starts with the zip magic number.
     *
     * Sniffed from the content rather than trusted from the filename or the
     * MIME type, both of which the picker reports inconsistently across
     * Android versions and file managers.
     */
    fun looksLikeZip(header: ByteArray): Boolean =
        header.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { header[it] == ZIP_MAGIC[it] }

    /**
     * True for the entries inside the export that hold audio listening history.
     *
     * Spotify names them `Streaming_History_Audio_2020-2021_0.json`; older
     * exports used `endsong_0.json`. Video history, playlists and everything
     * else are left alone.
     *
     * The `__MACOSX` resource forks a zip made on a Mac carries are skipped:
     * they have the same names prefixed with `._` and contain binary rubbish
     * that would fail to parse and abort the import.
     */
    fun isAudioHistoryEntry(name: String): Boolean {
        if (name.endsWith("/")) return false
        val base = name.substringAfterLast('/')
        if (!base.endsWith(".json", ignoreCase = true)) return false
        if (base.startsWith("._") || base.startsWith(".")) return false
        if (name.contains("__MACOSX")) return false
        return base.contains("Streaming_History_Audio", ignoreCase = true) ||
            base.startsWith("endsong", ignoreCase = true)
    }

    /**
     * The SHA-256 of a stream's contents, as lower-case hex.
     *
     * Identity by content, not by name: re-downloading the export to
     * `history (1).zip` is the same file and must not import twice, while a
     * genuinely different export is a different hash however it is named.
     *
     * Streamed in 64 KB blocks — the file can be 12 MB and the device that
     * hashes it is the one with the least memory.
     */
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
