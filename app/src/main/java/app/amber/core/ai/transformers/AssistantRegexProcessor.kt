package app.amber.core.ai.transformers

import app.amber.core.model.AssistantAffectScope
import app.amber.core.model.AssistantRegex
import app.amber.agent.data.model.nativebridge.RegexNativeSwitch

fun String.replaceRegexes(
    regexes: List<AssistantRegex>,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String = replaceRegexes(regexes, scope, visual) { input, patterns, replacements, jvmFallback ->
    if (RegexNativeSwitch.isEnabled()) {
        RegexNativeSwitch.applyOrNull(input, patterns, replacements, jvmFallback)
    } else {
        null
    }
}

internal fun String.replaceRegexes(
    regexes: List<AssistantRegex>,
    scope: AssistantAffectScope,
    visual: Boolean,
    nativeApply: (String, Array<String>, Array<String>, () -> String) -> String?,
): String {
    if (regexes.isEmpty()) return this

    val program = AssistantRegexProgramCache.get(regexes, scope, visual)
    if (program.isEmpty) return this

    // Phase 2 Step 3: route through RegexNativeSwitch when enabled AND no
    // rule uses JVM-only syntax. Preflight is required because the Rust
    // `regex` crate silently skips lookbehind / backref / possessive in
    // patterns (and `\$` / `$<name>` in replacements), producing different
    // output without throwing — fallback can't detect it. When the preflight
    // flags any JVM-only rule we route the whole batch to JVM to preserve
    // per-rule semantic parity. See `RegexNativeSwitch` class KDoc.
    if (!program.containsJvmOnlySyntax) {
        nativeApply(
            this,
            program.nativePatterns,
            program.nativeReplacements,
            { program.applyJvm(this) },
        )?.let { return it }
    }

    return program.applyJvm(this)
}

private object AssistantRegexProgramCache {
    private const val MAX_ENTRIES = 128
    private val lock = Any()
    private val entries = object : LinkedHashMap<AssistantRegexProgramKey, AssistantRegexProgram>(
        MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<AssistantRegexProgramKey, AssistantRegexProgram>,
        ): Boolean = size > MAX_ENTRIES
    }

    fun get(
        regexes: List<AssistantRegex>,
        scope: AssistantAffectScope,
        visual: Boolean,
    ): AssistantRegexProgram {
        val key = AssistantRegexProgramKey(
            scope = scope,
            visual = visual,
            regexes = regexes.toList(),
        )
        synchronized(lock) {
            entries[key]?.let { return it }
        }
        val program = buildProgram(regexes, scope, visual)
        synchronized(lock) {
            entries[key]?.let { return it }
            entries[key] = program
        }
        return program
    }

    private fun buildProgram(
        regexes: List<AssistantRegex>,
        scope: AssistantAffectScope,
        visual: Boolean,
    ): AssistantRegexProgram {
        val applicable = regexes.filter { regex ->
            regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)
        }
        if (applicable.isEmpty()) return AssistantRegexProgram.Empty
        val compiledRules = applicable.map { rule ->
            CompiledAssistantRegex(
                regex = runCatching { Regex(rule.findRegex) }
                    .onFailure { it.printStackTrace() }
                    .getOrNull(),
                replacement = rule.replaceString,
            )
        }
        return AssistantRegexProgram(
            compiledRules = compiledRules,
            nativePatterns = Array(applicable.size) { applicable[it].findRegex },
            nativeReplacements = Array(applicable.size) { applicable[it].replaceString },
            containsJvmOnlySyntax = containsJvmOnlyRegexSyntax(applicable) ||
                compiledRules.any { rule ->
                    val regex = rule.regex
                    regex == null || hasNonPortableNumericReference(regex, rule.replacement)
                },
        )
    }
}

private data class AssistantRegexProgramKey(
    val scope: AssistantAffectScope,
    val visual: Boolean,
    // Keep rule values for equality: different edits can have the same hash.
    val regexes: List<AssistantRegex>,
)

private data class AssistantRegexProgram(
    val compiledRules: List<CompiledAssistantRegex>,
    val nativePatterns: Array<String>,
    val nativeReplacements: Array<String>,
    val containsJvmOnlySyntax: Boolean,
) {
    val isEmpty: Boolean get() = compiledRules.isEmpty()

    fun applyJvm(input: String): String =
        compiledRules.fold(input) { acc, rule ->
            val regex = rule.regex ?: return@fold acc
            try {
                regex.replace(acc, rule.replacement)
            } catch (e: Exception) {
                e.printStackTrace()
                acc
            }
        }

    companion object {
        val Empty = AssistantRegexProgram(
            compiledRules = emptyList(),
            nativePatterns = emptyArray(),
            nativeReplacements = emptyArray(),
            containsJvmOnlySyntax = false,
        )
    }
}

private data class CompiledAssistantRegex(
    val regex: Regex?,
    val replacement: String,
)

/**
 * Detect any rule that uses Java `Pattern` syntax the Rust `regex` crate
 * doesn't support (and would silently skip, producing a divergent output).
 *
 * Detects, in **pattern** strings:
 * - lookbehind: `(?<=...)`, `(?<!...)`
 * - lookahead:  `(?=...)`, `(?!...)`
 * - atomic group: `(?>...)`
 * - backreference: `\1` through `\9` (only `\` followed by single digit;
 *   `\10` etc. are rare and we accept under-coverage there)
 * - possessive quantifier: `?+`, `*+`, `++`
 *
 * Detects, in **replacement** strings:
 * - backslash escaping (Rust treats replacement backslashes literally)
 * - `$<...>` (not a valid Java replacement reference)
 * - an unbraced numeric reference followed by identifier text, e.g. `$1bar`
 *
 * Numeric references beyond the compiled capture count are checked separately:
 * Java rejects or shortens them, while Rust can expand them to empty text.
 *
 * Pure prefilter — no compilation, no DFA, no allocations beyond the
 * `Regex.containsMatchIn` internal state. Safe to run per-message.
 */
private fun containsJvmOnlyRegexSyntax(rules: List<AssistantRegex>): Boolean {
    return rules.any { rule ->
        JVM_ONLY_PATTERN_SYNTAX.containsMatchIn(rule.findRegex) ||
            JVM_ONLY_REPLACEMENT_SYNTAX.containsMatchIn(rule.replaceString)
    }
}

private val JVM_ONLY_PATTERN_SYNTAX = Regex(
    """\(\?<[=!]|\(\?[=!]|\(\?>|\\[1-9]|[?*+]\+"""
)
private val JVM_ONLY_REPLACEMENT_SYNTAX = Regex(
    """\\|\$<|\$[0-9]+[A-Za-z_]"""
)

private val NUMERIC_REPLACEMENT_REFERENCE = Regex("""\$([0-9]+)""")

private fun hasNonPortableNumericReference(regex: Regex, replacement: String): Boolean {
    val groupCount = regex.toPattern().matcher("").groupCount()
    return NUMERIC_REPLACEMENT_REFERENCE.findAll(replacement).any { reference ->
        val group = reference.groupValues[1].toIntOrNull()
        group == null || group > groupCount
    }
}
