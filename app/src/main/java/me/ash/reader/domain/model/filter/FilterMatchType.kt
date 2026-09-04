package me.ash.reader.domain.model.filter

/**
 * How a condition's pattern is matched against the target field.
 *
 * - [GLOB] / [NOT_GLOB]: case-insensitive glob match (`*`, `?`, `[...]`,
 *   `{a,b}`). This is the only match type offered by the editor.
 * - Legacy values below are retained only so rules created by older versions
 *   still deserialize and evaluate; the editor maps them onto glob on load.
 */
enum class FilterMatchType {
    GLOB,
    NOT_GLOB,
    @Deprecated("Use GLOB instead")
    CONTAINS,
    @Deprecated("Use NOT_GLOB instead")
    NOT_CONTAINS,
    @Deprecated("Use GLOB instead")
    WORD_MATCH,
    @Deprecated("Use GLOB instead")
    REGEX,
    @Deprecated("Use NOT_GLOB instead")
    NOT_REGEX,
}
