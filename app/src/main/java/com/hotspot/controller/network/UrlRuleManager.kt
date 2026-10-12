package com.hotspot.controller.network

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hotspot.controller.model.UrlRule

class UrlRuleManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("url_rules_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val rulesList = mutableListOf<UrlRule>()
    private val listeners = mutableListOf<() -> Unit>()

    init {
        loadRules()
    }

    private fun loadRules() {
        val json = prefs.getString("rules_json", null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<UrlRule>>() {}.type
                val loaded: List<UrlRule> = gson.fromJson(json, type)
                rulesList.clear()
                rulesList.addAll(loaded)
            } catch (_: Exception) {
                initDefaultRules()
            }
        } else {
            initDefaultRules()
        }
    }

    private fun initDefaultRules() {
        rulesList.clear()
        // Popular categories pre-configured
        rulesList.add(UrlRule(domainPattern = "tiktok.com", category = "Social Media", isEnabled = true, description = "TikTok App & Web"))
        rulesList.add(UrlRule(domainPattern = "facebook.com", category = "Social Media", isEnabled = false, description = "Facebook"))
        rulesList.add(UrlRule(domainPattern = "instagram.com", category = "Social Media", isEnabled = false, description = "Instagram"))
        rulesList.add(UrlRule(domainPattern = "x.com", category = "Social Media", isEnabled = false, description = "Twitter / X"))
        rulesList.add(UrlRule(domainPattern = "youtube.com", category = "Streaming", isEnabled = false, description = "YouTube Streaming"))
        rulesList.add(UrlRule(domainPattern = "netflix.com", category = "Streaming", isEnabled = false, description = "Netflix"))
        rulesList.add(UrlRule(domainPattern = "pornhub.com", category = "Adult Content", isEnabled = true, description = "Adult Content"))
        rulesList.add(UrlRule(domainPattern = "xvideos.com", category = "Adult Content", isEnabled = true, description = "Adult Content"))
        saveRules()
    }

    @Synchronized
    fun saveRules() {
        val json = gson.toJson(rulesList)
        prefs.edit().putString("rules_json", json).apply()
        notifyListeners()
    }

    @Synchronized
    fun getRules(): List<UrlRule> {
        return ArrayList(rulesList)
    }

    @Synchronized
    fun addRule(pattern: String, category: String = "Custom"): Boolean {
        val clean = pattern.trim().lowercase().removePrefix("http://").removePrefix("https://").substringBefore("/")
        if (clean.isEmpty()) return false

        // Check duplicate
        if (rulesList.any { it.domainPattern.equals(clean, ignoreCase = true) }) {
            return false
        }

        rulesList.add(0, UrlRule(domainPattern = clean, category = category, isEnabled = true))
        saveRules()
        return true
    }

    @Synchronized
    fun removeRule(id: String) {
        rulesList.removeAll { it.id == id }
        saveRules()
    }

    @Synchronized
    fun toggleRule(id: String, enabled: Boolean) {
        val rule = rulesList.find { it.id == id }
        if (rule != null) {
            rule.isEnabled = enabled
            saveRules()
        }
    }

    @Synchronized
    fun toggleCategory(category: String, enabled: Boolean) {
        for (r in rulesList) {
            if (r.category.equals(category, ignoreCase = true)) {
                r.isEnabled = enabled
            }
        }
        saveRules()
    }

    /**
     * Checks whether an incoming host is blocked by any enabled rule.
     * Returns Pair(isBlocked, ruleMatchedPattern).
     */
    @Synchronized
    fun isHostBlocked(host: String): Pair<Boolean, String?> {
        for (rule in rulesList) {
            if (rule.isEnabled && rule.matches(host)) {
                return Pair(true, rule.domainPattern)
            }
        }
        return Pair(false, null)
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }
}
