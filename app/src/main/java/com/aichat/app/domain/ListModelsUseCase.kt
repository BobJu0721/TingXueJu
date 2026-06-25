package com.aichat.app.domain

import com.aichat.app.data.AppSettings
import com.aichat.app.network.AiApiClient

class ListModelsUseCase(private val api: AiApiClient) {
    suspend operator fun invoke(settings: AppSettings, key: String): List<String> =
        api.listModels(settings, key)
}
