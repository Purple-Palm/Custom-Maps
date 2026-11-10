package io.github.purplepalm.custommaps.nav.guidance

/**
 * Compares routes by their main roads. The routing service names them in a route's summary: a few
 * road names joined by commas, such as "Oak Street, Pine Avenue". Two routes that were requested at
 * different times, or from different places, are never the same route, but with the same main roads
 * they are the same way to the driver.
 */
object MainRoads {

    /** The road names of [summary], without the space around them and in lower case; none for no summary. */
    fun of(summary: String?): Set<String> {
        if (summary.isNullOrBlank()) return emptySet()
        val names = LinkedHashSet<String>()
        for (part in summary.split(SEPARATOR)) {
            val name = part.trim().lowercase()
            if (name.isNotEmpty()) names.add(name)
        }
        return names
    }

    /** How many of the main roads of [chosen] the route with [summary] has as well. */
    fun shared(chosen: String?, summary: String?): Int {
        val wanted = of(chosen)
        if (wanted.isEmpty()) return 0
        return of(summary).count { it in wanted }
    }

    /**
     * [routes] with the one first that has the most main roads of the [chosen] route, and the
     * others after it in the order they came. Of two that have as many, the earlier one wins, which
     * is the one the routing service ranks higher. When no route has any of those roads, or the
     * chosen route has none, the order stays as it came.
     *
     * @param chosen the summary of the route the driver started on
     * @param summary reads the summary of a route
     */
    fun <T> closestFirst(chosen: String?, routes: List<T>, summary: (T) -> String?): List<T> {
        val wanted = of(chosen)
        if (wanted.isEmpty() || routes.size < 2) return routes
        var best = 0
        var bestShared = 0
        for (index in routes.indices) {
            val shared = of(summary(routes[index])).count { it in wanted }
            if (shared > bestShared) {
                best = index
                bestShared = shared
            }
        }
        if (best == 0) return routes
        val ordered = ArrayList<T>(routes.size)
        ordered.add(routes[best])
        for (index in routes.indices) if (index != best) ordered.add(routes[index])
        return ordered
    }

    private const val SEPARATOR = ","
}
