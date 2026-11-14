package io.github.purplepalm.custommaps.nav.camera

/**
 * Whether a move of the navigation SDK's own camera (the two Mapbox camera styles) that ended cancelled is asked for once
 * more. Every request of the camera, and every time the driver's gesture or the compass takes the map from it, raises the
 * camera's request serial; a move that ends cancelled posts one check to the main thread, which comes here.
 *
 * The SDK tells a replaced request that it ended cancelled from inside the request that replaces it, and from a request to
 * go idle, while its state still names the cancelled move; a request made from there is a move the SDK drops at once and
 * that nothing can stop. Posted, the check finds the newer request's serial, and asks nothing. What is left is a move that
 * ended cancelled with nothing newer and its camera still on its way there, which computes no frames until asked again:
 * that is asked once more, and only once.
 */
object RecoverRule {

    /**
     * @param serialAtRequest the camera's request serial when the move was asked for
     * @param serialNow the serial now, at the posted check
     * @param stillInThatTransition the SDK camera still names the move asked for as on its way (its `TRANSITION_TO_` state)
     * @param alreadyRecovered the move was itself the one asked for once more
     */
    fun shouldAsk(serialAtRequest: Long, serialNow: Long, stillInThatTransition: Boolean, alreadyRecovered: Boolean): Boolean =
        serialAtRequest == serialNow && stillInThatTransition && !alreadyRecovered
}
