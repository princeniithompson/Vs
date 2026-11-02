package com.princeniithompson.rapcha.service.floating

/**
 * Stage-2 Live sends the polished text so far on every transcription chunk.
 * [android.view.inputmethod.InputConnection.commitText] appends, so committing
 * that whole string again stacks another copy. This plans the field edit.
 */
object LiveStreamPaste {
    sealed class Edit {
        data class Append(val suffix: String) : Edit()
        data class Replace(val previousLength: Int, val full: String) : Edit()
        data object Skip : Edit()
    }

    fun plan(already: String, next: String): Edit {
        if (next.isEmpty() || next == already) return Edit.Skip
        if (already.isEmpty()) return Edit.Append(next)
        if (next.startsWith(already)) {
            val suffix = next.substring(already.length)
            return if (suffix.isEmpty()) Edit.Skip else Edit.Append(suffix)
        }
        return Edit.Replace(already.length, next)
    }

    /**
     * Field contents after applying [updates], which are cumulative snapshots.
     * Text that was already in the field before the first update is [prefix].
     */
    fun visibleText(updates: List<String>, prefix: String = ""): String {
        var committed = ""
        var field = prefix
        for (update in updates) {
            when (val edit = plan(committed, update)) {
                is Edit.Skip -> Unit
                is Edit.Append -> {
                    field += edit.suffix
                    committed = update
                }
                is Edit.Replace -> {
                    field = if (field.length >= edit.previousLength) {
                        field.dropLast(edit.previousLength) + edit.full
                    } else {
                        edit.full
                    }
                    committed = update
                }
            }
        }
        return field
    }
}
