package io.github.purplepalm.custommaps.nav.model

/** How close the guidance camera goes before turns and lane choices; the driver chooses one in Settings. */
enum class LaneZoom {
    /** The zoom guidance has had so far; in use until the driver chooses the other. */
    DEFAULT,

    /** Close in before every turn and before lane choices on freeways, and back out after. */
    LARGE,
}
