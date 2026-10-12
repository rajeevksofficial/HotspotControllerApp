package com.hotspot.controller.model

import java.util.UUID

/**
 * Represents a URL or domain filter rule configured on D0.
 */
data class UrlRule(
    val id: String = UUID.randomUUID().toString(),
    val domainPattern: String,
    val category: String = "Custom",
    var isEnabled: Boolean = true,
    val description: String = ""
) {
    /**
     * Checks if the target host (e.g., "tiktok.com", "api.facebook.com") matches this rule.
     */
    fun matches(rawHost: String): Boolean {
        if (!isEnabled) return false
        val cleanHost = rawHost.trim().lowercase().substringBefore(":")
        val pattern = domainPattern.trim().lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")

        // Exact match
        if (cleanHost == pattern) return true

        // Wildcard prefix: *.example.com or example.com covers subdomains
        if (pattern.startsWith("*.")) {
            val root = pattern.removePrefix("*.")
            if (cleanHost == root || cleanHost.endsWith(".$root")) return true
        }

        // Subdomain matching: if pattern is "facebook.com", also match "m.facebook.com", "static.facebook.com"
        if (cleanHost.endsWith(".$pattern")) return true

        // General glob wildcard e.g. *xxx* or *adult*
        if (pattern.contains("*")) {
            val regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .toRegex()
            return regex.matches(cleanHost)
        }

        return false
    }
}
