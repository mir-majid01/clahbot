package com.clahbot.ui

/** Connects the overlay settings panel to its normal-window text input activity. */
object MacroInputRelay {
    var pending: ((String?) -> Unit)? = null

    fun complete(value: String?) {
        val callback = pending
        pending = null
        callback?.invoke(value)
    }
}
