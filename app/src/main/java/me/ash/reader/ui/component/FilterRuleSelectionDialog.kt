package me.ash.reader.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ash.reader.R
import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.ui.component.base.RYDialog

/**
 * Middle-tab selector: which view-time filters apply to the filtered view.
 *
 * - "Unread only" is a pseudo-rule: it never appears in Settings → Filter
 *   rules, it only toggles unread-only vs read+unread in the middle tab.
 * - User rules below come from [FilterViewSelectionViewModel.rulesFlow];
 *   ticking them adds their id to [me.ash.reader.domain.data.FilterState.appliedRuleIds].
 * - All (right) ignores this selection and shows everything; Starred (left)
 *   is unchanged and also ignores it.
 */
@Composable
fun FilterRuleSelectionDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    viewModel: FilterViewSelectionViewModel = hiltViewModel(),
) {
    if (!visible) return

    val filterState by viewModel.filterStateFlow.collectAsStateWithLifecycle()
    val rules by viewModel.rulesFlow.collectAsStateWithLifecycle()

    RYDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.filter_view_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.filter_view_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Unread pseudo-rule (not in the rules list).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.setUnreadOnly(!filterState.unreadOnlyInFiltered)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = filterState.unreadOnlyInFiltered,
                        onCheckedChange = { viewModel.setUnreadOnly(it) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.filter_view_unread_only),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.filter_view_unread_only_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.filter_view_rules_section),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (rules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.filter_view_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                    ) {
                        items(rules, key = { it.id }) { rule ->
                            val selected = filterState.appliedRuleIds.contains(rule.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = rule.isEnabled) {
                                        viewModel.toggleRule(rule.id, !selected)
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selected,
                                    enabled = rule.isEnabled,
                                    onCheckedChange = { checked ->
                                        viewModel.toggleRule(rule.id, checked)
                                    },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rule.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (rule.isEnabled) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                    Text(
                                        text = stringResource(
                                            if (rule.action == FilterAction.BLOCK) {
                                                R.string.filter_rule_action_block_desc
                                            } else {
                                                R.string.filter_rule_action_allow_desc
                                            }
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.filter_view_done))
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.clearSelection() }) {
                Text(text = stringResource(R.string.filter_view_clear))
            }
        },
    )
}
