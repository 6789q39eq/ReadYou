package me.ash.reader.domain.service

import me.ash.reader.domain.model.filter.FilterAction

/**
 * A minimal, Android-free projection of an [me.ash.reader.domain.model.article.Article]
 * used by [ArticleFilterEngine] so filter evaluation stays unit-testable on the JVM.
 */
data class ArticleSnapshot(
    val title: String,
    val author: String?,
    val link: String,
    val content: String,
)
