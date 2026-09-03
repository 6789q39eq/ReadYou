package me.ash.reader.domain.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.filter.toFilterExpressionOrNull
import me.ash.reader.domain.repository.FilterRuleDao
import me.ash.reader.infrastructure.di.DefaultDispatcher
import timber.log.Timber

/**
 * Sync-pipeline hook: drops incoming articles that match BLOCK rules and,
 * when any ALLOW rule exists, keeps only articles matching at least one.
 *
 * Rules are loaded once per feed batch and their regexes compiled once; with
 * zero enabled rules the hook is a passthrough no-op, so the feature costs
 * nothing until configured (see docs/plans/advanced-feed-filtering.md §2.7).
 *
 * Each article's evaluation is wrapped in [PER_ARTICLE_TIMEOUT_MS] as a
 * best-effort guard against pathological user-supplied regexes (ReDoS).
 * Blocking Java regex evaluation is not cooperatively cancellable, so the
 * timeout bounds everything *around* the match (scheduling, DAO, batch
 * overhead) but cannot preempt a stuck match itself; the 500-char pattern
 * cap in [ArticleFilterEngine] is the primary ReDoS mitigation. On timeout
 * the article is kept and the event logged so the user can locate the rule.
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
    ): List<Article> =
        partition(accountId, feedId, articles).first

    /**
     * Like [invoke] but also returns the dropped articles, so callers that
     * must account for every fetched item (e.g. Google Reader, which would
     * otherwise re-fetch dropped items on every sync) can handle them.
     */
    suspend fun partition(
        accountId: Int,
        feedId: String,
        articles: List<Article>,
    ): Pair<List<Article>, List<Article>> = try {
        withContext(defaultDispatcher) {
            val rules = loadCompiled(accountId, feedId)
            if (rules.isEmpty()) {
                articles to emptyList()
            } else {
                val kept = ArrayList<Article>(articles.size)
                val dropped = ArrayList<Article>()
                for (article in articles) {
                    if (evaluateWithTimeout(article.toSnapshot(), rules)) {
                        kept += article
                    } else {
                        dropped += article
                    }
                }
                kept to dropped
            }
        }
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "applyFeedFilters failed; keeping all %d articles", articles.size)
        articles to emptyList()
    }

    /**
     * Filters a batch that mixes several feeds (Fever sync) while preserving
     * the input order. Rules are loaded once per distinct feed.
     */
    suspend fun filterMixedFeeds(
        accountId: Int,
        articles: List<Article>,
        feedIdOf: (Article) -> String = { it.feedId },
    ): List<Article> = try {
        withContext(defaultDispatcher) {
            val rulesByFeed = mutableMapOf<String, List<CompiledFilterRule>>()
            articles.filter { article ->
                val feedId = feedIdOf(article)
                val rules = rulesByFeed.getOrPut(feedId) { loadCompiled(accountId, feedId) }
                rules.isEmpty() || evaluateWithTimeout(article.toSnapshot(), rules)
            }
        }
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "applyFeedFilters failed; keeping all %d articles", articles.size)
        articles
    }

    private suspend fun loadCompiled(
        accountId: Int,
        feedId: String,
    ): List<CompiledFilterRule> =
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

    /**
     * Evaluates [article] against [rules] with a per-article timeout. Returns
     * `true` (keep) on timeout so a slow rule cannot block sync.
     */
    private suspend fun evaluateWithTimeout(
        article: ArticleSnapshot,
        rules: List<CompiledFilterRule>,
    ): Boolean =
        try {
            withTimeoutOrNull(PER_ARTICLE_TIMEOUT_MS) {
                ArticleFilterEngine.shouldKeep(article, rules)
            } ?: run {
                Timber.tag(TAG)
                    .w(
                        "Filter evaluation timed out after %dms for article title=%s; keeping it",
                        PER_ARTICLE_TIMEOUT_MS,
                        article.title.take(80),
                    )
                true
            }
        } catch (_: TimeoutCancellationException) {
            // Defensive: withTimeoutOrNull already converts to null, but if
            // the engine ever throws on a coroutine boundary we still keep
            // the article.
            true
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

        /**
         * Per-article evaluation budget. Light ReDoS mitigation; users with
         * 500-char regexes still cannot freeze the sync worker.
         */
        const val PER_ARTICLE_TIMEOUT_MS: Long = 250L
    }
}
