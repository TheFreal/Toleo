package de.freal.toleo.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeightedProgressionTest {
    // Like an ePubLibre book: short cover, blurb, title and dedication pages, then long chapters.
    private val counts = listOf(1, 1, 1, 1, 30, 30, 30, 30, 30, 30, 30, 30, 30)

    @Test fun shortFrontMatterBarelyCounts() {
        // A third into the first chapter of ~274 pages is about page 14, i.e. 5 %, not the
        // (4 + 1/3) / 13 = 33 % that counting files as equal would give.
        assertEquals(14.0 / 274, weightedProgression(counts, 4, 1.0 / 3)!!, 1e-9)
    }

    @Test fun endsOfTheBook() {
        assertEquals(0.0, weightedProgression(counts, 0, 0.0)!!, 1e-9)
        assertEquals(1.0, weightedProgression(counts, counts.lastIndex, 1.0)!!, 1e-9)
    }

    @Test fun movesSmoothlyWithinAResource() {
        val a = weightedProgression(counts, 5, 0.10)!!
        val b = weightedProgression(counts, 5, 0.11)!!
        assert(b > a)
    }

    @Test fun nullWhenUnknown() {
        assertNull(weightedProgression(counts, -1, 0.5))
        assertNull(weightedProgression(counts, counts.size, 0.5))
        assertNull(weightedProgression(listOf(0, 0), 0, 0.5))
    }
}
