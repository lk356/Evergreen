package com.example.evergreen.data

/**
 * Which device writes the Evergreen playlist.
 *
 * **One writer, handed over, never shared.** Both devices can capture, view and
 * change settings; only the one holding this role writes the playlist, takes
 * the monthly snapshot, and exports a month to Spotify. Reading from Spotify —
 * the likes sync — is allowed everywhere, because two devices reading the same
 * Liked Songs cannot disagree about it.
 *
 * ### The rule that makes it safe
 *
 * **The current writer releases the role, and the other device claims it only
 * on importing that release.** The releasing device stops being the writer
 * immediately; the receiving device becomes it only when the release actually
 * arrives. So between the two there is briefly **no** writer, which is
 * harmless — Evergreen simply is not written that day — and there is never
 * **two**, which is not harmless at all: two devices writing one playlist from
 * two rankings would undo each other daily, against a quota neither can see.
 *
 * Note which way round that is. A design where the *claiming* device announces
 * "I am the writer now" would have both holding it between the announcement and
 * its arrival. Releasing first is what turns the dangerous window into a safe
 * one.
 *
 * ### A device id, not "an Android device"
 *
 * [holderDeviceId] is a `capture/DeviceId` install id — a random UUID. Nothing
 * here knows or cares what kind of thing holds the role, so a self-hosted
 * server in Phase 7 could hold it by having an id of its own. That is a reason
 * not to make it impossible, and deliberately **not** a reason to build for it.
 *
 * ### Where it lives, and why there is no table
 *
 * In `sync_state` under [SyncKeys.EVERGREEN_WRITER_DEVICE]: `valueText` is the
 * holder, `valueMs` is when it was decided. It crosses between devices as a
 * `role` fact in a batch, exactly as the scoring parameters cross as a `params`
 * fact rather than as a copied `sync_state` row (`docs/SYNC_FORMAT.md` §3).
 *
 * A handover *log* — a table, like `parameter_history` — was considered and
 * not built. It would be a schema change, and it buys nothing for correctness:
 * [resolve] needs only the latest decision, and one row holds that. What it
 * would buy is an audit trail of past handovers, which nothing yet asks for.
 * Worth revisiting if a handover ever goes wrong in a way that needs
 * explaining after the fact.
 */
data class WriterRole(
    /** The install id of the device that writes Evergreen. */
    val holderDeviceId: String,
    /** When that was decided, on the deciding device's clock. */
    val decidedAtMs: Long,
) {
    /** True when [deviceId] is the device that may write. */
    fun isHeldBy(deviceId: String): Boolean = holderDeviceId == deviceId

    companion object {
        /**
         * The value written before Phase 5c, when this key held a boolean about
         * *this* device rather than the identity of a device.
         */
        private const val LEGACY_TRUE = "true"

        /**
         * Reads the stored row, translating the pre-5c boolean.
         *
         * `"true"` meant "I am the writer", so on the device that holds it that
         * is a claim by [thisDeviceId] — dated by the row, which is a real
         * moment and not a guess.
         *
         * **`"false"` becomes null, not a decision.** It only ever meant "not
         * me"; it never said who, so it carries no positive information and
         * must not be allowed to win a [resolve] against a real claim. On the
         * real devices it would have: the Pixel's `"false"` was written two
         * days *after* the Walkman's `"true"`, so treating it as a decision
         * would have left the Walkman's claim losing to a row that names
         * nobody.
         */
        fun fromStored(valueText: String?, valueMs: Long?, thisDeviceId: String): WriterRole? =
            when {
                valueText.isNullOrBlank() -> null
                valueText == LEGACY_TRUE -> WriterRole(thisDeviceId, valueMs ?: 0L)
                valueText.toBooleanStrictOrNull() == false -> null
                else -> WriterRole(valueText, valueMs ?: 0L)
            }

        /**
         * Which of two decisions stands: **the later one**.
         *
         * Ties break on the holder id, which is arbitrary but **deterministic**
         * — two databases that have seen the same decisions must agree on who
         * writes, or the invariant this type exists for is gone. The same
         * reasoning, and the same shape, as the parameter merge in
         * `BatchImport.adoptNewestParameters`.
         */
        fun resolve(a: WriterRole?, b: WriterRole?): WriterRole? = when {
            a == null -> b
            b == null -> a
            a.decidedAtMs != b.decidedAtMs ->
                if (a.decidedAtMs > b.decidedAtMs) a else b
            else -> if (a.holderDeviceId >= b.holderDeviceId) a else b
        }

        /**
         * The current writer releases the role to another device.
         *
         * Returns the decision to store **on the releasing device**, which
         * stops being the writer the instant it lands: the holder is now
         * someone else. The other device learns of it by importing a batch.
         */
        fun release(to: String, atMs: Long): WriterRole = WriterRole(to, atMs)

        /**
         * A device takes the role without being given it.
         *
         * **The one way to end up with two writers**, and the reason it is
         * behind a loud confirmation. It is here for when the device holding
         * the role is lost, broken or wiped, and the release it would have sent
         * can never arrive — without it that playlist could never be written
         * again.
         *
         * It wins by being later than whatever came before, which is the same
         * rule as every other decision; nothing special happens here. What is
         * special is that the old holder has not agreed, and if it is still
         * running and still believes it holds the role, both will write.
         */
        fun forceTakeOver(self: String, atMs: Long): WriterRole = WriterRole(self, atMs)
    }
}
