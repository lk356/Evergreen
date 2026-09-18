package com.example.evergreen.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A record of one canonical track having been folded into another.
 *
 * Merging is the only operation in this app that deletes a row, so it is the
 * only one that needs an audit trail. Without this, two songs silently becoming
 * one would be unexplainable a year later — and "why does this track have
 * listening history from an album I never played?" is exactly the question a
 * merge creates.
 *
 * Deliberately stores the *losing* track's identifying fields rather than a
 * foreign key to it: the row it describes no longer exists. That is also why
 * this table has no foreign keys at all.
 */
@Entity(
    tableName = "track_merges",
    indices = [
        Index(value = ["survivorTrackId"]),
        Index(value = ["isrc"]),
    ],
)
data class TrackMergeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The track that absorbed the other, and still exists. */
    val survivorTrackId: Long,

    /** What the deleted track was, kept so the merge stays explainable. */
    val mergedTrackKey: String,
    val mergedTitle: String?,
    val mergedArtist: String?,
    val mergedAlbum: String?,

    /** The ISRC the two shared, which is why they were judged the same song. */
    val isrc: String,

    /**
     * How much was re-pointed.
     *
     * Non-zero counts here are the evidence that the history moved rather than
     * being deleted — which is what the RESTRICT foreign keys exist to enforce.
     */
    val segmentsMoved: Int,
    val serviceRowsMoved: Int,

    val mergedAt: Long,
)
