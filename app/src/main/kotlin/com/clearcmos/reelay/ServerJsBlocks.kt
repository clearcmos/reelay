package com.clearcmos.reelay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The Relay payloads Meta server-renders into a page.
 *
 * Both instagram.com and facebook.com embed their preloaded GraphQL results in
 * `<script type="application/json" data-sjs>` blocks, so both parsers read the page the
 * same way: filter the blocks by a marker key, then walk the JSON. The Relay wrapper keys
 * around the payload are generated names that change between deploys and are never matched
 * on. WebView serialises the boolean attribute as `data-sjs=""`, curl leaves it bare, and
 * Facebook's own scripts add `data-processed="1"`; the pattern accepts all three.
 */
internal object ServerJsBlocks {
    private val BLOCK = Regex("""<script\b[^>]*\bdata-sjs\b[^>]*>(\{.*?\})</script>""", RegexOption.DOT_MATCHES_ALL)
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** Every parseable block of [html] whose text contains [marker], in document order. */
    fun containing(html: String, marker: String): Sequence<JsonElement> = BLOCK
        .findAll(html)
        .map { it.groupValues[1] }
        .filter { marker in it }
        .mapNotNull { runCatching { json.parseToJsonElement(it) }.getOrNull() }
}
