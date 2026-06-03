package com.aichat.app

fun filterModels(models: List<String>, query: String): List<String> {
    val normalizedQuery = query.trim()
    return if (normalizedQuery.isEmpty()) {
        models
    } else {
        models.filter { it.contains(normalizedQuery, ignoreCase = true) }
    }
}

