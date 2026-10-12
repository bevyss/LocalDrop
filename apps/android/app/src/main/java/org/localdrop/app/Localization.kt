package org.localdrop.app

import android.content.Context
import java.util.Locale

class Localization(private val context: Context) {
    private val languages = listOf("en", "tr", "de", "es", "zh", "ja")
    private val values: Map<String, List<String>> = context.assets.open("strings.tsv")
        .bufferedReader(Charsets.UTF_8).use { reader ->
            reader.lineSequence().drop(1).filter { it.isNotBlank() }
                .map { it.split('\t') }.associate { it.first() to it.drop(1) }
        }
    var language: String = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("language", null)?.takeIf { it in languages }
        ?: Locale.getDefault().language.takeIf { it in languages } ?: "en"
        private set

    fun change(language: String) {
        if (language !in languages) return
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("language", language).apply()
        this.language = language
    }

    fun text(key: String, vararg args: Any): String {
        val translations = values[key] ?: return key
        val index = languages.indexOf(language)
        val template = translations.getOrNull(index)?.takeIf { it.isNotBlank() }
            ?: translations.firstOrNull() ?: key
        return args.foldIndexed(template) { i, value, arg -> value.replace("{$i}", arg.toString()) }
    }
}
