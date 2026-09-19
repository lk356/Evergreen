package com.example.evergreen.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.evergreen.scoring.ScoringParameters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The parameter change log: what it records, and what it refuses to.
 *
 * The log exists because the value in force lives in one `sync_state` row that
 * every save overwrites. So the guarantee worth testing is not "a row appears"
 * but "the row that appears says what was true before as well as after", and
 * that the two writes cannot come apart.
 */
@RunWith(AndroidJUnit4::class)
class ParameterHistoryTest {

    private lateinit var db: EvergreenDatabase

    /** 2026-09-18T12:00:00Z. */
    private val t0 = 1_789_214_400_000L
    private val day = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EvergreenDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun dao() = db.scoringDao()

    @Test
    fun aFreshDatabaseHasNoHistory() = runBlocking {
        assertEquals(0, dao().parameterHistoryCount())
        assertTrue(dao().parameterHistoryOnce().isEmpty())
        // And the settings in force are the shipped defaults, unrecorded
        // because nothing has happened to them yet.
        assertEquals(ScoringParameters(), dao().scoringParameters())
    }

    /**
     * The first change is the one that could lose something.
     *
     * Until it happens the settings in force are the build's defaults, a fact
     * recorded nowhere; the instant it lands, that fact stops being true. So
     * the first row's `previousJson` must hold the defaults rather than null.
     */
    @Test
    fun theFirstChangeRecordsTheDefaultsAsWhatCameBefore() = runBlocking {
        val tuned = ScoringParameters(negativeWeight = -0.25)
        assertTrue(dao().putScoringParameters(tuned, t0))

        val log = dao().parameterHistoryOnce()
        assertEquals(1, log.size)
        assertEquals(t0, log[0].changedAt)
        assertEquals(tuned.toJson(), log[0].parametersJson)
        assertEquals(tuned.hash(), log[0].parametersHash)
        assertEquals(
            "the defaults were in force and are recorded as such",
            ScoringParameters().toJson(),
            log[0].previousJson,
        )
        assertEquals(tuned, dao().scoringParameters())
    }

    @Test
    fun eachChangeAppendsARowAndNothingIsEverOverwritten() = runBlocking {
        val first = ScoringParameters(negativeWeight = -0.25)
        val second = ScoringParameters(negativeWeight = -0.25, halfLifeDays = 60.0)
        val third = ScoringParameters(negativeWeight = -0.25, halfLifeDays = 60.0, likeWeight = 4.0)

        assertTrue(dao().putScoringParameters(first, t0))
        assertTrue(dao().putScoringParameters(second, t0 + day))
        assertTrue(dao().putScoringParameters(third, t0 + 2 * day))

        // Newest first, which is the order the settings screen reads it in.
        val log = dao().parameterHistoryOnce()
        assertEquals(3, log.size)
        assertEquals(third.toJson(), log[0].parametersJson)
        assertEquals(second.toJson(), log[1].parametersJson)
        assertEquals(first.toJson(), log[2].parametersJson)

        // Every row's predecessor is the row before it. A gap here would mean
        // a change had been recorded without what it replaced.
        assertEquals(second.toJson(), log[0].previousJson)
        assertEquals(first.toJson(), log[1].previousJson)
        assertEquals(ScoringParameters().toJson(), log[2].previousJson)
    }

    /**
     * Opening the settings screen and closing it again is not a change.
     *
     * Without this, every visit that saved the values already on screen would
     * add a row, and a log of no-ops is a log nobody reads — and worse, it
     * would make "when did the scoring actually change" unanswerable, which is
     * the single question the table exists for.
     */
    @Test
    fun savingTheSettingsAlreadyInForceRecordsNothing() = runBlocking {
        val tuned = ScoringParameters(upperBreakpoint = 0.80)
        assertTrue(dao().putScoringParameters(tuned, t0))
        assertFalse(dao().putScoringParameters(tuned, t0 + day))
        assertFalse(dao().putScoringParameters(tuned.copy(), t0 + 2 * day))

        assertEquals(1, dao().parameterHistoryCount())
        assertEquals(t0, dao().parameterHistoryOnce().single().changedAt)
    }

    /** Saving the defaults on a fresh database is also not a change. */
    @Test
    fun savingTheDefaultsOnAFreshDatabaseRecordsNothing() = runBlocking {
        assertFalse(dao().putScoringParameters(ScoringParameters(), t0))
        assertEquals(0, dao().parameterHistoryCount())
    }

    /**
     * Settings that would make the scorer produce nonsense are refused, and
     * refusing must leave neither the value nor the log touched.
     */
    @Test
    fun anInvalidChangeWritesNeitherTheValueNorALogEntry() = runBlocking {
        val good = ScoringParameters(halfLifeDays = 60.0)
        dao().putScoringParameters(good, t0)

        val failed = runCatching {
            dao().putScoringParameters(ScoringParameters(halfLifeDays = 0.0), t0 + day)
        }
        assertTrue("a half-life of zero must be refused", failed.isFailure)

        assertEquals(good, dao().scoringParameters())
        assertEquals(1, dao().parameterHistoryCount())
    }

    /**
     * "What were the settings on this date" — the question the log makes
     * answerable, and the one that unlocks recomputing a past ranking.
     */
    @Test
    fun theSettingsInForceAtAPastInstantCanBeReadBack() = runBlocking {
        val first = ScoringParameters(halfLifeDays = 60.0)
        val second = ScoringParameters(halfLifeDays = 30.0)
        dao().putScoringParameters(first, t0)
        dao().putScoringParameters(second, t0 + 10 * day)

        assertEquals(first.toJson(), dao().parametersInForceAt(t0)?.parametersJson)
        assertEquals(first.toJson(), dao().parametersInForceAt(t0 + 5 * day)?.parametersJson)
        assertEquals(second.toJson(), dao().parametersInForceAt(t0 + 10 * day)?.parametersJson)
        assertEquals(second.toJson(), dao().parametersInForceAt(t0 + 99 * day)?.parametersJson)

        // Before the log begins there is no row, and the caller falls back to
        // the earliest row's predecessor — which is why previousJson is kept.
        assertNull(dao().parametersInForceAt(t0 - day))
        val earliest = dao().earliestParameterChange()
        assertNotNull(earliest)
        assertEquals(ScoringParameters().toJson(), earliest!!.previousJson)
    }

    /**
     * A recorded hash matches the one the score cache stamps its rows with, so
     * "which settings produced the numbers on screen" is a lookup rather than a
     * reconstruction.
     */
    @Test
    fun theRecordedHashMatchesTheOneTheScoreCacheUses() = runBlocking {
        val tuned = ScoringParameters(likeWeight = 5.0)
        dao().putScoringParameters(tuned, t0)

        val row = dao().parameterHistoryOnce().single()
        assertEquals(dao().scoringParameters().hash(), row.parametersHash)
        assertEquals(
            ScoringParameters.fromJson(row.parametersJson).hash(),
            row.parametersHash,
        )
    }
}
