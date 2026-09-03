package me.ash.reader.ui.page.settings.filter

import android.os.Parcelable
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.ash.reader.domain.model.filter.FilterRule

/**
 * Large-screen (expanded width) rule management: rule list in the list pane,
 * rule editor in the detail pane via [NavigableListDetailPaneScaffold] — the
 * same pattern `ArticleListReaderPage` uses, so power users can iterate on
 * rules without leaving the list.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun FiltersListDetailPage(
    scaffoldNavigator: ThreePaneScaffoldNavigator<FilterEditData>,
    navigateToDetail: (FilterEditData) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    viewModel: FilterRuleViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    var deleteCandidate by remember { mutableStateOf<FilterRule?>(null) }

    NavigableListDetailPaneScaffold(
        navigator = scaffoldNavigator,
        listPane = {
            AnimatedPane {
                FiltersListContent(
                    viewModel = viewModel,
                    isTwoPane = true,
                    onBack = { scope.launch { scaffoldNavigator.navigateBack() } },
                    navigateToEditRule = { ruleId ->
                        viewModel.startEditing(ruleId)
                        navigateToDetail(FilterEditData(ruleId))
                    },
                    deleteCandidate = deleteCandidate,
                    onDismissDelete = { deleteCandidate = null },
                    onDeleteRequest = { deleteCandidate = it },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                // The editor reads its target from the ViewModel state that the
                // list pane set right before navigating to the detail pane.
                FilterRuleEditContent(
                    viewModel = viewModel,
                    isTwoPane = true,
                    onBack = { scope.launch { scaffoldNavigator.navigateBack() } },
                )
            }
        },
    )
}

/** Parcelable navigation payload for the detail pane (survives process death). */
@Parcelize
data class FilterEditData(val ruleId: String?) : Parcelable
