package io.github.purplepalm.custommaps.nav.lanes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The drive's cache of the horizon's edges ([HorizonEdges]): invented ids and edges. */
class HorizonEdgesTest {

    private fun edge(lanes: Int?) = HorizonEdge(100.0, lanes, motorway = true, ramp = false, oneWay = true, roadClass = HorizonRoadClass.MOTORWAY)

    /** A read that counts its calls, with no metadata for [none]. */
    private class Reads(val none: Set<Long> = emptySet(), val throwsAt: Long? = null) {
        val asked = ArrayList<Long>()

        fun read(id: Long): HorizonEdge? {
            asked.add(id)
            if (id == throwsAt) throw IllegalStateException("no read")
            return if (id in none) null else HorizonEdge(id.toDouble(), 4, motorway = true, ramp = false, oneWay = true, roadClass = HorizonRoadClass.TRUNK)
        }
    }

    @Test
    fun `each edge is read once, one without metadata too`() {
        val cache = HorizonEdges()
        val reads = Reads(none = setOf(2L))

        val first = cache.edges(longArrayOf(1L, 2L, 3L), reads::read)
        val again = cache.edges(longArrayOf(2L, 3L, 4L), reads::read)

        assertEquals(listOf(1L, 2L, 3L, 4L), reads.asked)
        assertEquals(listOf(1.0, null, 3.0), first.map { it?.lengthM })
        assertEquals(listOf(null, 3.0, 4.0), again.map { it?.lengthM })
        assertEquals(4, cache.reads)
        assertEquals(4, cache.size)
    }

    @Test
    fun `a cleared cache reads its edges again`() {
        val cache = HorizonEdges()
        val reads = Reads()
        cache.edges(longArrayOf(7L), reads::read)
        cache.clear()
        cache.edges(longArrayOf(7L), reads::read)
        assertEquals(listOf(7L, 7L), reads.asked)
        assertEquals(2, cache.reads)
    }

    @Test
    fun `a read that throws keeps what was read before it`() {
        val cache = HorizonEdges()
        val reads = Reads(throwsAt = 3L)
        assertThrows<IllegalStateException> { cache.edges(longArrayOf(1L, 2L, 3L), reads::read) }
        assertEquals(2, cache.size)
        cache.edges(longArrayOf(1L, 2L), reads::read)
        assertEquals(listOf(1L, 2L, 3L), reads.asked)
    }

    @Test
    fun `the cache starts over once it holds its most edges, and not before`() {
        val max = HorizonEdges.MAX_EDGES
        assertEquals(10_000, max)
        val cache = HorizonEdges()
        val all = LongArray(max) { it.toLong() }
        cache.edges(all, { edge(it.toInt() % 6) })
        assertEquals(max, cache.size)
        // Held: no read.
        cache.edges(longArrayOf(0L), { throw AssertionError("read again") })
        // One more: the cache starts over and holds the new one alone.
        cache.edges(longArrayOf(max.toLong()), { edge(null) })
        assertEquals(1, cache.size)
        assertNull(cache.edges(longArrayOf(max.toLong()), { throw AssertionError("read again") }).single()?.laneCount)
        // One short of the most: nothing is forgotten.
        val short = HorizonEdges()
        short.edges(LongArray(max - 1) { it.toLong() }, { edge(5) })
        short.edges(longArrayOf(-1L), { edge(5) })
        assertEquals(max, short.size)
    }
}
