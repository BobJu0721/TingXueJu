package com.aichat.app

fun filterModels(models: List<String>, query: String): List<String> {
    val normalizedQuery = query.trim()
    return if (normalizedQuery.isEmpty()) {
        models
    } else {
        models.filter { it.contains(normalizedQuery, ignoreCase = true) }
    }
}

fun manualModelCandidate(models: List<String>, query: String): String? {
    val model = query.trim()
    return model.takeIf { it.isNotEmpty() && it !in models }
}

