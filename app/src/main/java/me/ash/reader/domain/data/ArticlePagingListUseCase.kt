package me.ash.reader.domain.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.paging.ItemSnapshotList
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.cachedIn
import androidx.paging.filter
import javax.inject.Inject
import kotlin.text.trim
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.article.mapPagingFlowItem
import me.ash.reader.domain.model.filter.toFilterExpressionOrNull
import me.ash.reader.domain.repository.FilterRuleDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.ArticleFilterEngine
import me.ash.reader.domain.service.ArticleSnapshot
import me.ash.reader.domain.service.CompiledFilterRule
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.android.AndroidStringsHelper
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.preference.SettingsProvider

class ArticlePagingListUseCase
@Inject
constructor(
    private val rssService: RssService,
    private val androidStringsHelper: AndroidStringsHelper,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val settingsProvider: SettingsProvider,
    private val filterStateUseCase: FilterStateUseCase,
    private val accountService: AccountService,
    private val filterRuleDao: FilterRuleDao,
) {

    private val mutablePagerFlow =
        MutableStateFlow<PagerData>(
            PagerData(filterState = filterStateUseCase.filterStateFlow.value)
        )
    val pagerFlow: StateFlow<PagerData> = mutablePagerFlow

    var itemSnapshotList by
        mutableStateOf(
            ItemSnapshotList<ArticleFlowItem>(
                placeholdersBefore = 0,
                placeholdersAfter = 0,
                items = emptyList(),
            )
        )
        private set

    val pagingDataPresenter =
        object : PagingDataPresenter<ArticleFlowItem>() {
            override suspend fun presentPagingDataEvent(event: PagingDataEvent<ArticleFlowItem>) {
                itemSnapshotList = snapshot()
            }
        }

    init {
        applicationScope.launch(ioDispatcher) {
            filterStateUseCase.filterStateFlow
                .combine(accountService.currentAccountIdFlow) { filterState, accountId ->
                    filterState
                }
                .collect { filterState ->
                    val searchContent = filterState.searchContent
                    // Middle tab (Unread) doubles as the filtered view:
                    // - unread pseudo-rule controls unread-only vs read+unread
                    // - selected user rules apply as a view-time filter.
                    // All (right) shows everything unfiltered, Starred (left)
                    // is unchanged and ignores rules.
                    val isMiddle = filterState.filter.isUnread()
                    val effectiveUnread =
                        if (isMiddle) filterState.unreadOnlyInFiltered
                        else filterState.filter.isUnread()
                    val viewRules: List<CompiledFilterRule> =
                        if (isMiddle && filterState.appliedRuleIds.isNotEmpty()) {
                            loadSelectedRules(filterState.appliedRuleIds)
                        } else {
                            emptyList()
                        }

                    mutablePagerFlow.value =
                        PagerData(
                            Pager(
                                    config = PagingConfig(pageSize = 50, enablePlaceholders = false)
                                ) {
                                    if (!searchContent.isNullOrBlank()) {
                                        rssService
                                            .get()
                                            .searchArticles(
                                                content = searchContent.trim(),
                                                groupId = filterState.group?.id,
                                                feedId = filterState.feed?.id,
                                                isStarred = filterState.filter.isStarred(),
                                                isUnread = effectiveUnread,
                                                sortAscending =
                                                    settingsProvider.settings.flowSortUnreadArticles
                                                        .value,
                                            )
                                    } else {
                                        rssService
                                            .get()
                                            .pullArticles(
                                                groupId = filterState.group?.id,
                                                feedId = filterState.feed?.id,
                                                isStarred = filterState.filter.isStarred(),
                                                isUnread = effectiveUnread,
                                                sortAscending =
                                                    settingsProvider.settings.flowSortUnreadArticles
                                                        .value,
                                            )
                                    }
                                }
                                .flow
                                .map { it.mapPagingFlowItem(androidStringsHelper) }
                                .map { pagingData ->
                                    if (viewRules.isEmpty()) pagingData
                                    else {
                                        pagingData.filter { item ->
                                            if (item is ArticleFlowItem.Article) {
                                                val a = item.articleWithFeed.article
                                                ArticleFilterEngine.shouldKeep(
                                                    ArticleSnapshot(
                                                        title = a.title,
                                                        author = a.author,
                                                        link = a.link,
                                                        content = a.shortDescription,
                                                    ),
                                                    viewRules,
                                                )
                                            } else {
                                                true
                                            }
                                        }
                                    }
                                }
                                .cachedIn(applicationScope),
                            filterState = filterState,
                        )
                }
        }
        applicationScope.launch {
            pagerFlow.collectLatest { (pager, _) ->
                pager.collectLatest { pagingDataPresenter.collectFrom(it) }
            }
        }
    }

    /**
     * Loads the user-selected rules for the middle-tab view filter.
     * Only enabled rules apply; invalid expressions are skipped so a bad
     * rule can never empty the list. Runs on the caller's IO context.
     */
    private suspend fun loadSelectedRules(ruleIds: Set<String>): List<CompiledFilterRule> {
        if (ruleIds.isEmpty()) return emptyList()
        val compiled = ArrayList<CompiledFilterRule>(ruleIds.size)
        for (id in ruleIds) {
            val rule = runCatching { filterRuleDao.findById(id) }.getOrNull() ?: continue
            if (!rule.isEnabled) continue
            val expression = rule.expressionJson.toFilterExpressionOrNull() ?: continue
            compiled += CompiledFilterRule(id = rule.id, action = rule.action, expression = expression)
        }
        return compiled
    }
}

data class PagerData(
    val pager: Flow<PagingData<ArticleFlowItem>> = emptyFlow(),
    val filterState: FilterState = FilterState(),
)
