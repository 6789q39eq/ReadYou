package me.ash.reader.ui.page.settings.filter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.FilterCondition
import me.ash.reader.domain.model.filter.FilterExpression
import me.ash.reader.domain.model.filter.FilterField
import me.ash.reader.domain.model.filter.FilterMatchType
import me.ash.reader.domain.model.filter.FilterRule
import me.ash.reader.domain.model.filter.toJson
import me.ash.reader.domain.model.filter.toFilterExpressionOrNull
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.FilterRuleDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.ui.ext.spacerDollar

@HiltViewModel
class FilterRuleViewModel
@Inject
constructor(
    private val filterRuleDao: FilterRuleDao,
    private val feedDao: FeedDao,
    private val accountService: AccountService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilterRuleUiState())
    val uiState: StateFlow<FilterRuleUiState> = _uiState.asStateFlow()

    /** Feeds of the current account, for the "Applies to" scope selector. */
    private val _availableFeeds = MutableStateFlow<List<Feed>>(emptyList())
    val availableFeeds: StateFlow<List<Feed>> = _availableFeeds.asStateFlow()

    /** All rules for the current account, ordered by creation time. */
    val rules: StateFlow<List<FilterRule>> =
        filterRuleDao.observeByAccount(accountService.getCurrentAccountId())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * In-flight load job for an existing rule. Exposed so [save] can suspend
     * on it instead of racing the IO read.
     */
    private var loadJob: Job? = null

    fun startEditing(ruleId: String?, feedId: String? = null) {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch(ioDispatcher) {
                _availableFeeds.value =
                    runCatching { feedDao.queryAll(accountService.getCurrentAccountId()) }
                        .getOrNull()
                        .orEmpty()
                        .sortedBy { it.name.lowercase() }
                if (ruleId == null) {
                    _uiState.update {
                        FilterRuleUiState(
                            editingRuleId = null,
                            feedId = feedId,
                            action = FilterAction.BLOCK,
                            conditions =
                                listOf(
                                    EditableCondition(
                                        field = FilterField.ALL,
                                        matchType = FilterMatchType.GLOB,
                                        pattern = "",
                                    )
                                ),
                        )
                    }
                } else {
                    val rule = filterRuleDao.findById(ruleId)
                    if (rule == null) {
                        _uiState.update { FilterRuleUiState() }
                        return@launch
                    }
                    val expression = rule.expressionJson.toFilterExpressionOrNull()
                    val wasAdvanced = expression != null && !expression.isFlatConditionList()
                    val conditions = expression?.flattenToConditions().orEmpty()
                    _uiState.update {
                        FilterRuleUiState(
                            editingRuleId = rule.id,
                            feedId = rule.feedId,
                            action = rule.action,
                            conditions = conditions,
                            originalExpressionJson = rule.expressionJson,
                            isAdvancedRule = wasAdvanced,
                            editingIsEnabled = rule.isEnabled,
                            editingCreatedAt = rule.createdAt,
                        )
                    }
                }
            }
    }

    /** Suspends until the in-flight load (if any) completes. */
    suspend fun awaitLoaded() {
        loadJob?.join()
    }

    fun acknowledgeAdvancedRuleWarning() =
        _uiState.update { it.copy(isAdvancedRule = false) }

    fun updateAction(action: FilterAction) = _uiState.update { it.copy(action = action) }

    fun updateFeedId(feedId: String?) = _uiState.update { it.copy(feedId = feedId) }

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
                            field = FilterField.ALL,
                            matchType = FilterMatchType.GLOB,
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
     * Persists the rule currently being edited. Suspends until the IO load
     * for an existing rule has completed, so the user never sees the default
     * empty state right after opening an existing rule. Returns false when
     * the input is incomplete (no non-blank pattern).
     */
    suspend fun save(): Boolean {
        awaitLoaded()
        val state = _uiState.value
        val patterns = state.conditions.map { it.pattern.trim() }.filter { it.isNotEmpty() }
        if (patterns.isEmpty()) return false

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
        // Preserve enablement and creation order when editing; new rules start
        // enabled with the current timestamp.
        val isEnabled = if (state.editingRuleId == null) true else state.editingIsEnabled
        val createdAt = state.editingCreatedAt ?: System.currentTimeMillis()
        // The pattern string identifies the rule in the settings list.
        val name = patterns.joinToString(", ")

        return withContext(ioDispatcher) {
            filterRuleDao.upsert(
                FilterRule(
                    id = ruleId,
                    accountId = accountId,
                    feedId = state.feedId,
                    name = name,
                    isEnabled = isEnabled,
                    action = state.action,
                    expressionJson = expression.toJson(),
                    createdAt = createdAt,
                )
            )
            true
        }
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
    val field: FilterField = FilterField.ALL,
    val matchType: FilterMatchType = FilterMatchType.GLOB,
    val pattern: String = "",
)

data class FilterRuleUiState(
    val editingRuleId: String? = null,
    /** null ⇒ global (account-level) rule. */
    val feedId: String? = null,
    val action: FilterAction = FilterAction.BLOCK,
    val conditions: List<EditableCondition> = listOf(EditableCondition()),
    /**
     * Original JSON expression of the rule being edited, if it was loaded
     * from storage. Used to detect that a simple-mode edit would drop
     * grouping and warn the user. `null` for new rules.
     */
    val originalExpressionJson: String? = null,
    /**
     * True when the loaded rule used a non-flat expression (groups, NONE_OF,
     * nesting). The editor surfaces a one-shot warning before saving.
     */
    val isAdvancedRule: Boolean = false,
    /** Enablement of the rule being edited (new rules start enabled). */
    val editingIsEnabled: Boolean = true,
    /** Creation timestamp of the rule being edited (null for new rules). */
    val editingCreatedAt: Long? = null,
)

/**
 * Flattens an arbitrary expression tree back into the flat condition list used
 * by the simple-mode editor. Group operators are lost — the editor warns
 * the user (via [FilterRuleUiState.isAdvancedRule]) when this happens and
 * preserves [FilterRuleUiState.originalExpressionJson] so callers can detect
 * the case.
 *
 * Legacy match types are mapped onto glob so the editor only ever shows
 * [FilterMatchType.GLOB]/[FilterMatchType.NOT_GLOB]: contains/regex/word
 * become [GLOB], their negations become [NOT_GLOB].
 */
internal fun FilterExpression.flattenToConditions(): List<EditableCondition> =
    when (this) {
        is FilterExpression.Condition -> listOf(condition.toEditable())
        is FilterExpression.AllOf -> children.flatMap { it.flattenToConditions() }
        is FilterExpression.AnyOf -> children.flatMap { it.flattenToConditions() }
        is FilterExpression.NoneOf -> children.flatMap { it.flattenToConditions() }
    }

internal fun FilterCondition.toEditable(): EditableCondition {
    val matchType =
        when (matchType) {
            FilterMatchType.GLOB -> FilterMatchType.GLOB
            FilterMatchType.NOT_GLOB -> FilterMatchType.NOT_GLOB
            FilterMatchType.CONTAINS,
            FilterMatchType.WORD_MATCH,
            FilterMatchType.REGEX -> FilterMatchType.GLOB
            FilterMatchType.NOT_CONTAINS,
            FilterMatchType.NOT_REGEX -> FilterMatchType.NOT_GLOB
        }
    return EditableCondition(field, matchType, pattern)
}

/**
 * True when [this] can be losslessly edited in simple mode: a single leaf
 * or a one-level group of leaves. Nested groups (depth > 1) are *not* flat
 * because the simple-mode editor's flat list cannot represent nesting.
 */
internal fun FilterExpression.isFlatConditionList(): Boolean =
    when (this) {
        is FilterExpression.Condition -> true
        is FilterExpression.AllOf -> children.all { it is FilterExpression.Condition }
        is FilterExpression.AnyOf -> children.all { it is FilterExpression.Condition }
        is FilterExpression.NoneOf -> children.all { it is FilterExpression.Condition }
    }
