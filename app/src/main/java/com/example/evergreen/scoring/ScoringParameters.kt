package com.example.evergreen.scoring

import java.security.MessageDigest

/**
 * Every number the scoring formula depends on, in one place.
 *
 * These are **settings, not constants** (decisions 1d). Changing one triggers a
 * recompute of the score cache and never a database migration, because a score
 * is computed from the stored facts every time rather than accumulated as plays
 * arrive (decisions 1a). That is the whole reason this class exists: if the
 * numbers were `const val`s scattered through the scorer, retuning them would
 * mean shipping a new build and the old scores would already have been folded
 * in under the old values, with no way to unwind them.
 *
 * Stored as one JSON string in the `sync_state` table, and copied verbatim into
 * every snapshot — without the parameters, a snapshot becomes unreadable the
 * first time the scoring is retuned (decisions 5).
 *
 * Pure Kotlin, no Android types, so the whole scoring package runs as plain JVM
 * unit tests.
 *
 * @param halfLifeDays how long a contribution takes to lose half its weight.
 *   Calibrated over 77,369 rows of real listening history: for liked tracks the
 *   odds of coming back within 90 days cross 50% at 45 days
 *   (`docs/PHASE3_DECISIONS.md` 3.1).
 * @param lowerBreakpoint fraction heard below which a listen is a rejection.
 *   Calibrated: 79% of cut-short plays heard less than this much.
 * @param upperBreakpoint fraction heard above which a listen is full credit.
 *   Calibrated, but a softer reading than the lower one — 0.80 is defensible
 *   and this is a setting precisely so it can be moved after seeing real scores.
 * @param negativeWeight play-equivalents contributed by a listen below
 *   [lowerBreakpoint]. Negative. Chosen, not calibrated.
 * @param likeWeight play-equivalents contributed by a like. Chosen. This is
 *   what replaces the "grace period" idea: a freshly liked song enters strong
 *   and fades if listening does not back it up, with no separate protected
 *   window to implement (decisions 2c).
 * @param replayJumpThresholdMs a backwards position jump at least this large is
 *   a deliberate replay; below it, player jitter. Capture cuts at 1000 ms and
 *   stores every jump's size, so this can be re-decided over history already
 *   recorded (decisions 2d).
 * @param dailyCapPlays the most one track can contribute in positive
 *   play-equivalents on one calendar day. Stops repeat-one overnight scoring 40
 *   plays, without needing loop detection (decisions 2e).
 * @param recordFloor score above which a liked track counts as "still alive"
 *   in the in-app record. Affects the record only, never the playlist.
 * @param playlistJoinRank a track joins the Evergreen playlist at this rank or
 *   better.
 * @param playlistLeaveRank a member leaves once its rank falls below this. The
 *   gap between the two is hysteresis: without it, tracks sitting either side
 *   of the cutoff would be added and removed on alternate days (decisions 4).
 */
data class ScoringParameters(
    val halfLifeDays: Double = 45.0,
    val lowerBreakpoint: Double = 0.20,
    val upperBreakpoint: Double = 0.85,
    val negativeWeight: Double = -0.5,
    val likeWeight: Double = 3.0,
    val replayJumpThresholdMs: Long = 10_000L,
    val dailyCapPlays: Double = 2.0,
    val recordFloor: Double = 0.1,
    val playlistJoinRank: Int = 100,
    val playlistLeaveRank: Int = 130,
) {

    /** [halfLifeDays] in milliseconds, which is what the decay actually uses. */
    val halfLifeMs: Double get() = halfLifeDays * MS_PER_DAY

    /**
     * How far back the ranker needs to load segments.
     *
     * Ten half-lives, where a contribution retains 2^-10 — under 0.1% — of its
     * weight. Bounding the working set is what keeps the NW-A307 usable with
     * six years of imported history in the table; `RecomputeTest` pins that the
     * truncation moves no score by more than 0.001.
     */
    val loadWindowMs: Long get() = (halfLifeMs * LOAD_WINDOW_HALF_LIVES).toLong()

    /**
     * A canonical JSON object: fixed key order, no whitespace.
     *
     * Canonical because [hash] is taken over this text and is what tells the
     * score cache that its rows were computed under different settings. Two
     * equal [ScoringParameters] must always produce byte-identical JSON.
     */
    fun toJson(): String = buildString {
        append('{')
        appendNumber(KEY_HALF_LIFE_DAYS, halfLifeDays.toString(), first = true)
        appendNumber(KEY_LOWER_BREAKPOINT, lowerBreakpoint.toString())
        appendNumber(KEY_UPPER_BREAKPOINT, upperBreakpoint.toString())
        appendNumber(KEY_NEGATIVE_WEIGHT, negativeWeight.toString())
        appendNumber(KEY_LIKE_WEIGHT, likeWeight.toString())
        appendNumber(KEY_REPLAY_JUMP_THRESHOLD_MS, replayJumpThresholdMs.toString())
        appendNumber(KEY_DAILY_CAP_PLAYS, dailyCapPlays.toString())
        appendNumber(KEY_RECORD_FLOOR, recordFloor.toString())
        appendNumber(KEY_PLAYLIST_JOIN_RANK, playlistJoinRank.toString())
        appendNumber(KEY_PLAYLIST_LEAVE_RANK, playlistLeaveRank.toString())
        append('}')
    }

    /**
     * A stable fingerprint of these settings.
     *
     * Stable across processes and installs, which `hashCode()` is not — the
     * score cache stores this alongside every row and rebuilds itself when it
     * stops matching, so a value that changed between runs would rebuild the
     * cache constantly or, worse, fail to rebuild it when it should.
     */
    fun hash(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toJson().toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * Rejects settings that would make the scorer produce nonsense.
     *
     * Worth having because Phase 3 lets the user edit this JSON by hand on the
     * debug screen. A half-life of zero would divide by zero and a NaN anywhere
     * would quietly turn every score into NaN, which sorts unpredictably and
     * would look like a scoring bug rather than a typo.
     *
     * @return the problems found, empty when the settings are usable.
     */
    fun problems(): List<String> = buildList {
        if (!halfLifeDays.isFinite() || halfLifeDays <= 0.0) {
            add("$KEY_HALF_LIFE_DAYS must be a positive number, was $halfLifeDays")
        }
        if (!lowerBreakpoint.isFinite() || lowerBreakpoint < 0.0 || lowerBreakpoint > 1.0) {
            add("$KEY_LOWER_BREAKPOINT must be between 0 and 1, was $lowerBreakpoint")
        }
        if (!upperBreakpoint.isFinite() || upperBreakpoint < 0.0 || upperBreakpoint > 1.0) {
            add("$KEY_UPPER_BREAKPOINT must be between 0 and 1, was $upperBreakpoint")
        }
        if (lowerBreakpoint.isFinite() && upperBreakpoint.isFinite() && lowerBreakpoint >= upperBreakpoint) {
            add("$KEY_LOWER_BREAKPOINT ($lowerBreakpoint) must be below $KEY_UPPER_BREAKPOINT ($upperBreakpoint)")
        }
        if (!negativeWeight.isFinite() || negativeWeight > 0.0) {
            add("$KEY_NEGATIVE_WEIGHT must be zero or negative, was $negativeWeight")
        }
        if (!likeWeight.isFinite() || likeWeight < 0.0) {
            add("$KEY_LIKE_WEIGHT must be zero or positive, was $likeWeight")
        }
        if (replayJumpThresholdMs < 0L) {
            add("$KEY_REPLAY_JUMP_THRESHOLD_MS must not be negative, was $replayJumpThresholdMs")
        }
        if (!dailyCapPlays.isFinite() || dailyCapPlays <= 0.0) {
            add("$KEY_DAILY_CAP_PLAYS must be a positive number, was $dailyCapPlays")
        }
        if (!recordFloor.isFinite() || recordFloor < 0.0) {
            add("$KEY_RECORD_FLOOR must be zero or positive, was $recordFloor")
        }
        if (playlistJoinRank < 1) {
            add("$KEY_PLAYLIST_JOIN_RANK must be at least 1, was $playlistJoinRank")
        }
        if (playlistLeaveRank < playlistJoinRank) {
            add(
                "$KEY_PLAYLIST_LEAVE_RANK ($playlistLeaveRank) must be at least " +
                    "$KEY_PLAYLIST_JOIN_RANK ($playlistJoinRank)",
            )
        }
    }

    private fun StringBuilder.appendNumber(key: String, value: String, first: Boolean = false) {
        if (!first) append(',')
        append('"').append(key).append("\":").append(value)
    }

    companion object {
        const val MS_PER_DAY: Double = 24.0 * 60.0 * 60.0 * 1000.0

        /** See [loadWindowMs]. */
        const val LOAD_WINDOW_HALF_LIVES: Int = 10

        const val KEY_HALF_LIFE_DAYS = "halfLifeDays"
        const val KEY_LOWER_BREAKPOINT = "lowerBreakpoint"
        const val KEY_UPPER_BREAKPOINT = "upperBreakpoint"
        const val KEY_NEGATIVE_WEIGHT = "negativeWeight"
        const val KEY_LIKE_WEIGHT = "likeWeight"
        const val KEY_REPLAY_JUMP_THRESHOLD_MS = "replayJumpThresholdMs"
        const val KEY_DAILY_CAP_PLAYS = "dailyCapPlays"
        const val KEY_RECORD_FLOOR = "recordFloor"
        const val KEY_PLAYLIST_JOIN_RANK = "playlistJoinRank"
        const val KEY_PLAYLIST_LEAVE_RANK = "playlistLeaveRank"

        /**
         * Reads settings from JSON, throwing on anything unusable.
         *
         * **Unknown keys are ignored and missing keys take their default.**
         * Both matter for snapshots: one written by an older build will lack
         * any parameter added since, and filling it with today's default is
         * right, because that older build behaved as though the default were in
         * force. The reverse — a key this build no longer knows — is ignored
         * rather than being an error, so an old snapshot stays readable.
         *
         * @throws IllegalArgumentException if the text is not a flat JSON
         *   object, if one of the keys above holds something that is not a
         *   number, or if the result fails [problems].
         */
        fun fromJson(text: String): ScoringParameters {
            val values = parseFlatJsonObject(text)
            val defaults = ScoringParameters()
            val parsed = ScoringParameters(
                halfLifeDays = values.double(KEY_HALF_LIFE_DAYS, defaults.halfLifeDays),
                lowerBreakpoint = values.double(KEY_LOWER_BREAKPOINT, defaults.lowerBreakpoint),
                upperBreakpoint = values.double(KEY_UPPER_BREAKPOINT, defaults.upperBreakpoint),
                negativeWeight = values.double(KEY_NEGATIVE_WEIGHT, defaults.negativeWeight),
                likeWeight = values.double(KEY_LIKE_WEIGHT, defaults.likeWeight),
                replayJumpThresholdMs = values.long(
                    KEY_REPLAY_JUMP_THRESHOLD_MS,
                    defaults.replayJumpThresholdMs,
                ),
                dailyCapPlays = values.double(KEY_DAILY_CAP_PLAYS, defaults.dailyCapPlays),
                recordFloor = values.double(KEY_RECORD_FLOOR, defaults.recordFloor),
                playlistJoinRank = values.int(KEY_PLAYLIST_JOIN_RANK, defaults.playlistJoinRank),
                playlistLeaveRank = values.int(KEY_PLAYLIST_LEAVE_RANK, defaults.playlistLeaveRank),
            )
            val problems = parsed.problems()
            require(problems.isEmpty()) { problems.joinToString(separator = "; ") }
            return parsed
        }

        /**
         * [fromJson], but falling back to the defaults rather than throwing.
         *
         * For the startup path, where refusing to score because a stored string
         * is malformed would be worse than scoring with the shipped defaults.
         * The debug screen's editor uses [fromJson] so it can show the user
         * what is wrong with what they typed.
         */
        fun fromJsonOrDefault(text: String?): ScoringParameters =
            text?.let { runCatching { fromJson(it) }.getOrNull() } ?: ScoringParameters()

        private fun Map<String, Double?>.double(key: String, fallback: Double): Double {
            if (!containsKey(key)) return fallback
            return get(key) ?: throw IllegalArgumentException("\"$key\" must be a number")
        }

        private fun Map<String, Double?>.long(key: String, fallback: Long): Long =
            double(key, fallback.toDouble()).toLong()

        private fun Map<String, Double?>.int(key: String, fallback: Int): Int =
            double(key, fallback.toDouble()).toInt()
    }
}

/**
 * Parses a flat JSON object into its numeric values.
 *
 * Hand-written rather than using `org.json`, which is a stub on the JVM unit
 * test classpath and would throw `RuntimeException: Stub!` — and the scoring
 * package is deliberately pure Kotlin so that all of it is testable without a
 * device. No third-party JSON dependency is added for ten numbers.
 *
 * A key whose value is not a number maps to `null` ("present, but not a
 * number"), which lets the caller distinguish it from a key that is absent.
 * Nested objects and arrays are skipped rather than rejected, so an unrecognised
 * structured key does not stop the settings being read.
 */
internal fun parseFlatJsonObject(text: String): Map<String, Double?> {
    val cursor = JsonCursor(text)
    val values = mutableMapOf<String, Double?>()
    cursor.expect('{')
    if (cursor.peek() == '}') {
        cursor.advance()
    } else {
        while (true) {
            val key = cursor.readString()
            cursor.expect(':')
            values[key] = cursor.readValueAsNumberOrNull()
            when (val next = cursor.peek()) {
                ',' -> cursor.advance()
                '}' -> {
                    cursor.advance()
                    break
                }
                else -> cursor.fail("expected ',' or '}' but found '$next'")
            }
        }
    }
    cursor.skipWhitespace()
    if (!cursor.atEnd()) cursor.fail("unexpected trailing text")
    return values
}

/** A position in a JSON string, with just enough reading to serve [parseFlatJsonObject]. */
private class JsonCursor(private val text: String) {
    private var index = 0

    fun atEnd(): Boolean = index >= text.length

    fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    fun peek(): Char {
        skipWhitespace()
        if (atEnd()) fail("unexpected end of input")
        return text[index]
    }

    fun advance() {
        index++
    }

    fun expect(expected: Char) {
        val found = peek()
        if (found != expected) fail("expected '$expected' but found '$found'")
        advance()
    }

    fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (atEnd()) fail("unterminated string")
            when (val c = text[index++]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (atEnd()) fail("unterminated escape")
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) fail("truncated unicode escape")
                            val hex = text.substring(index, index + 4)
                            val code = hex.toIntOrNull(radix = 16) ?: fail("bad unicode escape '$hex'")
                            out.append(code.toChar())
                            index += 4
                        }
                        else -> fail("unsupported escape '\\$escaped'")
                    }
                }
                else -> out.append(c)
            }
        }
    }

    /** Reads one value, returning it as a number or null when it is not one. */
    fun readValueAsNumberOrNull(): Double? = when (val c = peek()) {
        '"' -> {
            readString()
            null
        }
        '{', '[' -> {
            skipStructure()
            null
        }
        't' -> {
            expectWord("true")
            null
        }
        'f' -> {
            expectWord("false")
            null
        }
        'n' -> {
            expectWord("null")
            null
        }
        else -> if (c == '-' || c == '+' || c.isDigit()) readNumber() else fail("unexpected '$c'")
    }

    private fun readNumber(): Double {
        skipWhitespace()
        val start = index
        while (index < text.length && (text[index].isDigit() || text[index] in "+-.eE")) index++
        val literal = text.substring(start, index)
        return literal.toDoubleOrNull() ?: fail("'$literal' is not a number")
    }

    private fun expectWord(word: String) {
        skipWhitespace()
        if (!text.startsWith(word, index)) fail("expected '$word'")
        index += word.length
    }

    /** Skips a nested object or array, honouring strings so braces inside them do not count. */
    private fun skipStructure() {
        var depth = 0
        do {
            when (peek()) {
                '{', '[' -> {
                    depth++
                    advance()
                }
                '}', ']' -> {
                    depth--
                    advance()
                }
                '"' -> readString()
                else -> advance()
            }
        } while (depth > 0)
    }

    fun fail(message: String): Nothing =
        throw IllegalArgumentException("$message at character ${index + 1}")
}
