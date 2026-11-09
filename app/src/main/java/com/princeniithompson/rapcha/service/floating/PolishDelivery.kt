package com.princeniithompson.rapcha.service.floating

/**
 * Decides whether a polish result may still be written into the field that
 * started the section. A stop is permanent for that session.
 */
enum class DeliveryHalt {
    NONE,
    TARGET_LEFT,
    HOME,
    SENSITIVE,
    CANCELLED
}

object PolishDelivery {
    fun halt(
        originPackage: String?,
        currentPackage: String?,
        sensitive: Boolean,
        cancelled: Boolean,
        launcher: Boolean
    ): DeliveryHalt {
        if (cancelled) return DeliveryHalt.CANCELLED
        if (sensitive) return DeliveryHalt.SENSITIVE
        if (launcher) return DeliveryHalt.HOME
        if (!originPackage.isNullOrBlank() &&
            !currentPackage.isNullOrBlank() &&
            currentPackage != originPackage
        ) {
            return DeliveryHalt.TARGET_LEFT
        }
        return DeliveryHalt.NONE
    }

    /**
     * A large Stage-1 piece that comes back as a few characters is suspicious.
     * A genuinely short input may still have a short polish.
     */
    fun suspiciousPiece(sourceChars: Int, outputChars: Int): Boolean {
        if (sourceChars < 80) return false
        if (outputChars <= 0) return true
        return outputChars < 40 && outputChars * 20 < sourceChars
    }

    fun readableField(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        val name = className.lowercase()
        return !name.contains("webview") && !name.contains("flutter")
    }

    /** One clipboard recovery. Cancel and sensitive sessions never copy. */
    fun mayCopyFinal(halt: DeliveryHalt, alreadyCopied: Boolean): Boolean {
        if (alreadyCopied) return false
        return halt != DeliveryHalt.CANCELLED && halt != DeliveryHalt.SENSITIVE
    }
}

class DeliverySession(
    val id: Long,
    val originPackage: String?
) {
    var halt: DeliveryHalt = DeliveryHalt.NONE
        private set
    var copied: Boolean = false
        private set

    fun note(next: DeliveryHalt) {
        if (halt == DeliveryHalt.NONE && next != DeliveryHalt.NONE) halt = next
    }

    fun acceptsInsert(): Boolean = halt == DeliveryHalt.NONE

    fun markCopied() {
        copied = true
    }
}
