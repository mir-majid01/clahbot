package com.clahbot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clahbot.data.ConfigRepository
import com.clahbot.data.models.Macro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(private val configRepo: ConfigRepository) : ViewModel() {

    private val _macros = MutableStateFlow<List<Macro>>(emptyList())
    val macros: StateFlow<List<Macro>> = _macros.asStateFlow()

    private val _selectedId = MutableStateFlow(-1L)
    val selectedId: StateFlow<Long> = _selectedId.asStateFlow()

    init {
        loadMacros()
    }

    fun loadMacros() {
        viewModelScope.launch {
            _macros.value = configRepo.loadMacros().sortedByDescending { it.createdAt }
        }
    }

    fun selectMacro(id: Long) {
        _selectedId.value = id
    }

    fun deleteMacro(id: Long) {
        viewModelScope.launch {
            configRepo.deleteMacro(id)
            _macros.value = configRepo.loadMacros()
            if (_selectedId.value == id) _selectedId.value = -1
        }
    }

    fun getSelectedMacro(): Macro? {
        val id = _selectedId.value
        return _macros.value.find { it.id == id }
    }
}
