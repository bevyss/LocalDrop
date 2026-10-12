package org.localdrop.app

import android.content.Context

object DeviceIdentity {
    fun loadOrCreate(context: Context): String {
        val preferences = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        val existing = preferences.getString("id", null)
        if (existing != null) {
            require(Regex("^[0-9a-f]{32}$").matches(existing)) { "Stored device identity is invalid" }
            return existing
        }
        val id = DiscoveryRecord.newId()
        check(preferences.edit().putString("id", id).commit()) { "Could not save device identity" }
        return id
    }
}
