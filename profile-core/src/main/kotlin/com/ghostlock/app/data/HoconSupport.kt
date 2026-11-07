package com.ghostlock.app.data

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigSyntax

/**
 * HOCON loader for every configuration source. Documents are parsed with the
 * HOCON grammar (comments, `${variables}`, trailing commas; file includes are
 * expanded by the profile compiler before parsing) straight into the value
 * model (LinkedHashMap / ArrayList / scalars).
 */
object HoconSupport {
    private val parseOptions = ConfigParseOptions.defaults()
        .setSyntax(ConfigSyntax.CONF)
        .setAllowMissing(false)

    /**
     * Parses an object document, or an array document wrapped so HOCON sees a
     * value (the offsets store keeps its top-level array shape).
     */
    fun parseValue(text: String): Any? {
        val isArray = text.trimStart().startsWith("[")
        val source = if (isArray) "value = $text" else text
        val config = runCatching {
            ConfigFactory.parseString(source, parseOptions).resolve()
        }.getOrNull() ?: return null
        val unwrapped = config.root().unwrapped()
        val value = if (isArray) unwrapped["value"] else unwrapped
        return normalize(value)
    }

    private fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) -> put(key.toString(), normalize(item)) }
        }

        is List<*> -> arrayListOf<Any?>().apply {
            value.forEach { item -> add(normalize(item)) }
        }

        else -> value
    }
}
