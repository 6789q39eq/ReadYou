package me.ash.reader.ui.page.settings.filter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import me.ash.reader.R
import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.FilterCondition
import me.ash.reader.domain.service.ArticleFilterEngine
import me.ash.reader.domain.service.ArticleSnapshot
import me.ash.reader.ui.component.base.RYDialog

/**
 * Live "try your pattern" box: paste a sample title/content and instantly see
 * whether the current conditions would match it. The summary line shows how
 * the per-condition results combine under the rule's [action] (OR for Block,
 * AND for Allow-only) so the dialog agrees with the engine's evaluation.
 */
@Composable
fun RegexTestDialog(
    action: FilterAction,
    conditions: List<EditableCondition>,
    onDismiss: () -> Unit,
) {
    var sample by remember { mutableStateOf("") }

    val results: List<Pair<EditableCondition, Boolean>>? =
        if (sample.isBlank()) {
            null
        } else {
            val snapshot =
                ArticleSnapshot(
                    title = sample,
                    author = "",
                    link = "",
                    content = sample,
                )
            conditions
                .filter { it.pattern.isNotBlank() }
                .map { condition ->
                    condition to
                        ArticleFilterEngine.matchesCondition(
                            article = snapshot,
                            condition =
                                FilterCondition(
                                    field = condition.field,
                                    matchType = condition.matchType,
                                    pattern = condition.pattern.trim(),
                                ),
                        )
                }
        }

    RYDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.filter_regex_test_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = sample,
                    onValueChange = { sample = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(R.string.filter_regex_test_sample)) },
                    minLines = 2,
                )
                Spacer(modifier = Modifier.height(12.dp))
                results?.let { list ->
                    if (list.isNotEmpty()) {
                        val matchedCount = list.count { it.second }
                        val summaryRes =
                            when (action) {
                                FilterAction.BLOCK -> R.string.filter_regex_test_summary_block
                                FilterAction.ALLOW -> R.string.filter_regex_test_summary_allow
                            }
                        Text(
                            text = stringResource(summaryRes, matchedCount, list.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        list.forEach { (condition, matched) ->
                            Text(
                                text =
                                    "${condition.field.name} ${condition.matchType.name} " +
                                        "\"${condition.pattern.trim()}\": " +
                                        stringResource(
                                            if (matched) R.string.filter_regex_test_yes
                                            else R.string.filter_regex_test_no
                                        ),
                                color =
                                    if (matched) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.close)) }
        },
    )
}
