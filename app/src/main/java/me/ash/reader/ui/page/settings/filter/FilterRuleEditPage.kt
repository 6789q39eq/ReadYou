package me.ash.reader.ui.page.settings.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ash.reader.R
import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.FilterField
import me.ash.reader.domain.model.filter.FilterMatchType
import me.ash.reader.domain.service.ArticleFilterEngine
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYDialog
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.Subtitle

/**
 * Create/edit a filter rule (simple mode): name, action, and a flat list of
 * condition rows. Conditions are OR'd for BLOCK rules and AND'd for ALLOW
 * rules when saved — see [FilterExpression.simple].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterRuleEditPage(
    onBack: () -> Unit,
    viewModel: FilterRuleViewModel = hiltViewModel(),
) {
    FilterRuleEditContent(
        viewModel = viewModel,
        isTwoPane = false,
        onBack = onBack,
    )
}

/**
 * The editor itself, shared by the compact page and the detail pane of
 * [FiltersListDetailPage].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterRuleEditContent(
    viewModel: FilterRuleViewModel,
    isTwoPane: Boolean,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showTestDialog by remember { mutableStateOf(false) }

    RYScaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBack,
            )
        },
        actions = {
            FeedbackIconButton(
                imageVector = Icons.Outlined.Science,
                contentDescription = stringResource(R.string.filter_regex_test_title),
                tint = MaterialTheme.colorScheme.onSurface,
            ) { showTestDialog = true }
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onBack) {
                    Text(text = stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        if (viewModel.save()) onBack()
                    }
                ) {
                    Text(text = stringResource(R.string.save))
                }
            }
        },
        content = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Subtitle(
                    text = stringResource(R.string.filter_rule_name),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::updateName,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.filter_rule_name)) },
                )
                Spacer(modifier = Modifier.height(16.dp))

                Subtitle(
                    text = stringResource(R.string.filter_rules),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                ) {
                    SegmentedButton(
                        selected = state.action == FilterAction.BLOCK,
                        onClick = { viewModel.updateAction(FilterAction.BLOCK) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(text = stringResource(R.string.filter_rule_action_block))
                    }
                    SegmentedButton(
                        selected = state.action == FilterAction.ALLOW,
                        onClick = { viewModel.updateAction(FilterAction.ALLOW) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(text = stringResource(R.string.filter_rule_action_allow))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text =
                        stringResource(
                            if (state.action == FilterAction.BLOCK) {
                                R.string.filter_rule_action_block_desc
                            } else {
                                R.string.filter_rule_action_allow_desc
                            }
                        ),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(16.dp))

                Subtitle(
                    text = stringResource(R.string.filter_rule_condition_pattern),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    text = stringResource(R.string.filter_rule_simple_mode_hint),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))

                state.conditions.forEachIndexed { index, condition ->
                    ConditionRow(
                        condition = condition,
                        canRemove = state.conditions.size > 1,
                        onFieldChange = {
                            viewModel.updateCondition(index, condition.copy(field = it))
                        },
                        onMatchTypeChange = {
                            viewModel.updateCondition(index, condition.copy(matchType = it))
                        },
                        onPatternChange = {
                            viewModel.updateCondition(index, condition.copy(pattern = it))
                        },
                        onRemove = { viewModel.removeCondition(index) },
                    )
                }
                TextButton(
                    onClick = viewModel::addCondition,
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) {
                    Icon(imageVector = Icons.Outlined.Add, contentDescription = null)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = stringResource(R.string.filter_rule_add_condition))
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        },
    )

    if (showTestDialog) {
        RegexTestDialog(onDismiss = { showTestDialog = false }, conditions = state.conditions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConditionRow(
    condition: EditableCondition,
    canRemove: Boolean,
    onFieldChange: (FilterField) -> Unit,
    onMatchTypeChange: (FilterMatchType) -> Unit,
    onPatternChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val regexError =
        if (
            condition.matchType == FilterMatchType.REGEX ||
                condition.matchType == FilterMatchType.NOT_REGEX
        ) {
            runCatching { Regex(condition.pattern) }
                .exceptionOrNull()
                ?.let { stringResource(R.string.filter_regex_invalid) }
        } else {
            null
        }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldDropdown(
                selected = condition.field,
                onSelect = onFieldChange,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            MatchTypeDropdown(
                selected = condition.matchType,
                onSelect = onMatchTypeChange,
                modifier = Modifier.weight(1f),
            )
            if (canRemove) {
                FeedbackIconButton(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onRemove,
                )
            }
        }
        OutlinedTextField(
            value = condition.pattern,
            onValueChange = onPatternChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(text = stringResource(R.string.filter_rule_condition_pattern)) },
            isError = regexError != null,
            supportingText = regexError?.let { error -> { Text(text = error) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumDropdown(
    options: List<T>,
    label: @Composable (T) -> String,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = label(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun FieldDropdown(
    selected: FilterField,
    onSelect: (FilterField) -> Unit,
    modifier: Modifier = Modifier,
) {
    EnumDropdown(
        options = FilterField.entries.toList(),
        selected = selected,
        onSelect = onSelect,
        label = {
            stringResource(
                when (it) {
                    FilterField.TITLE -> R.string.filter_field_title
                    FilterField.AUTHOR -> R.string.filter_field_author
                    FilterField.URL -> R.string.filter_field_url
                    FilterField.CONTENT -> R.string.filter_field_content
                }
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun MatchTypeDropdown(
    selected: FilterMatchType,
    onSelect: (FilterMatchType) -> Unit,
    modifier: Modifier = Modifier,
) {
    EnumDropdown(
        options = FilterMatchType.entries.toList(),
        selected = selected,
        onSelect = onSelect,
        label = {
            stringResource(
                when (it) {
                    FilterMatchType.CONTAINS -> R.string.filter_match_type_contains
                    FilterMatchType.NOT_CONTAINS -> R.string.filter_match_type_not_contains
                    FilterMatchType.WORD_MATCH -> R.string.filter_match_type_word_match
                    FilterMatchType.REGEX -> R.string.filter_match_type_regex
                    FilterMatchType.NOT_REGEX -> R.string.filter_match_type_not_regex
                }
            )
        },
        modifier = modifier,
    )
}
