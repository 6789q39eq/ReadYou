package me.ash.reader.domain.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.filter.FilterAction
import me.ash.reader.domain.model.filter.toFilterExpressionOrNull
import me.ash.reader.domain.repository.FilterRuleDao
import me.ash.reader.infrastructure.di.DefaultDispatcher
import timber.log.Timber

/**
 * Sync-pipeline hook: drops incoming articles that match BLOCK rules and,
 * when any ALLOW rule exists, keeps only articles matching at least one.
 *
 * Rules are loaded once per feed batch and compiled once; with zero enabled
 * rules the hook is a passthrough no-op, so the feature costs nothing until
 * configured (see docs/plans/advanced-feed-filtering.md §2.7).
 */
@Singleton
class ApplyFeedFiltersUseCase
@Inject
constructor(
    private val filterRuleDao: FilterRuleDao,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {

    /**
     * Filters [articles] for [feedId] using the account's enabled global +
     * feed-specific rules. Never throws: any failure logs and returns the
     * input unchanged so sync can never be broken by filtering.
     */
    suspend operator fun invoke(
        accountId: Int,
        feedId: String,
        articles: List<Article>,
    ): List<Article> = try {
        withContext(defaultDispatcher) {
            val rules =
                filterRuleDao.findEnabledForAccountAndFeed(accountId, feedId)
                    .mapNotNull { rule ->
                        rule.expressionJson.toFilterExpressionOrNull()?.let { expression ->
                            CompiledFilterRule(
                                id = rule.id,
                                action = rule.action,
                                expression = expression,
                            )
                        }
                    }
            if (rules.isEmpty()) {
                articles
            } else {
                articles.filter { article ->
                    ArticleFilterEngine.shouldKeep(article.toSnapshot(), rules)
                }
            }
        }
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "applyFeedFilters failed; keeping all %d articles", articles.size)
        articles
    }

    private fun Article.toSnapshot() =
        ArticleSnapshot(
            title = title,
            author = author,
            link = link,
            content = shortDescription,
        )

    companion object {
        private const val TAG = "ApplyFeedFilters"
    }
}
