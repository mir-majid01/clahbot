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

    suspend fun duplicate(id: Long): Macro? = repository.duplicate(id)

    suspend fun backup(prefix: String = "before-delete"): java.io.File? =
        repository.backup(prefix)

    suspend fun deleteMany(ids: Set<Long>): List<Macro> = repository.deleteMany(ids)

    suspend fun restoreMany(items: List<Macro>): Int = repository.restoreMany(items)

    fun rename(id: Long, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun update(macro: Macro) {
        viewModelScope.launch { repository.save(macro) }
    }

    suspend fun importShared(macro: Macro): Macro? = repository.importShared(macro)

    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }
}
