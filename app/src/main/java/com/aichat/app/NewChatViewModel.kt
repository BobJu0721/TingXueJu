package com.aichat.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ConversationEntity
import com.aichat.app.data.ConversationWorldSetEntity
import com.aichat.app.data.MessageEntity
import com.aichat.app.data.ProfileEntity
import com.aichat.app.data.ProfileType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface NewChatNavigation {
    data object NewChat : NewChatNavigation
    data class Chat(val conversationId: String) : NewChatNavigation
}

class NewChatViewModel(appContainer: AppContainer) : ViewModel() {
    private val conversationRepository = appContainer.conversationRepository
    private val profileRepository = appContainer.profileRepository
    private val worldInfoRepository = appContainer.worldInfoRepository
    private val settingsRepository = appContainer.settingsRepository

    val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val personas = profileRepository.observeProfiles(ProfileType.PERSONA).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val worldSets = worldInfoRepository.observeWorldSets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _newChatCharacter = MutableStateFlow<ProfileEntity?>(null)
    val newChatCharacter = _newChatCharacter.asStateFlow()
    private val _newChatPersonaId = MutableStateFlow<String?>(null)
    val newChatPersonaId = _newChatPersonaId.asStateFlow()
    private val _newChatWorldSetIds = MutableStateFlow<Set<String>>(emptySet())
    val newChatWorldSetIds = _newChatWorldSetIds.asStateFlow()
    private val _newChatGreeting = MutableStateFlow("")
    val newChatGreeting = _newChatGreeting.asStateFlow()
    private val _navigationEvents = MutableSharedFlow<NewChatNavigation>()
    val navigationEvents = _navigationEvents.asSharedFlow()

    private fun text(traditional: String, simplified: String): String =
        settings.value.language.pick(traditional, simplified)

    fun beginNewChat(characterId: String? = null) {
        viewModelScope.launch {
            _newChatCharacter.value = profileRepository.getProfile(characterId)
            _newChatGreeting.value = _newChatCharacter.value?.greeting.orEmpty()
            _newChatPersonaId.value = null
            _newChatWorldSetIds.value = emptySet()
            _navigationEvents.emit(NewChatNavigation.NewChat)
        }
    }

    fun selectNewChatPersona(id: String?) {
        _newChatPersonaId.value = id
    }

    fun selectNewChatGreeting(greeting: String) {
        _newChatGreeting.value = greeting
    }

    fun toggleNewChatWorldSet(id: String) {
        _newChatWorldSetIds.value = _newChatWorldSetIds.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
    }

    fun createConfiguredConversation() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val character = _newChatCharacter.value
            val conversation = ConversationEntity(
                id = UUID.randomUUID().toString(),
                title = character?.name?.ifBlank { text("新對話", "新对话") } ?: text("新對話", "新对话"),
                createdAt = now,
                updatedAt = now,
                characterId = character?.id,
                personaId = _newChatPersonaId.value,
            )
            conversationRepository.upsertConversation(conversation)
            setConversationWorldSets(conversation.id, _newChatWorldSetIds.value)
            if (_newChatGreeting.value.isNotBlank()) {
                conversationRepository.upsertMessage(
                    MessageEntity(UUID.randomUUID().toString(), conversation.id, "assistant", _newChatGreeting.value, now + 1),
                )
            }
            _navigationEvents.emit(NewChatNavigation.Chat(conversation.id))
        }
    }

    private suspend fun setConversationWorldSets(conversationId: String, ids: Set<String>) {
        conversationRepository.replaceConversationWorldSets(
            conversationId,
            ids.map { ConversationWorldSetEntity(conversationId, it) },
        )
    }
}
