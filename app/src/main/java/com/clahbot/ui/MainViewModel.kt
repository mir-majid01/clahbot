package com.clahbot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clahbot.data.MacroRepository
import com.clahbot.data.models.Macro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(private val repository: MacroRepository) : ViewModel() {

    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()

    init {
        loadMacros()
    }

    fun loadMacros() {
        viewModelScope.launch {
            _macros.value = repository.load()
        }
    }

    fun deleteMacro(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            _macros.value = repository.load()
        }
    }

    fun updateMacro(macro: Macro) {
        viewModelScope.launch {
            repository.save(macro)
            _macros.value = repository.load()
        }
    }
}
