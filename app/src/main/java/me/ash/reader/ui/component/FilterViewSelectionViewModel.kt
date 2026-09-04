package me.ash.reader.ui.component

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.repository.FilterRuleDao
import me.ash.reader.domain.service.AccountService

/**
 * Backs [FilterRuleSelectionDialog]: exposes the current account's rules
 * plus the middle-tab view selection (selected rule ids + unread
 * pseudo-rule) and persists selection into [FilterStateUseCase].
 */
@HiltViewModel
class FilterViewSelectionViewModel @Inject constructor(
    private val filterStateUseCase: FilterStateUseCase,
    filterRuleDao: FilterRuleDao,
    accountService: AccountService,
) : ViewModel() {

    val filterStateFlow = filterStateUseCase.filterStateFlow

    val rulesFlow = filterRuleDao
        .observeByAccount(accountService.getCurrentAccountId())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setUnreadOnly(unreadOnly: Boolean) {
        filterStateUseCase.updateFilterState(unreadOnlyInFiltered = unreadOnly)
    }

    fun toggleRule(ruleId: String, selected: Boolean) {
        val current = filterStateUseCase.filterStateFlow.value.appliedRuleIds.toMutableSet()
        if (selected) current.add(ruleId) else current.remove(ruleId)
        filterStateUseCase.updateFilterState(appliedRuleIds = current)
    }

    fun clearSelection() {
        filterStateUseCase.updateFilterState(
            appliedRuleIds = emptySet(),
            unreadOnlyInFiltered = true,
        )
    }
}
