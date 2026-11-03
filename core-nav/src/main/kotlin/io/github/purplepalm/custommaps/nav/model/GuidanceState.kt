package io.github.purplepalm.custommaps.nav.model

enum class GuidanceState {
    /** No route. Nothing is being billed. */
    IDLE,

    /** Routes are shown for the driver to pick one; no trip session is running. */
    PREVIEW,

    /** Turn-by-turn guidance with a running trip session. */
    ACTIVE,
}
