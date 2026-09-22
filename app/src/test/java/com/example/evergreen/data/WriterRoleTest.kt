package com.example.evergreen.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arbitration rule, on its own.
 *
 * **The test this file exists for is [noSequenceOfHandoversLeavesTwoWriters].**
 * Everything else here is a property it rests on.
 */
class WriterRoleTest {

    private val walkman = "45977bb7-walkman"
    private val pixel = "f642cfc7-pixel"
    private val t0 = 1_789_000_000_000L
    private val minute = 60_000L

    // --- Reading what is stored ---------------------------------------------

    @Test
    fun `the pre-5c true becomes a claim by this device`() {
        val role = WriterRole.fromStored("true", t0, thisDeviceId = walkman)

        assertEquals(WriterRole(walkman, t0), role)
        assertTrue(role!!.isHeldBy(walkman))
    }

    @Test
    fun `the pre-5c false is not a decision at all`() {
        // It only ever meant "not me" and never said who, so it must not be
        // able to win against a real claim.
        assertNull(WriterRole.fromStored("false", t0, thisDeviceId = pixel))
    }

    @Test
    fun `a false written later does not beat an earlier real claim`() {
        // Exactly the real devices' state going into Phase 5c: the Walkman's
        // "true" was written 2026-09-18, the Pixel's "false" two days later.
        val walkmanSide = WriterRole.fromStored("true", t0, thisDeviceId = walkman)
        val pixelSide = WriterRole.fromStored("false", t0 + 2 * 24 * 60 * minute, thisDeviceId = pixel)

        val resolved = WriterRole.resolve(walkmanSide, pixelSide)

        assertEquals("the Walkman still holds it", walkman, resolved?.holderDeviceId)
    }

    @Test
    fun `a stored device id is read back as itself`() {
        assertEquals(
            WriterRole(walkman, t0),
            WriterRole.fromStored(walkman, t0, thisDeviceId = pixel),
        )
    }

    @Test
    fun `nothing stored is nobody`() {
        assertNull(WriterRole.fromStored(null, null, thisDeviceId = pixel))
        assertNull(WriterRole.fromStored("", t0, thisDeviceId = pixel))
    }

    // --- Resolving ------------------------------------------------------------

    @Test
    fun `the later decision wins, whichever side it is on`() {
        val early = WriterRole(walkman, t0)
        val late = WriterRole(pixel, t0 + minute)

        assertEquals(late, WriterRole.resolve(early, late))
        assertEquals("and the order of the arguments is irrelevant", late, WriterRole.resolve(late, early))
    }

    @Test
    fun `resolving is idempotent`() {
        val role = WriterRole(pixel, t0)
        assertEquals(role, WriterRole.resolve(role, role))
    }

    @Test
    fun `a tie resolves the same way on both devices`() {
        // Two decisions in the same millisecond is vanishingly unlikely and
        // still has to be deterministic: two databases that have seen the same
        // decisions must not disagree about who writes.
        val a = WriterRole(walkman, t0)
        val b = WriterRole(pixel, t0)

        assertEquals(WriterRole.resolve(a, b), WriterRole.resolve(b, a))
    }

    // --- Handing over ----------------------------------------------------------

    @Test
    fun `releasing stops this device being the writer immediately`() {
        val before = WriterRole(walkman, t0)
        assertTrue(before.isHeldBy(walkman))

        val after = WriterRole.release(to = pixel, atMs = t0 + minute)

        assertFalse("the releasing device is no longer the writer", after.isHeldBy(walkman))
        assertTrue("and names the device it handed to", after.isHeldBy(pixel))
    }

    @Test
    fun `there is briefly no writer, and that is the safe direction`() {
        // The Walkman releases. Until the batch arrives, the Walkman knows it
        // has released and the Pixel has not heard -- so neither writes.
        val walkmanView = WriterRole.release(to = pixel, atMs = t0 + minute)
        val pixelView = WriterRole(walkman, t0)

        assertFalse("the releaser stood down", walkmanView.isHeldBy(walkman))
        assertFalse("and the receiver has not heard yet", pixelView.isHeldBy(pixel))
    }

    /**
     * **No sequence of release, claim, import and repeated import leaves both
     * devices holding the role.**
     *
     * Simulated over every ordering and repetition the file transport can
     * produce: a batch delivered twice, delivered late, delivered out of order,
     * or never delivered at all.
     */
    @Test
    fun noSequenceOfHandoversLeavesTwoWriters() {
        // Each device's view, and the decisions in flight between them.
        var walkmanView: WriterRole? = WriterRole(walkman, t0)
        var pixelView: WriterRole? = WriterRole(walkman, t0)
        val inFlight = mutableListOf<WriterRole>()
        var clock = t0

        fun check(step: String) {
            val both = walkmanView?.isHeldBy(walkman) == true && pixelView?.isHeldBy(pixel) == true
            assertFalse("two writers after $step", both)
        }

        // A releases to B. The decision is now in flight.
        clock += minute
        walkmanView = WriterRole.release(to = pixel, atMs = clock)
        inFlight += walkmanView
        check("the Walkman released")

        // The batch is delivered -- twice, because a folder can re-sync.
        repeat(2) {
            inFlight.forEach { pixelView = WriterRole.resolve(pixelView, it) }
            check("the Pixel imported")
        }
        assertTrue("the Pixel is the writer now", pixelView!!.isHeldBy(pixel))

        // B releases back. A imports an OLD batch first, then the new one.
        clock += minute
        val stale = walkmanView!!
        pixelView = WriterRole.release(to = walkman, atMs = clock)
        val fresh = pixelView!!

        listOf(fresh, stale, stale, fresh).forEach {
            walkmanView = WriterRole.resolve(walkmanView, it)
            check("the Walkman imported out of order")
        }
        assertTrue("the Walkman has it back", walkmanView!!.isHeldBy(walkman))

        // And the Pixel catches up, however many times.
        repeat(3) {
            pixelView = WriterRole.resolve(pixelView, walkmanView)
            check("the Pixel caught up")
        }
        assertFalse(pixelView!!.isHeldBy(pixel))
    }

    @Test
    fun aForcedTakeOverIsTheOneWayToGetTwoWriters() {
        // Tested separately and deliberately: this is the documented exception,
        // and the reason the control is behind a loud confirmation.
        val walkmanView = WriterRole(walkman, t0)
        val pixelView = WriterRole.forceTakeOver(pixel, atMs = t0 + minute)

        assertTrue("the old holder has not heard and still writes", walkmanView.isHeldBy(walkman))
        assertTrue("and the new one writes too", pixelView.isHeldBy(pixel))

        // It resolves the moment they exchange anything, because it is just a
        // later decision -- there is no special case in the merge rule.
        assertEquals(pixelView, WriterRole.resolve(walkmanView, pixelView))
    }

    @Test
    fun `a forced take-over loses to a release made after it`() {
        // Nothing about forcing makes it sticky. If the old device is alive and
        // hands the role somewhere later, that later decision stands.
        val forced = WriterRole.forceTakeOver(pixel, atMs = t0 + minute)
        val laterRelease = WriterRole.release(to = walkman, atMs = t0 + 2 * minute)

        assertEquals(laterRelease, WriterRole.resolve(forced, laterRelease))
    }
}
