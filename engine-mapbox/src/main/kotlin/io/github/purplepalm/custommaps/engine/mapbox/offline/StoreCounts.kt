package io.github.purplepalm.custommaps.engine.mapbox.offline

import com.mapbox.common.TileRegion
import com.mapbox.common.TileRegionLoadProgress
import com.mapbox.maps.StylePack
import com.mapbox.maps.StylePackLoadProgress

/**
 * The numbers of a finished load of the tile region: how many resources the region needs and has,
 * and their size, from [region], the store's final answer; what came over the network and what
 * failed only from [progress], the last progress report, since the region has no such numbers.
 * Without the region, all of it from the progress; null when the store gave neither.
 */
internal fun downloadCounts(region: TileRegion?, progress: TileRegionLoadProgress?): DownloadCounts? = when {
    region != null -> DownloadCounts(
        required = region.requiredResourceCount,
        completed = region.completedResourceCount,
        completedBytes = region.completedResourceSize,
        loadedBytes = progress?.loadedResourceSize,
        errored = progress?.erroredResourceCount ?: 0,
    )
    progress != null -> DownloadCounts(
        required = progress.requiredResourceCount,
        completed = progress.completedResourceCount,
        completedBytes = progress.completedResourceSize,
        loadedBytes = progress.loadedResourceSize,
        errored = progress.erroredResourceCount,
    )
    else -> null
}

/** The numbers of a finished load of the style pack, read as for the tile region. */
internal fun downloadCounts(pack: StylePack?, progress: StylePackLoadProgress?): DownloadCounts? = when {
    pack != null -> DownloadCounts(
        required = pack.requiredResourceCount,
        completed = pack.completedResourceCount,
        completedBytes = pack.completedResourceSize,
        loadedBytes = progress?.loadedResourceSize,
        errored = progress?.erroredResourceCount ?: 0,
    )
    progress != null -> DownloadCounts(
        required = progress.requiredResourceCount,
        completed = progress.completedResourceCount,
        completedBytes = progress.completedResourceSize,
        loadedBytes = progress.loadedResourceSize,
        errored = progress.erroredResourceCount,
    )
    else -> null
}
