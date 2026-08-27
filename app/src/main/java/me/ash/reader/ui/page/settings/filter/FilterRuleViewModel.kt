package me.ash.reader.ui.page.settings.filter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.FilterCondition
import me.ash.reader.domain.model.filter.FilterExpression
import me.ash.reader.domain.model.filter.FilterField
import me.ash.reader.domain.model.filter.FilterMatchType
import me.ash.reader.domain.model.filter.FilterRule
import me.ash.reader.domain.model.filter.toJson
import me.ash.reader.domain.model.filter.toFilterExpressionOrNull
import me.ash.reader.domain.repository.FilterRuleDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.ui.ext.spacerDollar

@HiltViewModel
class FilterRuleViewModel
@Inject
constructor(
    private val filterRuleDao: FilterRuleDao,
    private val accountService: AccountService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilterRuleUiState())
    val uiState: StateFlow<FilterRuleUiState> = _uiState.asStateFlow()

    /** All rules for the current account, ordered by creation time. */
    val rules: StateFlow<List<FilterRule>> =
        filterRuleDao.observeByAccount(accountService.getCurrentAccountId())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startEditing(ruleId: String?, feedId: String? = null) {
        viewModelScope.launch(ioDispatcher) {
            if (ruleId == null) {
                _uiState.update {
                    FilterRuleUiState(
                        editingRuleId = null,
                        feedId = feedId,
                        name = "",
                        action = FilterAction.BLOCK,
                        conditions =
                            listOf(
                                EditableCondition(
                                    field = FilterField.TITLE,
                                    matchType = FilterMatchType.CONTAINS,
                                    pattern = "",
                                )
                            ),
                    )
                }
            } else {
                val rule = filterRuleDao.findById(ruleId) ?: return@launch
                val expression = rule.expressionJson.toFilterExpressionOrNull()
                _uiState.update {
                    FilterRuleUiState(
                        editingRuleId = rule.id,
                        name = rule.name,
                        action = rule.action,
                        conditions = expression?.flattenToConditions().orEmpty(),
                    )
                }
            }
        }
    }

    fun updateName(name: String) = _uiState.update { it.copy(name = name) }

    fun updateAction(action: FilterAction) = _uiState.update { it.copy(action = action) }

    fun updateCondition(index: Int, condition: EditableCondition) =
        _uiState.update { state ->
            state.copy(
                conditions =
                    state.conditions.mapIndexed { i, c -> if (i == index) condition else c }
            )
        }

    fun addCondition() =
        _uiState.update { state ->
            state.copy(
                conditions =
                    state.conditions +
                        EditableCondition(
                            field = FilterField.TITLE,
                            matchType = FilterMatchType.CONTAINS,
                            pattern = "",
                        )
            )
        }

    fun removeCondition(index: Int) =
        _uiState.update { state ->
            if (state.conditions.size <= 1) {
                state
            } else {
                state.copy(conditions = state.conditions.filterIndexed { i, _ -> i != index })
            }
        }

    /**
     * Persists the rule currently being edited. Returns false when the input
     * is incomplete (no name or no non-blank pattern).
     */
    fun save(): Boolean {
        val state = _uiState.value
        val name = state.name.trim()
        val patterns = state.conditions.map { it.pattern.trim() }.filter { it.isNotEmpty() }
        if (name.isEmpty() || patterns.isEmpty()) return false

        val accountId = accountService.getCurrentAccountId()
        val conditions =
            state.conditions
                .filter { it.pattern.isNotBlank() }
                .map {
                    FilterCondition(
                        field = it.field,
                        matchType = it.matchType,
                        pattern = it.pattern.trim(),
                    )
                }
        val expression = FilterExpression.simple(conditions, state.action) ?: return false
        val ruleId = state.editingRuleId ?: accountId.spacerDollar(UUID.randomUUID().toString())

        viewModelScope.launch(ioDispatcher) {
            filterRuleDao.upsert(
                FilterRule(
                    id = ruleId,
                    accountId = accountId,
                    feedId = state.feedId,
                    name = name,
                    isEnabled = true,
                    action = state.action,
                    expressionJson = expression.toJson(),
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
        return true
    }

    fun delete(rule: FilterRule) {
        viewModelScope.launch(ioDispatcher) { filterRuleDao.delete(rule) }
    }

    fun setEnabled(rule: FilterRule, enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            filterRuleDao.update(rule.copy(isEnabled = enabled))
        }
    }
}

/** One condition row in the editor, kept editable until save. */
data class EditableCondition(
    val field: FilterField = FilterField.TITLE,
    val matchType: FilterMatchType = FilterMatchType.CONTAINS,
    val pattern: String = "",
)

data class FilterRuleUiState(
    val editingRuleId: String? = null,
    /** null ⇒ global (account-level) rule; per-feed rules arrive in step 7. */
    val feedId: String? = null,
    val name: String = "",
    val action: FilterAction = FilterAction.BLOCK,
    val conditions: List<EditableCondition> = listOf(EditableCondition()),
)

/**
 * Flattens an arbitrary expression tree back into the flat condition list used
 * by the simple-mode editor. Group operators are lost — acceptable because the
 * editor rebuilds them from the rule's action on save.
 */
private fun FilterExpression.flattenToConditions(): List<EditableCondition> =
    when (this) {
        is FilterExpression.Condition ->
            listOf(
                EditableCondition(condition.field, condition.matchType, condition.pattern)
            )
        is FilterExpression.AllOf -> children.flatMap { it.flattenToConditions() }
        is FilterExpression.AnyOf -> children.flatMap { it.flattenToConditions() }
        is FilterExpression.NoneOf -> children.flatMap { it.flattenToConditions() }
    }
