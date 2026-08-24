package me.ash.reader.domain.model.filter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A single matching condition: does [field] match [pattern] using [matchType]?
 */
@Serializable
@SerialName("condition")
data class FilterCondition(
    val field: FilterField,
    val matchType: FilterMatchType,
    val pattern: String,
)

/**
 * Boolean expression tree over [FilterCondition]s.
 *
 * Depth is capped at [MAX_DEPTH] to keep the UI (and evaluation) manageable.
 */
@Serializable
sealed class FilterExpression {

    /** Leaf: a single condition. */
    @Serializable
    @SerialName("leaf")
    data class Condition(val condition: FilterCondition) : FilterExpression()

    /** AND of all children. */
    @Serializable
    @SerialName("all_of")
    data class AllOf(val children: List<FilterExpression>) : FilterExpression()

    /** OR of all children. */
    @Serializable
    @SerialName("any_of")
    data class AnyOf(val children: List<FilterExpression>) : FilterExpression()

    /**
     * NOR of all children: true only when *none* of the children match.
     *
     * Note this is group negation, not unary NOT. The rule editor should
     * either restrict [NoneOf] to a single child or label multi-child
     * groups explicitly as "none of" to avoid user confusion.
     */
    @Serializable
    @SerialName("none_of")
    data class NoneOf(val children: List<FilterExpression>) : FilterExpression()

    companion object {
        const val MAX_DEPTH = 3

        /**
         * Builds a flat expression from a list of conditions.
         *
         * Used by the "simple mode" editor: conditions are OR'd for BLOCK rules
         * and AND'd for ALLOW rules, which matches what most users mean.
         */
        fun simple(conditions: List<FilterCondition>, action: FilterAction): FilterExpression? {
            val leaves = conditions.map { Condition(it) }
            return when {
                leaves.isEmpty() -> null
                leaves.size == 1 -> leaves.first()
                action == FilterAction.BLOCK -> AnyOf(leaves)
                else -> AllOf(leaves)
            }
        }

        /** Returns true if adding a group at [depth] would exceed [MAX_DEPTH]. */
        fun exceedsDepth(depth: Int): Boolean = depth >= MAX_DEPTH
    }
}

/** JSON format used to persist [FilterExpression] in the database. */
val FilterExpressionJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/**
 * Serializes this expression to JSON for storage in `filter_rule.expressionJson`.
 */
fun FilterExpression.toJson(): String = FilterExpressionJson.encodeToString(this)

/**
 * Deserializes an expression; unknown/corrupt payloads degrade safely to null
 * so a bad rule can never crash sync.
 */
fun String.toFilterExpressionOrNull(): FilterExpression? = try {
    FilterExpressionJson.decodeFromString(FilterExpression.serializer(), this)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
