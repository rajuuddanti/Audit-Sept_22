package com.mahamart.mahamartaudit

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatAutoCompleteTextView

class PersistentAutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.autoCompleteTextViewStyle
) : AppCompatAutoCompleteTextView(context, attrs, defStyleAttr) {

    // Catch hardware/soft keyboard Back press before IME dismisses the popup
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.action == KeyEvent.ACTION_UP) {
            if (isPopupShowing) {
                // Hide soft keyboard manually
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(windowToken, 0)

                // Re-open/keep dropdown visible
                postDelayed({
                    if (text.trim().length >= 2) {
                        showDropDown()
                    }
                }, 100)
                return true // Consume back press event
            }
        }
        return super.onKeyPreIme(keyCode, event)
    }

    // Prevent auto-dismissal when focus changes or blank area is tapped
    override fun dismissDropDown() {
        // Only allow explicit dismissal if field is cleared or item is clicked
        if (text.trim().length < 2) {
            super.dismissDropDown()
        }
    }

    fun forceDismissDropDown() {
        super.dismissDropDown()
    }
}