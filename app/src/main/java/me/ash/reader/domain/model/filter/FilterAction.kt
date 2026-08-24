package me.ash.reader.domain.model.filter

/**
 * What happens to articles matching a rule.
 *
 * - [BLOCK]: drop matching articles (block wins over allow).
 * - [ALLOW]: when any ALLOW rule exists, only articles matching at least
 *   one ALLOW rule are kept.
 */
enum class FilterAction {
    BLOCK,
    ALLOW,
}
