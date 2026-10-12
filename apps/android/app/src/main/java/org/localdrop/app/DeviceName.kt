package org.localdrop.app

import android.content.Context
import android.os.Build

object DeviceName {
    fun normalize(value: String): String {
        val name = value.trim()
        require(name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size <= 64 &&
            name.none { it.isISOControl() }) {
            "Device name must be 1–64 UTF-8 bytes without control characters"
        }
        return name
    }

    fun load(context: Context): String {
        var fallback = (Build.MODEL ?: "Android").ifBlank { "Android" }
        while (fallback.toByteArray(Charsets.UTF_8).size > 64) fallback = fallback.dropLast(1)
        val value = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("device_name", null) ?: fallback
        return runCatching { normalize(value) }.getOrElse { fallback }
    }

    fun save(context: Context, value: String): String {
        val name = normalize(value)
        check(context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("device_name", name).commit()) { "Could not save device name" }
        return name
    }
}
