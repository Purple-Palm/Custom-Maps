package io.github.purplepalm.custommaps.nav.lanes

/**
 * The metadata of the electronic horizon's edges as a drive has read it: each edge once, by its id ([edges]), kept in
 * memory for the drive. An edge id names an edge only in one version of the road data, so the cache is cleared at every
 * new horizon ([clear]); it is cleared too once it holds [MAX_EDGES] edges, so a long drive keeps no more than that. The
 * ids never leave this object: nothing here is logged or stored. Not thread-safe: the engine uses it on its route lines'
 * worker alone.
 */
class HorizonEdges {

    /** The edges read, by id; an edge the horizon gave no metadata for maps to null, so it is not asked for again. */
    private val known = HashMap<Long, HorizonEdge?>()

    /** How many edges were read since the cache was made: one call of the read each. */
    var reads: Int = 0
        private set

    /** How many edges the cache holds. */
    val size: Int get() = known.size

    /**
     * What the horizon says of each edge of [ids], in their order: from the cache, or by [read] for an edge not read yet
     * this drive, which is then kept, null too. [read] may throw: what was read before stays.
     */
    fun edges(ids: LongArray, read: (Long) -> HorizonEdge?): List<HorizonEdge?> {
        val out = ArrayList<HorizonEdge?>(ids.size)
        for (id in ids) {
            if (known.containsKey(id)) {
                out.add(known[id])
                continue
            }
            if (known.size >= MAX_EDGES) known.clear()
            val edge = read(id)
            reads++
            known[id] = edge
            out.add(edge)
        }
        return out
    }

    /** Forgets every edge read: at a new horizon, whose ids may be of another version of the road data, and at a drive's end. */
    fun clear() {
        known.clear()
    }

    companion object {
        /**
         * The most edges the cache keeps before it starts over: about a thousand kilometres of road at the length of a
         * freeway's edges, far more than a horizon of [HorizonAhead.HORIZON_AHEAD_M] holds at a time.
         */
        const val MAX_EDGES = 10_000
    }
}
