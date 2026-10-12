package org.localdrop.app

import java.security.SecureRandom

data class DiscoveryRecord(val id: String, val name: String, val type: String, val port: Int,
                           val host: String? = null, val capabilities: String = "") {
    companion object {
        const val SERVICE_TYPE = "_localdrop._tcp."
        private val idPattern = Regex("^[0-9a-f]{32}$")

        fun newId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }

        fun parse(txt: Map<String, String>, port: Int): DiscoveryRecord? {
            val id = txt["id"] ?: return null
            val name = txt["name"] ?: return null
            val type = txt["type"] ?: return null
            if (txt["txtvers"] != "1" || txt["pv"] != "1" || !idPattern.matches(id) ||
                name.isBlank() || name.any { it.isISOControl() } ||
                name.toByteArray(Charsets.UTF_8).size > 64 ||
                type !in setOf("windows", "android") || txt["caps"] !in setOf("", "file1") ||
                port !in 1..65535) return null
            return DiscoveryRecord(id, name, type, port, capabilities = txt["caps"]!!)
        }
    }
}
