package se.lublin.humla.util

/**
 * Called when a
 * Created by andrew on 01/03/17.
 */
class HumlaDisconnectedException : RuntimeException {
    constructor() : super("Caller attempted to use the protocol while disconnected.")

    constructor(reason: String?) : super(reason)
}
