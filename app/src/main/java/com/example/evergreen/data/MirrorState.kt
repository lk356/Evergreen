package com.example.evergreen.data

/**
 * What this database is, when it is not this device's own record.
 *
 * A **mirror** is a copy of another device's database, imported whole. It is
 * not a merge and not a sync: the Walkman keeps its own record and stays the
 * one that writes the Evergreen playlist, and the Pixel holds a snapshot of
 * that record taken at [takenAtMs], for reading on a faster screen.
 *
 * **The mirror can never write to Spotify.** That is the point of this type
 * existing rather than the writer flag being enough on its own. An imported
 * copy carries the *source* device's `sync_state` — including its writer flag,
 * its playlist id and its "first write confirmed" row — so the moment the file
 * lands, the database believes it is the Walkman. Checking the writer flag
 * alone would let the Pixel write the Walkman's playlist from a ranking that
 * is as stale as the last import. Every write path checks for a mirror first.
 *
 * Nothing here is a fact about listening, so nothing here is append-only:
 * importing a newer mirror overwrites it, and turning mirror mode off deletes
 * it.
 */
data class MirrorState(
    /**
     * The device the copy came from, e.g. `NW-A307`.
     *
     * Null when the backup was taken by a build older than the stamp, which
     * the screens say in words rather than showing a blank.
     */
    val sourceDevice: String?,

    /**
     * When the backup was taken on that device — how stale the mirror is.
     *
     * Null when the file's provider would not say and the name could not be
     * read. [importedAtMs] is then the only bound on its age, and it is an
     * upper one: the file cannot have been taken after it was imported.
     */
    val takenAtMs: Long?,

    /** When the import ran on this device. */
    val importedAtMs: Long,
) {
    /** How the screens name the source, in one short phrase. */
    fun describeSource(): String = sourceDevice ?: "another device"
}
