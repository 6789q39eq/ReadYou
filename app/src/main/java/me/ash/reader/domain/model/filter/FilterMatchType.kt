package me.ash.reader.domain.model.filter

/**
 * How a condition's pattern is matched against the target field.
 *
 * - [CONTAINS] / [NOT_CONTAINS]: case-insensitive substring match.
 * - [WORD_MATCH]: case-insensitive whole-word match (word boundaries).
 * - [REGEX] / [NOT_REGEX]: full Kotlin regex match (case-insensitive by default).
 */
enum class FilterMatchType {
    CONTAINS,
    NOT_CONTAINS,
    WORD_MATCH,
    REGEX,
    NOT_REGEX,
}
