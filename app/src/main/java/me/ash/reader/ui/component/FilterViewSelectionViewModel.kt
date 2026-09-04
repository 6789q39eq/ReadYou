package me.ash.reader.ui.component

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    init {
        // Prune any rule ids in [FilterState.appliedRuleIds] that no longer
        // resolve to a real rule (rule deleted via the settings page, by
        // account deletion, or by feed deletion). Without this, the banner
        // title and middle tab stay stuck on "Filtered" after a delete.
        viewModelScope.launch {
            rulesFlow
                .map { rules -> rules.map { it.id }.toHashSet() }
                .distinctUntilChanged()
                .collect { existingIds ->
                    val applied =
                        filterStateUseCase.filterStateFlow.value.appliedRuleIds
                    if (applied.isEmpty()) return@collect
                    val pruned = applied.intersect(existingIds)
                    if (pruned.size != applied.size) {
                        filterStateUseCase.updateFilterState(appliedRuleIds = pruned)
                    }
                }
        }
    }

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
