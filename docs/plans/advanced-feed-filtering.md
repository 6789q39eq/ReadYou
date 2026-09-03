# Advanced Feed Filtering — Design & Implementation Plan

> Status: Implemented · Branch: `main` (commits `e1528fa0` → `1ba823e3`)
> Author: contributor proposal + implementation, 2026-08
>
> Goal: per-feed (and global) **allow / block filtering of incoming articles** using
> keyword lists and boolean expressions with light regex support, in a UI that fits
> Read You's existing Material 3 design language on phones *and* large screens.

---

## 1. Research summary (state of the art)

### 1.1 Tools surveyed

| Tool | Model | Matching | Boolean | Regex | Notes |
|---|---|---|---|---|---|
| **RSSBrew** (yinan-c/RSSbrew) | server-side feed processor | contains / not-contains / regex on Link, Title, Description | filters grouped with AND/OR/NOT; groups combined too | yes | closest feature match to this proposal |
| **Miniflux** | self-hosted server | `FieldName=RegEx` line rules on Title/URL/Author/Tag/Content/Date | implicit (rule order = first-match wins) | RE2 syntax | block + keep lists; global + per-feed; documented precedence: Global Block → Feed Block → Global Keep → Feed Keep |
| **Feeder.co** | commercial web/app | keyword include/exclude per feed | simple | no | notifications integrate with filters |
| **FeedFlow** (prof18/feed-flow) | open-source KMP reader | plain blocked-keyword list (`blocked_word` table), SQL `LIKE %kw%` NOCASE on title/subtitle | none | no | simplest viable model; good precedent for Room schema |
| **siftrss / feedfilter.link** | web proxies | whitelist/blacklist keywords, regex | limited | yes | shows users expect allow+block together |
| **Inoreader** | commercial | keyword conditions per rule, "match whole words" toggle | AND between conditions in a rule | premium | best-in-class rule-builder UX |

### 1.2 Key takeaways adopted here

1. **Block wins over allow** (Miniflux precedence). If an article matches a block
   rule it is dropped even if an allow rule matches.
2. **Two tiers**: *global* (account-level defaults) and *per-feed* overrides.
   Per-feed rules are evaluated after global rules, same as Miniflux.
3. **Structured boolean expressions over free-form DSL.** RSSBrew's group model
   (conditions joined by AND/OR/NOT, groups combinable) maps cleanly to a small
   serializable expression tree — easier to render as UI chips than a text DSL,
   and safer than asking users to write regex-heavy one-liners.
4. **"Light regex in a standard format"** = Java/Kotlin `Regex` (which is close to
   RE2 for the constructs users actually need). We expose regex as an explicit
   *match type* per condition (`CONTAINS`, `NOT_CONTAINS`, `REGEX`, `NOT_REGEX`,
   `WORD_MATCH`) rather than making every field a regex — this mirrors Inoreader's
   approach and avoids catastrophic backtracking foot-guns from casual users.
5. **Match scope fields**: title, author, link/URL, content/description, and
   (future) tags. These are the fields Miniflux/RSSBrew expose and the ones
   Read You already stores on `Article`.
6. **Action semantics**: filtered articles are **not inserted** at sync time
   (Miniflux behavior — keeps DB clean, unread counts correct). A future
   extension can add "mark read instead of hide".

### 1.3 UI/UX inspiration

- **Inoreader rule builder**: rows of condition chips + dropdown operators;
  progressive disclosure (simple mode default, advanced boolean editor behind a
  toggle).
- **Gmail filter editor**: single dialog, "from/subject/has the words/doesn't have"
  text fields, then action checkboxes — familiar mental model, works well narrow.
- **Material 3 canonical layouts** (developer.android.com/develop/adaptive-apps):
  list-detail via `NavigableListDetailPaneScaffold` for the rules management page
  on expanded widths (≥840dp ≈ desktop 1080p/4K windows); single-pane navigation
  on compact widths. Read You already uses exactly this scaffold in
  `ui/page/adaptive/ArticleListReadingPage.kt`, so we reuse the pattern, not a new one.
- **Read You's own conventions**: bottom drawers for object options
  (`BottomDrawer`, `FeedOptionDrawer`), `TextFieldDialog`/`RadioDialog` for input,
  `RYSelectionChip` for toggles, settings pages under `ui/page/settings/*`.

---

## 2. Feature specification

### 2.1 Concepts

- **FilterRule**: named rule owned by either the account (global) or a feed.
- **Condition**: `field ∈ {TITLE, AUTHOR, URL, CONTENT}` ×
  `matchType ∈ {CONTAINS, NOT_CONTAINS, WORD_MATCH, REGEX, NOT_REGEX}` × `pattern`.
- **Expression**: conditions combined into a tree:
  - `ALL_OF(list)` (AND), `ANY_OF(list)` (OR), `NONE_OF(list)` (NOT),
    leaf `CONDITION(c)`. Depth capped at 3 to keep UI manageable.
- **Rule action**: `BLOCK` (drop matching articles) or `ALLOW`
  (keep only matching articles when any ALLOW rule exists).
- **Evaluation order** (per synced article):
  1. Global BLOCK rules → drop on first match.
  2. Feed BLOCK rules → drop on first match.
  3. If any global/feed ALLOW rule exists: keep only if some ALLOW rule matches.
- Invalid regexes are skipped safely (logged, never crash sync) and surfaced in
  the UI with inline validation.

### 2.2 Storage

New Room entity, new table, auto-migration v7 → v8:

```kotlin
@Entity(
    tableName = "filter_rule",
)
data class FilterRule(
    @PrimaryKey val id: String,            // accountId.spacerDollar(uuid)
    @ColumnInfo(index = true) val accountId: Int,
    @ColumnInfo(index = true) val feedId: String?, // null ⇒ global (account-level)
    val name: String,
    val isEnabled: Boolean = true,
    val action: String,                    // "BLOCK" | "ALLOW"
    val expressionJson: String,            // serialized FilterExpression
    val createdAt: Long,
)
```

- Expression stored as JSON via kotlinx.serialization (already a project
  dependency: `libs.kotlinx.serialization.json`). One column keeps migrations
  trivial and mirrors how `syncBlockList` already lives inside `Account`.
- Schema JSON exported to `app/schemas/me.ash.reader.infrastructure.db.AndroidDatabase/8.json`
  (project convention: bump `version = 7` → `8` in `AndroidDatabase.kt`,
  add `AutoMigration(from = 7, to = 8)`).

### 2.3 Domain layer (new package, fully additive)

```
domain/model/filter/
    FilterRule.kt          (entity above)
    FilterField.kt         (enum)
    FilterMatchType.kt     (enum)
    FilterAction.kt        (enum)
    FilterExpression.kt    (sealed class + serializer, kotlinx.serialization)
domain/repository/
    FilterRuleDao.kt       (queries: byAccount, byFeed, upsert, delete)
domain/service/
    ArticleFilterEngine.kt (pure evaluation logic, no Android deps)
domain/repository/ (use-case style, following existing naming)
    ApplyFeedFiltersUseCase.kt  — hook invoked from sync pipeline
infrastructure/di/
    (register DAO in DatabaseModule.kt — one line)
```

`ArticleFilterEngine` is a pure Kotlin object:

```kotlin
object ArticleFilterEngine {
    fun shouldKeep(article: ArticleSnapshot, rules: List<CompiledFilterRule>): Boolean
}
```

with `ArticleSnapshot(title, author, link, content)` extracted from `Article`
so the engine is unit-testable on the JVM without Android.

The DAO exposes a combined query used by the sync hook so the use-case needs a
single call per feed batch:

```sql
SELECT * FROM filter_rule
WHERE accountId = :accountId AND isEnabled = 1
  AND (feedId IS NULL OR feedId = :feedId)
ORDER BY createdAt ASC   -- global rules first, then feed-specific
```

(`FilterRuleDao.findEnabledForAccountAndFeed`.)

### 2.4 Sync integration (minimal diff)

Single insertion point in `LocalRssService.syncFeed()`:

```kotlin
private suspend fun syncFeed(feed: Feed, preDate: Date): FeedWithArticle {
    val articles = rssHelper.queryRssXml(feed, "", preDate)
    val articles = applyFeedFilters(feed, articles)   // ← new, ~3 lines
    ...
}
```

`ApplyFeedFiltersUseCase` loads enabled rules once per sync batch (global +
feed), compiles regexes once, and filters the list. Remote providers
(GoogleReader/Fever services) get the same hook in their entry-ingest path.

### 2.5 UI layer (new package, follows existing patterns)

```
ui/page/settings/filter/
    FiltersPage.kt         — list of rules (global + per-feed sections)
    FilterRuleEditPage.kt  — create/edit a rule
    FilterRuleViewModel.kt
    components/
        ConditionRow.kt        — field dropdown + match-type chip + pattern field
        ExpressionEditor.kt    — AND/OR/NOT grouping UI
        RegexHelpDialog.kt     — cheatsheet + live test box ("try your pattern")
ui/page/home/feeds/drawer/feed/
    (+ one row in FeedOptionDrawer: "Filter rules…" → FiltersPage(feedId))
```

**Compact (<600dp)**: `FiltersPage` is a normal full-screen page pushed onto the
existing nav3 `NavDisplay` stack; editing happens on a second screen. Matches
how `SettingsPage` sub-pages work today.

**Expanded (≥840dp, e.g. desktop 1080p/4K)**: reuse `NavigableListDetailPaneScaffold`
exactly like `ArticleListReadingPage`: rule list on the left pane, rule editor in
the detail pane, so power users can iterate quickly. No new adaptive machinery is
introduced — we consume the same `scaffoldDirective`/navigator plumbing AppEntry
already provides.

**Rule editor UX** (progressive disclosure, Inoreader/Gmail-inspired):

1. Name + Enable switch + Action segmented buttons (Block / Allow-only).
2. Simple mode: flat list of condition rows, each row =
   `[Field ▾] [contains ▾] [____] [×]`, "+ Add condition" button.
   Conditions in simple mode are OR'd within Block rules, AND'd within Allow
   rules (documented in-page) — matches what most users mean.
3. Advanced mode toggle: reveals the grouping editor (AND/OR/NONE-OF groups,
   max depth 3) rendered as indented card groups with a group-operator chip.
   `NONE_OF` is *group negation* (NOR): the editor must either restrict it to a
   single child or label multi-child groups explicitly as "none of" — plain
   "NOT" labeling would mislead users.
4. Live validation: regex compile errors shown inline under the pattern field;
   a "Test" affordance lets the user paste a sample title/content and see
   match/no-match instantly (reuses `ArticleFilterEngine`).
5. Everything uses existing components: `RYTextField`, `RYSelectionChip`,
   `RYDialog`, `SubTitle`, `Tips`.

Strings go in `values/strings.xml` (English) only — repo lint disables
MissingTranslation, and maintainers' Weblate flow picks translations later.

### 2.6 Testing

- JVM unit tests (existing `app/src/test` convention: JUnit 4 + Mockito runner):
  - `FilterExpressionSerializationTest` — JSON round-trip, simple-mode
    OR-for-BLOCK / AND-for-ALLOW semantics, corrupt-payload degradation to null,
    unknown-field forward compatibility, unknown-enum safe degradation.
    *(Landed with commit 1.)*
  - `ArticleFilterEngineTest` — boolean semantics, precedence (block > allow),
    word-boundary vs contains, case sensitivity, invalid-regex resilience,
    deep-group evaluation. *(Commit 2.)*
- Robolectric-free; engine is pure Kotlin so tests run fast in CI.

### 2.7 Maintainability / merge-friendliness strategy

The constraint "easy to maintain even if upstream doesn't merge the PR" drives:

1. **100% additive files.** All new logic lives in new packages
   (`domain/model/filter`, `domain/repository/FilterRuleDao`,
   `domain/service/ArticleFilterEngine`, `ui/page/settings/filter`).
2. **Minimal touch points** on existing files (expected total <60 changed lines
   outside new files):
   - `AndroidDatabase.kt`: version bump + entity + auto-migration (~4 lines)
   - `DatabaseModule.kt`: DAO binding (~1 line)
   - `LocalRssService.kt` (+ remote services): filter hook call (~3 lines each)
   - `FeedOptionDrawer.kt`: one menu row (~5 lines)
   - `SettingsPage.kt` / nav graph: route registration (~10 lines)
   - `strings.xml`: appended strings
3. **No changes** to existing entities (`Feed`, `Article`), existing queries, or
   preference system — so upstream refactors elsewhere never conflict.
4. Feature flag not needed: with zero rules configured the hook is a no-op
   (`rules.isEmpty() → passthrough`), zero cost and zero behavior change.
5. Rebase recipe documented in §5 if upstream moves.

---

## 3. Implementation steps (PR-sized commits)

| # | Commit | Contents |
|---|---|---|
| 1 | `feat(filter): data model & storage` | enums, `FilterExpression` + serializer, `FilterRule` entity, `FilterRuleDao` (incl. combined `findEnabledForAccountAndFeed` query), DB v8 migration, schema export, DI wiring, serialization round-trip tests ✅ *done* |
| 2 | `feat(filter): evaluation engine` | `ArticleFilterEngine` + `ArticleSnapshot`, unit tests ✅ *done* |
| 3 | `feat(filter): sync pipeline hook` | `ApplyFeedFiltersUseCase`, wire into Local/GoogleReader/Fever ingest paths ✅ *done* |
| 4 | `feat(filter): rule management UI` | `FiltersPage`, `FilterRuleViewModel`, compact navigation ✅ *done* |
| 5 | `feat(filter): rule editor UI` | condition rows, expression editor, regex validation/test dialog ✅ *done* |
| 6 | `feat(filter): large-screen layout` | list-detail scaffold for expanded widths ✅ *done* |
| 7 | `feat(filter): feed drawer entry point` | "Filter rules…" row in `FeedOptionDrawer`; docs ✅ *done* |

Each commit builds and passes tests independently → easy partial review/upstreaming.

## 4. Risks & mitigations

- **ReDoS from user regex**: cap pattern length (500 chars), use
  `Regex(..., RegexOption.IGNORE_CASE)` only when requested, and evaluate on
  `Dispatchers.Default` with a timeout wrapper (`withTimeout(250ms)` per article
  batch, falling back to "keep" on timeout).
- **Content matching cost**: CONTENT matching strips HTML once per article via
  the existing Jsoup pipeline (`shortDescription` is already plain text — prefer
  it; fall back to raw only when user opts into "full content" scope).
- **Sync accounts (Fever/GReader)**: server-side unread counts may differ from
  locally filtered view; documented limitation, same as Miniflux's local-only
  keep/block for remote accounts.
- **DB migration**: auto-migration handles added table; destructive-recreation
  fallback unnecessary since no existing columns change.

## 5. Maintenance if unmerged (fork rebase recipe)

```bash
git fetch upstream
git checkout feature/advanced-filters
git rebase upstream/main          # conflicts limited to the ≤6 touch-point files
./gradlew :app:testGithubDebugUnitTest
```

Because all feature code is in isolated packages, upstream renames/moves of
unrelated modules cannot conflict; the only realistic conflicts are import-line
adjacency in the six touch files listed in §2.7.

## 6. Follow-up hardening (post-implementation review)

1. **Block-wins is order-independent**: `ArticleFilterEngine.shouldKeep`
   evaluates all BLOCK rules before ALLOW rules; regression test covers both
   orders.
2. **Regexes pre-compiled per batch** into `CompiledFilterRule.regexes`; the
   per-article timeout is documented as best-effort (blocking Java regex is
   not cancellable) with the 500-char cap as the primary ReDoS mitigation.
3. **Google Reader**: filtered-out items are stored as read so they are not
   re-fetched every sync, and are excluded from unread reconciliation.
4. **Fever**: mixed-feed batches filter via `filterMixedFeeds`, preserving
   item order.
5. **Rule edits preserve** `isEnabled`/`createdAt`; feed/account deletion
   cascades to `filter_rule` via `deleteByFeed`/`deleteByAccount`.
6. **Word matching uses ASCII-only boundaries** (CJK characters act as
   separators, so Latin words match inside CJK text); non-ASCII patterns
   degrade to substring matching since continuous scripts have no word
   boundaries. Expressions deeper than `MAX_DEPTH` are rejected at
   deserialization.

A composite index on `(accountId, feedId, isEnabled)` was deliberately
skipped: the table holds at most dozens of user-created rows, so it would
buy nothing while forcing a DB version bump + exported-schema churn.

## 7. References
- RSSBrew — https://github.com/yinan-c/RSSbrew (custom filters: AND/OR/NOT groups,
  contains/regex match types on link/title/description)
- Miniflux filter rules — https://miniflux.app/docs/rules.html (block/keep regex,
  RE2, global→feed precedence)
- FeedFlow blocked words — https://github.com/prof18/feed-flow
  (`blocked_word` table + trigger-maintained `is_blocked` column)
- Feeder.co filters — https://feeder.co (keyword include/exclude + notifications)
- Inoreader filters & rules — https://www.inoreader.com/blog/2023/06/streamline-content-discovery-with-filters-and-rules.html
- NetNewsWire community demand for regex filters — Ranchero-Software/NetNewsWire#1864, #3332
- M3 canonical layouts / list-detail — https://developer.android.com/develop/adaptive-apps/guides/list-detail
