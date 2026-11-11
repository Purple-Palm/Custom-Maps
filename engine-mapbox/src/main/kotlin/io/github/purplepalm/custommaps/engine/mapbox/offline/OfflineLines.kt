package io.github.purplepalm.custommaps.engine.mapbox.offline

import io.github.purplepalm.custommaps.nav.debug.Motion

/** Why the app itself ended a download before the store had finished it. */
internal enum class EndedBy {

    /** A newer preview loads the region again, with its own routes. */
    NEWER_PREVIEW,

    /** Guidance started. From then on only predictive caching loads, within its own areas. */
    GUIDANCE,
}

/**
 * How far a download came, as the store counted it. [loadedBytes] is what came over the network
 * for it, which is what it cost in data; [completedBytes] also counts what the phone had already.
 * [loadedBytes] is null when the store never reported it.
 */
internal data class DownloadCounts(
    val required: Long,
    val completed: Long,
    val completedBytes: Long,
    val loadedBytes: Long?,
    val errored: Long = 0,
)

/**
 * The fields of the debug lines about data kept on the phone, put together from plain values, so
 * what a line says for each case can be tested without the SDK.
 */
internal object OfflineLines {

    /** How a download ended, in a word, and for some endings the reason. */
    data class Outcome(val result: String, val why: String? = null)

    /**
     * The "tile_store" line, written each time a navigator is made. [mapHasStore]: the Maps SDK
     * has a tile store that was set for it, and only this app sets one. [navigatorHasStore]: the
     * navigator's options hold that same store. "shared" is true only with both: the map and the
     * navigator then read and fill one store, which predictive caching needs. [usageMode] is how
     * the map uses the store; READ_ONLY reads tile packs from it and still loads any other tile by
     * itself.
     */
    fun tileStore(mapHasStore: Boolean, navigatorHasStore: Boolean, usageMode: String): Map<String, Any?> = mapOf(
        "shared" to (mapHasStore && navigatorHasStore),
        "map" to mapHasStore,
        "navigator" to navigatorHasStore,
        "usage" to usageMode,
    )

    /**
     * The "offline_cache" line, written when predictive caching is set up for a navigator: how far
     * road data and the two bands of the map are kept ([OfflineAreas]). Each "meters" list is
     * around the car, along the route and around the destination.
     */
    fun predictiveCache(): Map<String, Any?> = mapOf(
        "roadData" to mapOf("meters" to OfflineAreas.ROAD_DATA.meters()),
        "streetMap" to mapOf("zooms" to OfflineAreas.STREET_ZOOMS.ends(), "meters" to OfflineAreas.STREET_MAP.meters()),
        "townMap" to mapOf("zooms" to OfflineAreas.TOWN_ZOOMS.ends(), "meters" to OfflineAreas.TOWN_MAP.meters()),
    )

    private fun KeptAround.meters(): List<Int> = listOf(carMeters, routeMeters, destinationMeters)

    private fun ZoomBand.ends(): List<Int> = listOf(min, max)

    /**
     * What the end of a download is called. [errorType] is the name of the store's error type, null
     * when it reported none; [endedBy] says whether the app had ended the download itself.
     *
     * - No error: "done".
     * - PARTIAL_LOAD: "partial". Some resources failed; what did load can be used.
     * - CANCELED after the app ended it: "replaced" for a newer preview, which is how the one
     *   region is meant to change hands, or "stopped" with the reason "guidance". Neither is a
     *   fault.
     * - Anything else is "failed", with the error type in lower case as the reason: a full disk,
     *   a style that could not be resolved, too many tiles, or a cancel nobody here asked for.
     *
     * A download that finished before the app's cancel reached it is reported as it finished.
     */
    fun outcome(errorType: String?, endedBy: EndedBy?): Outcome = when {
        errorType == null -> Outcome(DONE)
        errorType == PARTIAL_LOAD -> Outcome(PARTIAL)
        errorType == CANCELED && endedBy == EndedBy.NEWER_PREVIEW -> Outcome(REPLACED)
        errorType == CANCELED && endedBy == EndedBy.GUIDANCE -> Outcome(STOPPED, "guidance")
        else -> Outcome(FAILED, errorType.lowercase())
    }

    /**
     * The "offline_region_start" line: the tile region is asked for again, for the [routes] of a
     * new preview. [load] counts the loads of this run of the app and is on the "offline_region"
     * line that answers this one. [points] is how many points the routes' lines have after
     * thinning, [network] which networks the download may use. [routeMeters] is the length of each
     * route that has a line, [keptMeters] how far along it the region reaches, and [maxMeters] how
     * far it may reach at most; all three are written in km.
     */
    fun regionStart(
        load: Int,
        routes: Int,
        points: Int,
        zooms: ZoomBand,
        pixelRatio: Float,
        network: String,
        routeMeters: List<Double>,
        keptMeters: List<Double>,
        maxMeters: Double,
    ): Map<String, Any?> = mapOf(
        "load" to load,
        "routes" to routes,
        "points" to points,
        "zooms" to zooms.ends(),
        "pixelRatio" to pixelRatio,
        "network" to network,
        "routeKm" to routeMeters.map(::km),
        "keptKm" to keptMeters.map(::km),
        "maxKm" to km(maxMeters),
    )

    private fun km(meters: Double): Double = Motion.round(meters / METERS_PER_KM, 1)

    /**
     * The "offline_region" line: how load number [load] of the tile region ended. "result" and
     * "why" are those of [outcome]; "message" is the store's own text for a load that failed or is
     * partial. "required" and "completed" count resources, "completedBytes" is the size of what is
     * on the phone for the region, and "loadedBytes" what this load brought over the network.
     * "errored" appears when resources failed. A number the store never gave is left out.
     */
    fun region(
        load: Int,
        routes: Int,
        errorType: String?,
        message: String?,
        endedBy: EndedBy?,
        counts: DownloadCounts?,
        seconds: Double,
    ): Map<String, Any?> = mapOf("load" to load, "routes" to routes) + download(errorType, message, endedBy, counts, seconds)

    /** The "offline_style_pack" line: how a load of the style's own files ended; fields as in [region]. */
    fun stylePack(errorType: String?, message: String?, endedBy: EndedBy?, counts: DownloadCounts?, seconds: Double): Map<String, Any?> =
        download(errorType, message, endedBy, counts, seconds)

    private fun download(errorType: String?, message: String?, endedBy: EndedBy?, counts: DownloadCounts?, seconds: Double): Map<String, Any?> {
        val outcome = outcome(errorType, endedBy)
        return mapOf(
            "result" to outcome.result,
            "why" to outcome.why,
            // A download the app ended itself has nothing wrong to tell of.
            "message" to message.takeIf { outcome.result == FAILED || outcome.result == PARTIAL },
            "required" to counts?.required,
            "completed" to counts?.completed,
            "completedBytes" to counts?.completedBytes,
            "loadedBytes" to counts?.loadedBytes,
            "errored" to counts?.errored?.takeIf { it > 0 },
            "seconds" to Motion.round(seconds, 1),
        ).filterValues { it != null }
    }

    const val DONE = "done"
    const val PARTIAL = "partial"
    const val REPLACED = "replaced"
    const val STOPPED = "stopped"
    const val FAILED = "failed"

    /** Names of the store's error types that have an outcome of their own. */
    private const val CANCELED = "CANCELED"
    private const val PARTIAL_LOAD = "PARTIAL_LOAD"

    /** The reason of a region that was never asked for: no previewed route had a line. */
    const val NO_GEOMETRY = "NO_GEOMETRY"

    private const val METERS_PER_KM = 1_000.0
}
