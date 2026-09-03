package me.ash.reader.ui.page.settings.filter

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ash.reader.R
import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.FilterRule
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYDialog
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.component.swipe.SwipeAction
import me.ash.reader.ui.component.swipe.SwipeableActionsBox
import me.ash.reader.ui.ext.surfaceColorAtElevation
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.theme.palette.onLight

/**
 * Rule management page (compact layout): lists global and per-feed rules for
 * the current account and opens the editor dialog/screen per rule.
 */
@Composable
fun FiltersPage(
    onBack: () -> Unit,
    navigateToEditRule: (String?) -> Unit,
    viewModel: FilterRuleViewModel = hiltViewModel(),
) {
    var deleteCandidate by remember { mutableStateOf<FilterRule?>(null) }

    FiltersListContent(
        viewModel = viewModel,
        isTwoPane = false,
        onBack = onBack,
        navigateToEditRule = navigateToEditRule,
        onDeleteRequest = { deleteCandidate = it },
        deleteCandidate = deleteCandidate,
        onDismissDelete = { deleteCandidate = null },
    )
}

/**
 * The rule list itself, shared by the compact page and the list pane of
 * [FiltersListDetailPage].
 */
@Composable
fun FiltersListContent(
    viewModel: FilterRuleViewModel,
    isTwoPane: Boolean,
    onBack: () -> Unit,
    navigateToEditRule: (String?) -> Unit,
    deleteCandidate: FilterRule?,
    onDismissDelete: () -> Unit,
    onDeleteRequest: (FilterRule) -> Unit,
) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()

    RYScaffold(
        containerColor =
            MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
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
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.filter_rule_add),
                tint = MaterialTheme.colorScheme.onSurface,
            ) {
                viewModel.startEditing(null)
                navigateToEditRule(null)
            }
        },
        content = {
            val (globalRules, feedRules) =
                remember(rules) { rules.partition { it.feedId == null } }
            LazyColumn {
                item {
                    DisplayText(text = stringResource(R.string.filter_rules), desc = "")
                    Spacer(modifier = Modifier.height(16.dp))
                }
                if (rules.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.filter_rule_empty),
                            modifier = Modifier.padding(horizontal = 20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                } else {
                    if (globalRules.isNotEmpty()) {
                        item {
                            Subtitle(
                                text = stringResource(R.string.filter_rule_global),
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                        items(globalRules.size) { index ->
                            val rule = globalRules[index]
                            RuleRow(
                                rule = rule,
                                onToggle = { viewModel.setEnabled(rule, it) },
                                onEdit = {
                                    viewModel.startEditing(rule.id)
                                    navigateToEditRule(rule.id)
                                },
                                onDelete = { onDeleteRequest(rule) },
                            )
                        }
                    }
                    if (feedRules.isNotEmpty()) {
                        item {
                            Subtitle(
                                text = stringResource(R.string.filter_rule_feed),
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                        items(feedRules.size) { index ->
                            val rule = feedRules[index]
                            RuleRow(
                                rule = rule,
                                onToggle = { viewModel.setEnabled(rule, it) },
                                onEdit = {
                                    viewModel.startEditing(rule.id)
                                    navigateToEditRule(rule.id)
                                },
                                onDelete = { onDeleteRequest(rule) },
                            )
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(
                        modifier =
                            Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
                    )
                }
            }
        },
    )

    deleteCandidate?.let { rule ->
        RYDialog(
            visible = true,
            onDismissRequest = onDismissDelete,
            title = { Text(text = stringResource(R.string.filter_rule_delete)) },
            text = { Text(text = stringResource(R.string.filter_rule_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(rule)
                    onDismissDelete()
                }) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: FilterRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    SwipeableActionsBox(
        endActions =
            listOf(
                SwipeAction(
                    onSwipe = onDelete,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    },
                    background = MaterialTheme.colorScheme.errorContainer,
                )
            ),
        backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surface,
    ) {
        SettingItem(
            title = rule.name,
            desc =
                stringResource(
                    if (rule.action == FilterAction.BLOCK) {
                        R.string.filter_rule_action_block_desc
                    } else {
                        R.string.filter_rule_action_allow_desc
                    }
                ),
            onClick = onEdit,
        ) {
            Switch(checked = rule.isEnabled, onCheckedChange = onToggle)
        }
    }
}
