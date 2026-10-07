package __APP_ID__.core.extraction

/**
 * The single error type that leaves the extraction layer. UI shows [userMessage];
 * [technical] is available behind a "Details" action and in the debug log.
 */
class ExtractionException(
    val kind: Kind,
    val technical: String,
    cause: Throwable? = null,
) : Exception(technical, cause) {

    enum class Kind {
        NETWORK,
        UNAVAILABLE,
        PRIVATE,
        AGE_RESTRICTED,
        GEO_BLOCKED,
        PAID,
        RATE_LIMITED,
        BOT_CHECK,
        NEEDS_UPDATE,
        NO_STREAMS,
        UNKNOWN,
    }

    val userMessage: String
        get() = when (kind) {
            Kind.NETWORK -> "Couldn't reach YouTube. Check your connection and try again."
            Kind.UNAVAILABLE -> "This video is unavailable."
            Kind.PRIVATE -> "This video is private."
            Kind.AGE_RESTRICTED -> "This video is age-restricted and can't be loaded without signing in."
            Kind.GEO_BLOCKED -> "This video isn't available in your country."
            Kind.PAID -> "This video requires payment or a membership."
            Kind.RATE_LIMITED -> "YouTube is limiting requests right now. Wait a bit, then retry."
            Kind.BOT_CHECK -> "YouTube asked to confirm you're not a bot. This usually passes after a while or on a different network."
            Kind.NEEDS_UPDATE -> "This video could not be loaded. The extraction source may need an update."
            Kind.NO_STREAMS -> "No playable streams were found for this video."
            Kind.UNKNOWN -> "Something went wrong while loading this video."
        }

    /** Whether pressing Retry has a realistic chance of helping. */
    val retryable: Boolean
        get() = when (kind) {
            Kind.NETWORK, Kind.RATE_LIMITED, Kind.BOT_CHECK, Kind.NEEDS_UPDATE, Kind.UNKNOWN -> true
            else -> false
        }
}
