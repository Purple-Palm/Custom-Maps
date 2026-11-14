package io.github.purplepalm.custommaps.nav.lanes

import io.github.purplepalm.custommaps.nav.debug.Motion

/**
 * The "side_roads" line of the debug log, written while Debug's switch Side-road fields on routes is on: one for each
 * route of a preview and each primary route a drive uses ([Reason]), in counts, sizes and kinds only. It tells whether
 * Mapbox sent the two fields the switch asks for (each road's access, which says whether it is one-way and which way, and
 * its form of way, at every intersection of the route), how their values fall, and how much of the route's answer they
 * take. Never a coordinate, a shape, a road's name or ref, an instruction or banner text: what the route holds stays in
 * memory with it and is never stored.
 *
 * The access of a road, as the route gives it: 0 none, -1 forward, 1 backward, 2 both. Which way forward is, the Directions
 * models do not say; a road the route drives on can be driven its way, which a drive's counts of [Fields.OWN_ROAD] show.
 */
object SideRoadsLine {

    const val TYPE = "side_roads"

    /** Why a route gets its line. */
    enum class Reason(
        /** How the line names it. */
        val logName: String,
    ) {
        /** A route of a preview's answer: each route of it, the one shown first and the others. */
        PREVIEW("preview"),

        /** The route a drive starts on, once the navigator has it. */
        START("start"),

        /** A new primary route the navigator put in place of the one the car left. */
        REROUTE("reroute"),

        /** Any other new primary route of a drive: the start's replan, another route taken. */
        NEW_ROUTE("new_route"),
        ;

        companion object {
            /** The reason of a new primary route of a drive, a [reroute] or not. */
            fun ofRouteChange(reroute: Boolean): Reason = if (reroute) REROUTE else NEW_ROUTE
        }
    }

    /** One intersection of the route as the line counts it, in plain values. */
    class Node(
        /** The kind of road that leaves the intersection, as the lane evidence reads it ([RoadKind]). */
        val road: RoadKind,
        /** The maneuver its step begins with, at a step's first intersection; null where the route goes through. */
        val maneuver: NodeManeuver?,
        /** How many roads meet there: the route's bearings of it. */
        val roads: Int,
        /** Each road's access, in the order of the bearings, as the route gives it; null where the route gives none. */
        val access: List<Int?>?,
        /** Each road's form of way, in the order of the bearings, as the route gives it; null where the route gives none. */
        val formOfWay: List<String?>?,
        /** Which of the roads the route comes in on; null at its start. */
        val inIndex: Int? = null,
        /** Which of the roads the route leaves on; null at its end. */
        val outIndex: Int? = null,
    )

    /** Whether the request of the route asked for each of the two fields, as the route's own options say. */
    class Asked(val access: Boolean, val formOfWay: Boolean)

    /** The names of the line's fields. */
    object Fields {
        const val ROUTE_ID = "routeId"
        const val REASON = "reason"
        const val LINK_ACCESS = "linkAccess"
        const val LINK_FORM_OF_WAY = "linkFormOfWay"
        const val INTERSECTIONS = "intersections"
        const val WITH_ACCESS = "withAccess"
        const val WITH_FORM_OF_WAY = "withFormOfWay"
        const val ACCESS = "access"
        const val FORM_OF_WAY = "formOfWay"
        const val STREET_MANEUVERS = "streetManeuvers"
        const val OWN_ROAD = "streetOwnRoad"
        const val JSON_BYTES = "jsonBytes"
        const val FIELDS_BYTES = "fieldsBytes"
        const val GROWTH_PCT = "growthPct"
    }

    /** The words of a form of way the Directions models list, in their order; any other word counts as [OTHER]. */
    val FORM_OF_WAY_WORDS: List<String> = listOf(
        "freeway",
        "multiple_carriageway",
        "single_carriageway",
        "roundabout",
        "ramp",
        "service_road",
        "pedestrian_zone",
        "unknown",
    )

    /** The access values the Directions models list, by the name the line gives each; any other value counts as [OTHER]. */
    val ACCESS_VALUES: Map<Int, String> = linkedMapOf(0 to "none", -1 to "forward", 1 to "backward", 2 to "both")

    /** A value the Directions models do not list. */
    const val OTHER = "other"

    /** A road the route gives no value for: the field missing at its intersection, or a list too short or with a gap. */
    const val MISSING = "missing"

    /** The decimals of [Fields.GROWTH_PCT]. */
    const val GROWTH_DECIMALS = 1

    /**
     * The line's fields for the route [routeId], given its line for [reason]:
     * - "routeId", "reason" ([Reason.logName]);
     * - "linkAccess" and "linkFormOfWay": whether the route's own request asked for each field ([asked]); null where the
     *   route carries no options;
     * - "intersections", every intersection of the route, and of them "withAccess" and "withFormOfWay", how many came with
     *   each field;
     * - "access": every road at every intersection by its access ([ACCESS_VALUES], [OTHER], [MISSING]); "formOfWay": the
     *   same by the word of its form of way ([FORM_OF_WAY_WORDS], [OTHER], [MISSING]);
     * - "streetManeuvers": the maneuvers the camera frames as a street junction ([CardWindows.framesStreetManeuver]),
     *   "total", and of them "withAccess", those whose approach road, the one the route comes in on, has an access value;
     * - "streetOwnRoad": at every intersection a street leaves, the road the route leaves on by its access: "oneWay"
     *   (forward or backward), "twoWay" (both), "none", [OTHER] or [MISSING];
     * - "jsonBytes", the route's answer as the app holds it, in bytes of its JSON ([jsonBytes]), null where it could not
     *   be measured; "fieldsBytes", the bytes of that JSON that are the two fields ([fieldBytes]), so the same route
     *   without them is the difference; "growthPct", how much larger the route is with them, in percent of it without
     *   them, null without a size.
     */
    fun fields(routeId: String, reason: Reason, asked: Asked?, nodes: List<Node>, jsonBytes: Long?): Map<String, Any?> {
        val access = LinkedHashMap<String, Int>()
        for (name in ACCESS_VALUES.values) access[name] = 0
        access[OTHER] = 0
        access[MISSING] = 0
        val formOfWay = LinkedHashMap<String, Int>()
        for (word in FORM_OF_WAY_WORDS) formOfWay[word] = 0
        formOfWay[OTHER] = 0
        formOfWay[MISSING] = 0
        var withAccess = 0
        var withFormOfWay = 0
        var fieldsBytes = 0L
        for (node in nodes) {
            if (!node.access.isNullOrEmpty()) withAccess++
            if (!node.formOfWay.isNullOrEmpty()) withFormOfWay++
            for (r in 0 until maxOf(node.roads, node.access?.size ?: 0)) access.add(accessName(node.access?.getOrNull(r)))
            for (r in 0 until maxOf(node.roads, node.formOfWay?.size ?: 0)) formOfWay.add(formOfWayName(node.formOfWay?.getOrNull(r)))
            fieldsBytes += fieldBytes(node.access, node.formOfWay)
        }
        return linkedMapOf(
            Fields.ROUTE_ID to routeId,
            Fields.REASON to reason.logName,
            Fields.LINK_ACCESS to asked?.access,
            Fields.LINK_FORM_OF_WAY to asked?.formOfWay,
            Fields.INTERSECTIONS to nodes.size,
            Fields.WITH_ACCESS to withAccess,
            Fields.WITH_FORM_OF_WAY to withFormOfWay,
            Fields.ACCESS to access,
            Fields.FORM_OF_WAY to formOfWay,
            Fields.STREET_MANEUVERS to streetManeuvers(nodes),
            Fields.OWN_ROAD to streetOwnRoad(nodes),
            Fields.JSON_BYTES to jsonBytes,
            Fields.FIELDS_BYTES to fieldsBytes,
            Fields.GROWTH_PCT to growthPercent(jsonBytes, fieldsBytes),
        )
    }

    /** How the line names an access value. */
    fun accessName(value: Int?): String = if (value == null) MISSING else ACCESS_VALUES[value] ?: OTHER

    /** How the line names a form of way; never a word the Directions models do not list. */
    fun formOfWayName(value: String?): String = when (value) {
        null -> MISSING
        in FORM_OF_WAY_WORDS -> value
        else -> OTHER
    }

    /**
     * How much larger a route of [jsonBytes] is with the two fields that take [fieldsBytes] of it than without them, in
     * percent of it without them, to [GROWTH_DECIMALS] decimals; null without a size, or with one the fields do not fit in.
     */
    fun growthPercent(jsonBytes: Long?, fieldsBytes: Long): Double? {
        if (jsonBytes == null || jsonBytes <= fieldsBytes) return null
        return Motion.round(PERCENT * fieldsBytes / (jsonBytes - fieldsBytes), GROWTH_DECIMALS)
    }

    /**
     * The bytes that an intersection's two fields take in the JSON the Directions models write of it: for each list that is
     * there, its member, `,"access":[-1,2]` or `,"form_of_way":["ramp","single_carriageway"]`, with the comma before it,
     * each number or word as the models write it ([jsonStringBytes]), a gap as `null`. Without either list, 0.
     */
    fun fieldBytes(access: List<Int?>?, formOfWay: List<String?>?): Long {
        var bytes = 0L
        if (access != null) {
            bytes += memberBytes(ACCESS_KEY, access.size) + access.sumOf { (it?.toString()?.length ?: NULL_BYTES).toLong() }
        }
        if (formOfWay != null) {
            bytes += memberBytes(FORM_OF_WAY_KEY, formOfWay.size) + formOfWay.sumOf { if (it == null) NULL_BYTES.toLong() else jsonStringBytes(it) }
        }
        return bytes
    }

    /** The bytes of a list member named [key] with [count] values around them: the comma before it, its name, the colon, the brackets and the commas between the values. */
    private fun memberBytes(key: String, count: Int): Long = 1L + jsonStringBytes(key) + 1 + 2 + maxOf(count - 1, 0)

    /**
     * The bytes of [text] written as a JSON string the way the Directions models write it (Gson, with its HTML escapes):
     * its quotes, `\"` and `\\`, `\t` `\b` `\n` `\r` `\f`, a six-character escape for every other control character, for
     * `<`, `>`, `&`, `=`, `'` and for the line and paragraph separators, and every other character in UTF-8.
     */
    fun jsonStringBytes(text: String): Long {
        var bytes = 2L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c == '"' || c == '\\' -> 2
                c == '\t' || c == '\b' || c == '\n' || c == '\r' || c == '\u000C' -> 2
                c < ' ' || c in HTML_ESCAPED || c == ' ' || c == ' ' -> UNICODE_ESCAPE_BYTES
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    i++
                    4
                }
                else -> utf8Bytes(c)
            }
            i++
        }
        return bytes
    }

    /** The bytes of [text] in UTF-8 as Java writes it, counted without making them: a surrogate outside a pair as one `?`. */
    fun utf8Bytes(text: CharSequence): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                bytes += 4
                i += 2
                continue
            }
            bytes += utf8Bytes(c)
            i++
        }
        return bytes
    }

    /** The bytes of a character outside a pair of surrogates in UTF-8 as Java writes it; a surrogate on its own becomes one `?`. */
    private fun utf8Bytes(c: Char): Int = when {
        c.code < 0x80 || c.isSurrogate() -> 1
        c.code < 0x800 -> 2
        else -> 3
    }

    private fun streetManeuvers(nodes: List<Node>): Map<String, Int> {
        var total = 0
        var withAccess = 0
        for (m in 1 until nodes.size) {
            val node = nodes[m]
            val maneuver = node.maneuver ?: continue
            if (!CardWindows.framesStreetManeuver(maneuver, nodes[m - 1].road)) continue
            total++
            if (node.inIndex?.let { node.access?.getOrNull(it) } != null) withAccess++
        }
        return linkedMapOf("total" to total, "withAccess" to withAccess)
    }

    private fun streetOwnRoad(nodes: List<Node>): Map<String, Int> {
        val counts = linkedMapOf("oneWay" to 0, "twoWay" to 0, "none" to 0, OTHER to 0, MISSING to 0)
        for (node in nodes) {
            if (node.road != RoadKind.STREET) continue
            val out = node.outIndex ?: continue
            val name = when (node.access?.getOrNull(out)) {
                null -> MISSING
                -1, 1 -> "oneWay"
                2 -> "twoWay"
                0 -> "none"
                else -> OTHER
            }
            counts[name] = counts.getValue(name) + 1
        }
        return counts
    }

    private fun MutableMap<String, Int>.add(name: String) {
        this[name] = getValue(name) + 1
    }

    private const val ACCESS_KEY = "access"
    private const val FORM_OF_WAY_KEY = "form_of_way"
    private const val NULL_BYTES = 4
    private const val UNICODE_ESCAPE_BYTES = 6
    private const val PERCENT = 100.0
    private val HTML_ESCAPED = setOf('<', '>', '&', '=', '\'')
}
