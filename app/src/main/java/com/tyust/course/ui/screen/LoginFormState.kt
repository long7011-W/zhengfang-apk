package com.tyust.course.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Sensitive form fields survive rotation in memory, never in a saved-state Bundle. */
class LoginFormState : ViewModel() {
    var cookie by mutableStateOf("")
    var password by mutableStateOf("")
    private var contextKey: String? = null
    fun selectContext(key: String, initialCookie: String) {
        if (contextKey == key) return
        contextKey = key; cookie = initialCookie; password = ""
    }
    override fun onCleared() { cookie = ""; password = ""; contextKey = null }
}
