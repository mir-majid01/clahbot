package com.clahbot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clahbot.data.BotState
import com.clahbot.data.MacroRepository
import com.clahbot.data.models.Macro
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(private val repository: MacroRepository) : ViewModel() {

    val macros: StateFlow<List<Macro>> = repository.macros

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh() }
    }

    fun select(id: Long) {
        BotState.setSelectedMacroId(id)
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun update(macro: Macro) {
        viewModelScope.launch { repository.save(macro) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }
}
