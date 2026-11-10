package com.princeniithompson.rapcha.websocket

/**
 * Independent copy of the text the voice container is showing before Smart
 * finalization. Later finals replace the container; they do not rewrite this.
 */
class PrefinalBackup {
    private val pieces = ArrayList<String>()
    private var openPreview = ""
    private var sealed = false

    fun onPreview(text: String) {
        if (sealed) return
        openPreview = text.trim()
    }

    fun displayed(): String = assemble(pieces, openPreview)

    /**
     * Copies the open preview into the frozen pieces. Used at a piece boundary
     * before activityEnd. Later previews start a new open piece.
     */
    fun freezeOpenPiece(): String {
        if (sealed) return displayed()
        val piece = openPreview.trim()
        if (piece.isNotEmpty()) {
            pieces += piece
            openPreview = ""
        }
        return displayed()
    }

    /** Complete boundary. No later preview or final can change the backup. */
    fun seal(): String {
        if (!sealed) {
            freezeOpenPiece()
            sealed = true
        }
        return displayed()
    }

    fun reset() {
        pieces.clear()
        openPreview = ""
        sealed = false
    }

    val isSealed: Boolean get() = sealed

    companion object {
        fun assemble(pieces: List<String>, openPreview: String): String {
            var committed = ""
            for (piece in pieces) {
                committed = TranscriptSeam.append(committed, piece)
            }
            val live = openPreview.trim()
            if (live.isEmpty()) return committed
            if (committed.isEmpty() || committed == live || live.startsWith(committed)) return live
            if (committed.endsWith(" $live") || committed.endsWith(live)) return committed
            return TranscriptSeam.append(committed, live)
        }
    }
}
