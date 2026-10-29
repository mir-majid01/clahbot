package com.clahbot.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.clahbot.R

/**
 * Normal-window editor for overlay settings. Android refuses to show the IME
 * for a focusable TYPE_APPLICATION_OVERLAY on this ROM, so the overlay hides
 * and this small activity takes the text.
 */
class MacroInputActivity : Activity() {

    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_macro_input)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Edit value" }
        val value = intent.getStringExtra(EXTRA_VALUE).orEmpty()
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val input = findViewById<EditText>(R.id.etInput)
        findViewById<TextView>(R.id.tvInputTitle).text = title
        input.setText(value)
        input.setSelection(input.text.length)
        input.inputType = when (kind) {
            KIND_NAME -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            KIND_DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else -> InputType.TYPE_CLASS_NUMBER
        }
        input.hint = hintFor(kind)

        fun finishWith(text: String?) {
            if (completed) return
            completed = true
            MacroInputRelay.complete(text)
            finish()
        }

        findViewById<Button>(R.id.btnInputSave).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Enter a value", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            finishWith(text)
        }
        findViewById<Button>(R.id.btnInputCancel).setOnClickListener {
            finishWith(null)
        }
        input.requestFocus()
    }

    override fun onDestroy() {
        if (!completed) {
            completed = true
            MacroInputRelay.complete(null)
        }
        super.onDestroy()
    }

    private fun hintFor(kind: String): String = when (kind) {
        KIND_NAME -> "Macro name"
        KIND_DECIMAL -> "0-600"
        else -> "Value"
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_VALUE = "value"
        const val EXTRA_KIND = "kind"
        const val KIND_NAME = "name"
        const val KIND_NUMBER = "number"
        const val KIND_DECIMAL = "decimal"
    }
}
