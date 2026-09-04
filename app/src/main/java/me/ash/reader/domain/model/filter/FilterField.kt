package me.ash.reader.domain.model.filter

/**
 * The article field a filter condition matches against.
 *
 * [URL] is retained only for reading back rules created by older versions;
 * the editor no longer offers it. [ALL] matches when any of title, author
 * or content matches.
 */
enum class FilterField {
    TITLE,
    AUTHOR,
    URL,
    CONTENT,
    ALL,
}
