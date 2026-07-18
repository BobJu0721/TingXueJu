package com.aichat.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aichat.app.data.AppDatabase
import com.aichat.app.data.ConversationRepository
import com.aichat.app.data.ProfileRepository
import com.aichat.app.data.SecretStore
import com.aichat.app.data.SettingsRepository
import com.aichat.app.data.WorldInfoRepository
import com.aichat.app.domain.ListModelsUseCase
import com.aichat.app.domain.OrganizeProfileUseCase
import com.aichat.app.domain.OrganizeWorldSetUseCase
import com.aichat.app.domain.SaveImportedWorldSetUseCase
import com.aichat.app.domain.StreamConversationUseCase
import com.aichat.app.domain.SummarizeConversationUseCase
import com.aichat.app.network.AiApiClient

class AIChatApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    private val dao = AppDatabase.get(appContext).chatDao()

    val conversationRepository = ConversationRepository(dao)
    val profileRepository = ProfileRepository(dao)
    val worldInfoRepository = WorldInfoRepository(dao)
    val settingsRepository = SettingsRepository(appContext)
    val secretStore by lazy { SecretStore(appContext) }
    val api by lazy { AiApiClient() }
    val listModelsUseCase by lazy { ListModelsUseCase(api) }
    val organizeProfileUseCase by lazy { OrganizeProfileUseCase(api) }
    val organizeWorldSetUseCase by lazy { OrganizeWorldSetUseCase(api) }
    val saveImportedWorldSetUseCase by lazy { SaveImportedWorldSetUseCase(worldInfoRepository) }
    val streamConversationUseCase by lazy { StreamConversationUseCase(conversationRepository, profileRepository, worldInfoRepository, api) }
    val summarizeConversationUseCase by lazy { SummarizeConversationUseCase(conversationRepository, api) }
}

class AppViewModelFactory(private val appContainer: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ChatViewModel::class.java) -> ChatViewModel(appContainer)
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(appContainer)
        modelClass.isAssignableFrom(ProfilesViewModel::class.java) -> ProfilesViewModel(appContainer)
        modelClass.isAssignableFrom(WorldSetsViewModel::class.java) -> WorldSetsViewModel(appContainer)
        modelClass.isAssignableFrom(NewChatViewModel::class.java) -> NewChatViewModel(appContainer)
        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    } as T
}
