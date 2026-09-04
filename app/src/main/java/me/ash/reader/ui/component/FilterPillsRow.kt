package me.ash.reader.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.data.FilterState
import me.ash.reader.domain.model.filter.FilterRule
import me.ash.reader.ui.component.base.RYSelectionChip

/**
 * Quick-toggle pills shown above the middle (filtered-view) tab.
 *
 * Mirrors the chip style used in the feed long-press sheet
 * ([me.ash.reader.ui.page.home.feeds.FeedOptionView]): one pill for the
 * unread pseudo-rule plus one pill per enabled filter rule. Tapping a pill
 * toggles just that entry so the filtered view can be adjusted inline
 * without opening [FilterRuleSelectionDialog].
 */
@Composable
fun FilterPillsRow(
    filterState: FilterState,
    rules: List<FilterRule>,
    onToggleUnread: (Boolean) -> Unit,
    onToggleRule: (ruleId: String, selected: Boolean) -> Unit,
    onOpenEditor: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item(key = "unread") {
            RYSelectionChip(
                content = stringResource(R.string.unread),
                selected = filterState.unreadOnlyInFiltered,
                onClick = { onToggleUnread(!filterState.unreadOnlyInFiltered) },
            )
        }
        items(rules, key = { it.id }) { rule ->
            val selected = filterState.appliedRuleIds.contains(rule.id)
            RYSelectionChip(
                content = rule.name,
                selected = selected,
                enabled = rule.isEnabled,
                onClick = { onToggleRule(rule.id, !selected) },
            )
        }
    }
}
