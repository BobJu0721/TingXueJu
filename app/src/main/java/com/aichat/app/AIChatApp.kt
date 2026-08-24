package com.aichat.app

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.aichat.app.data.AppLanguage
import com.aichat.app.data.AppSettings
import com.aichat.app.data.ProfileType
import com.aichat.app.data.Provider
import com.aichat.app.ui.*
import com.aichat.app.ui.theme.iosColorScheme
import kotlinx.coroutines.launch

@Composable
private fun HalfProgressBackBridge(
    enabled: Boolean,
    source: OnBackPressedDispatcher?,
    target: OnBackPressedDispatcher,
) {
    if (!enabled || source == null) return

    DisposableEffect(source, target) {
        val callback = object : OnBackPressedCallback(true) {
            private fun half(event: BackEventCompat) = BackEventCompat(
                event.touchX,
                event.touchY,
                event.progress * 0.5f,
                event.swipeEdge,
            )

            override fun handleOnBackStarted(backEvent: BackEventCompat) =
                target.dispatchOnBackStarted(half(backEvent))

            override fun handleOnBackProgressed(backEvent: BackEventCompat) =
                target.dispatchOnBackProgressed(half(backEvent))

            override fun handleOnBackCancelled() = target.dispatchOnBackCancelled()

            override fun handleOnBackPressed() {
                isEnabled = false
                try {
                    target.onBackPressed()
                } finally {
                    isEnabled = true
                }
            }
        }
        source.addCallback(callback)
        onDispose(callback::remove)
    }
}

internal val DOCUMENT_TYPES = arrayOf(
    "text/plain",
    "application/json",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/octet-stream",
)

private object AppRoute {
    const val HOME = "home"
    const val CHAT = "chat"
    const val API_SETTINGS = "api_settings"
    const val API_SETTINGS_LIST = "api_settings/list"
    const val API_SETTINGS_BUILT_IN = "api_settings/built_in/{provider}"
    const val API_SETTINGS_CUSTOM = "api_settings/custom/{id}"
    const val MODELS = "models"
    const val PROFILE_EDIT = "profile_edit"
    const val WORLD_SETS = "world_sets"
    const val WORLD_SET_EDIT = "world_set_edit"
    const val NEW_CHAT = "new_chat"
    const val CHAT_INFO = "chat_info"

    fun apiSettingsBuiltIn(provider: Provider) = "api_settings/built_in/${provider.name}"
    fun apiSettingsCustom(id: String) = "api_settings/custom/$id"
}
@Composable
fun AIChatApp(viewModelFactory: ViewModelProvider.Factory) {
    val chatViewModel: ChatViewModel = composeViewModel(factory = viewModelFactory)
    val settingsViewModel: SettingsViewModel = composeViewModel(factory = viewModelFactory)
    val profilesViewModel: ProfilesViewModel = composeViewModel(factory = viewModelFactory)
    val worldSetsViewModel: WorldSetsViewModel = composeViewModel(factory = viewModelFactory)
    val newChatViewModel: NewChatViewModel = composeViewModel(factory = viewModelFactory)
    val settingsUiState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val settings = settingsUiState.settings
    val chatError by chatViewModel.error.collectAsStateWithLifecycle()
    val selectedConversation by chatViewModel.selectedConversation.collectAsStateWithLifecycle()
    val settingsError = settingsUiState.error
    val profilesError by profilesViewModel.error.collectAsStateWithLifecycle()
    val worldSetsError by worldSetsViewModel.error.collectAsStateWithLifecycle()
    val showUnsafeWarning by chatViewModel.showUnsafeHttpWarning.collectAsStateWithLifecycle()
    val profilePendingImport by profilesViewModel.pendingImport.collectAsStateWithLifecycle()
    val worldSetPendingImport by worldSetsViewModel.pendingImport.collectAsStateWithLifecycle()
    val isProfileImporting by profilesViewModel.isImporting.collectAsStateWithLifecycle()
    val isWorldSetImporting by worldSetsViewModel.isImporting.collectAsStateWithLifecycle()
    val language = settings.language
    val navController = rememberNavController()
    val parentBackDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasBackTarget by remember { mutableStateOf(false) }
    val navBackDispatcher = remember(parentBackDispatcher) {
        OnBackPressedDispatcher(
            fallbackOnBackPressed = { parentBackDispatcher?.onBackPressed() },
            onHasEnabledCallbacksChanged = { hasBackTarget = it },
        )
    }
    val navBackDispatcherOwner = remember(lifecycleOwner, navBackDispatcher) {
        object : OnBackPressedDispatcherOwner {
            override val lifecycle = lifecycleOwner.lifecycle
            override val onBackPressedDispatcher = navBackDispatcher
        }
    }
    var selectedRoot by remember { mutableStateOf(Screen.CONVERSATIONS) }
    fun navigateTo(target: Screen) {
        when (target) {
            Screen.CONVERSATIONS, Screen.CHARACTERS, Screen.LIBRARY, Screen.SETTINGS -> {
                selectedRoot = target
                navController.navigate(AppRoute.HOME) {
                    popUpTo(AppRoute.HOME)
                    launchSingleTop = true
                }
            }
            Screen.CHAT -> {
                if (!navController.popBackStack(AppRoute.CHAT, inclusive = false)) {
                    navController.navigate(AppRoute.CHAT) {
                        popUpTo(AppRoute.HOME)
                        launchSingleTop = true
                    }
                }
            }
            Screen.MODELS -> {
                settingsViewModel.refreshModels()
                navController.navigate(AppRoute.MODELS) { launchSingleTop = true }
            }
            Screen.API_SETTINGS -> navController.navigate(AppRoute.API_SETTINGS) { launchSingleTop = true }
            Screen.PROFILE_EDIT -> navController.navigate(AppRoute.PROFILE_EDIT) { launchSingleTop = true }
            Screen.WORLD_SETS -> navController.navigate(AppRoute.WORLD_SETS) { launchSingleTop = true }
            Screen.WORLD_SET_EDIT -> navController.navigate(AppRoute.WORLD_SET_EDIT) { launchSingleTop = true }
            Screen.NEW_CHAT -> navController.navigate(AppRoute.NEW_CHAT) { launchSingleTop = true }
            Screen.CHAT_INFO -> navController.navigate(AppRoute.CHAT_INFO) { launchSingleTop = true }
        }
    }
    LaunchedEffect(settingsViewModel) {
        settingsViewModel.navigationEvents.collect { target ->
            navigateTo(target)
        }
    }
    LaunchedEffect(profilesViewModel) {
        profilesViewModel.navigationEvents.collect { event ->
            when (event) {
                ProfilesNavigation.Characters -> navigateTo(Screen.CHARACTERS)
                ProfilesNavigation.Library -> navigateTo(Screen.LIBRARY)
                ProfilesNavigation.ProfileEdit -> navigateTo(Screen.PROFILE_EDIT)
                is ProfilesNavigation.NewChat -> newChatViewModel.beginNewChat(event.characterId)
            }
        }
    }
    LaunchedEffect(newChatViewModel) {
        newChatViewModel.navigationEvents.collect { event ->
            when (event) {
                NewChatNavigation.NewChat -> navigateTo(Screen.NEW_CHAT)
                is NewChatNavigation.Chat -> chatViewModel.selectConversation(event.conversationId)
            }
        }
    }
    LaunchedEffect(chatViewModel) {
        chatViewModel.navigationEvents.collect { target ->
            when (target) {
                Screen.NEW_CHAT -> newChatViewModel.beginNewChat()
                else -> navigateTo(target)
            }
        }
    }
    LaunchedEffect(worldSetsViewModel) {
        worldSetsViewModel.navigationEvents.collect { event ->
            when (event) {
                WorldSetsNavigation.Library -> navigateTo(Screen.LIBRARY)
                WorldSetsNavigation.WorldSets -> navigateTo(Screen.WORLD_SETS)
                WorldSetsNavigation.WorldSetEdit -> navigateTo(Screen.WORLD_SET_EDIT)
            }
        }
    }

    val darkTheme = isSystemInDarkTheme()
    val colors = remember(darkTheme) { iosColorScheme(darkTheme) }
    CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides navBackDispatcherOwner) {
    MaterialTheme(colorScheme = colors) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = AppRoute.HOME,
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = {
                    slideOutHorizontally(animationSpec = tween(easing = LinearEasing)) { it }
                },
            ) {
                composable(AppRoute.HOME) {
                    RootPager(
                        chatViewModel,
                        settingsViewModel,
                        profilesViewModel,
                        newChatViewModel,
                        selectedRoot,
                        settings,
                        language,
                        { navigateTo(Screen.WORLD_SETS) },
                        { selectedRoot = it },
                    )
                }
                composable(AppRoute.CHAT) { ChatScreen(chatViewModel, language) { navController.navigateUp() } }
                navigation(startDestination = AppRoute.API_SETTINGS_LIST, route = AppRoute.API_SETTINGS) {
                    composable(AppRoute.API_SETTINGS_LIST) {
                        ApiSettingsScreen(
                            settingsViewModel,
                            language,
                            onBack = { navController.navigateUp() },
                            onEditBuiltIn = { navController.navigate(AppRoute.apiSettingsBuiltIn(it)) },
                            onEditCustom = { navController.navigate(AppRoute.apiSettingsCustom(it)) },
                        )
                    }
                    composable(AppRoute.API_SETTINGS_BUILT_IN) { entry ->
                        val provider = entry.arguments?.getString("provider")
                            ?.let { name -> Provider.entries.firstOrNull { it.name == name } }
                            ?: return@composable
                        BuiltInEndpointScreen(settingsViewModel, provider, language) { navController.navigateUp() }
                    }
                    composable(AppRoute.API_SETTINGS_CUSTOM) { entry ->
                        val id = entry.arguments?.getString("id") ?: return@composable
                        CustomEndpointScreen(settingsViewModel, id, language) { navController.navigateUp() }
                    }
                }
                composable(AppRoute.MODELS) {
                    ModelsScreen(
                        viewModel = settingsViewModel,
                        selected = settings.model,
                        reasoningMode = selectedConversation?.reasoningMode,
                        language = language,
                        onReasoningModeChange = chatViewModel::updateConversationReasoningMode,
                        onBack = { navController.navigateUp() },
                    )
                }
                composable(AppRoute.PROFILE_EDIT) { ProfileEditScreen(profilesViewModel, language) { navController.navigateUp() } }
                composable(AppRoute.WORLD_SETS) { WorldSetsScreen(worldSetsViewModel, { navController.navigateUp() }, language) }
                composable(AppRoute.WORLD_SET_EDIT) { WorldSetEditScreen(worldSetsViewModel, language) { navController.navigateUp() } }
                composable(AppRoute.NEW_CHAT) { NewChatScreen(newChatViewModel, { navController.navigateUp() }, language) }
                composable(AppRoute.CHAT_INFO) { ChatInfoScreen(chatViewModel, language) { navController.navigateUp() } }
            }
            HalfProgressBackBridge(
                enabled = hasBackTarget,
                source = parentBackDispatcher,
                target = navBackDispatcher,
            )
            if (isProfileImporting || isWorldSetImporting) {
                LoadingOverlay(language.pick("AI 整理中…", "AI 整理中…"))
            }
        }
        (chatError ?: settingsError ?: profilesError ?: worldSetsError)?.let { current ->
            ErrorDialog(
                current,
                language,
                {
                    when {
                        chatError != null -> chatViewModel.clearError()
                        settingsError != null -> settingsViewModel.clearError()
                        profilesError != null -> profilesViewModel.clearError()
                        else -> worldSetsViewModel.clearError()
                    }
                },
                { navigateTo(Screen.SETTINGS) },
                chatViewModel::trimOldestContextAndRetry,
            ) {
                when {
                    chatError != null -> chatViewModel.clearError()
                    settingsError != null -> settingsViewModel.clearError()
                    profilesError != null -> profilesViewModel.clearError()
                    else -> worldSetsViewModel.clearError()
                }
                newChatViewModel.beginNewChat()
            }
        }
        (profilePendingImport ?: worldSetPendingImport)?.let { pending ->
            AlertDialog(
                onDismissRequest = {
                    when {
                        profilePendingImport != null -> profilesViewModel.dismissPendingImport()
                        else -> worldSetsViewModel.dismissPendingImport()
                    }
                },
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(language.pick("\u78ba\u8a8d\u532f\u5165", "\u786e\u8ba4\u5bfc\u5165")) },
                text = {
                    Text(language.pick(
                        "\u5c07\u532f\u5165 ${pending.document.name}\uff0c\u5171 ${pending.document.text.length} \u5b57\u3002\u6703\u4f7f\u7528 ${settings.provider.label} / ${settings.model} \u9032\u884c\u6574\u7406\uff0c\u9810\u4f30 ${pending.estimatedCalls} \u6b21 API \u547c\u53eb\u3002",
                        "\u5c06\u5bfc\u5165 ${pending.document.name}\uff0c\u5171 ${pending.document.text.length} \u5b57\u3002\u4f1a\u4f7f\u7528 ${settings.provider.label} / ${settings.model} \u8fdb\u884c\u6574\u7406\uff0c\u9884\u4f30 ${pending.estimatedCalls} \u6b21 API \u8c03\u7528\u3002",
                    ))
                },
                confirmButton = {
                    TextButton(onClick = { if (profilePendingImport != null) profilesViewModel.confirmPendingImport() else worldSetsViewModel.confirmPendingImport() }) {
                        Text(language.pick("\u78ba\u8a8d", "\u786e\u8ba4"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { if (profilePendingImport != null) profilesViewModel.dismissPendingImport() else worldSetsViewModel.dismissPendingImport() }) {
                        Text(language.pick("\u53d6\u6d88", "\u53d6\u6d88"))
                    }
                },
            )
        }
        if (showUnsafeWarning) {
            AlertDialog(
                onDismissRequest = chatViewModel::dismissUnsafeHttp,
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(language.pick("HTTP \u9023\u7dda\u8b66\u544a", "HTTP \u8fde\u63a5\u8b66\u544a")) },
                text = { Text(language.pick("\u4f60\u6b63\u5728\u4f7f\u7528 HTTP\uff0cAPI Key \u53ef\u80fd\u88ab\u7db2\u8def\u4e2d\u9593\u4eba\u64f7\u53d6\u3002", "\u4f60\u6b63\u5728\u4f7f\u7528 HTTP\uff0cAPI Key \u53ef\u80fd\u88ab\u7f51\u7edc\u4e2d\u95f4\u4eba\u622a\u53d6\u3002")) },
                confirmButton = { TextButton(onClick = chatViewModel::confirmUnsafeHttp) { Text(language.pick("\u7e7c\u7e8c\u4f7f\u7528", "\u7ee7\u7eed\u4f7f\u7528")) } },
                dismissButton = { TextButton(onClick = chatViewModel::dismissUnsafeHttp) { Text(language.pick("\u53d6\u6d88", "\u53d6\u6d88")) } },
            )
        }
    }
    }
}
@Composable
private fun RootPager(
    chatViewModel: ChatViewModel,
    settingsViewModel: SettingsViewModel,
    profilesViewModel: ProfilesViewModel,
    newChatViewModel: NewChatViewModel,
    selected: Screen,
    settings: AppSettings,
    language: AppLanguage,
    onOpenWorldSets: () -> Unit,
    onRootSelected: (Screen) -> Unit,
) {
    val roots = listOf(Screen.CONVERSATIONS, Screen.CHARACTERS, Screen.LIBRARY, Screen.SETTINGS)
    val selectedPage = roots.indexOf(selected).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = selectedPage) { roots.size }
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selected)
    var programmaticTargetPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(selectedPage) {
        if (pagerState.currentPage != selectedPage) {
            programmaticTargetPage = selectedPage
            pagerState.animateScrollToPage(selectedPage)
            programmaticTargetPage = null
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage to programmaticTargetPage }.collect { (page, targetPage) ->
            if (targetPage != null) return@collect
            val target = roots[page]
            if (target != currentSelected) onRootSelected(target)
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            RootBottomBar(selected, language) { target ->
                val targetPage = roots.indexOf(target)
                if (targetPage < 0) return@RootBottomBar
                scope.launch {
                    if (pagerState.currentPage != targetPage) pagerState.animateScrollToPage(targetPage)
                    onRootSelected(target)
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { page ->
            when (roots[page]) {
                Screen.CONVERSATIONS -> ConversationsScreen(chatViewModel, newChatViewModel, onRootSelected, language, showBottomBar = false)
                Screen.CHARACTERS -> ProfilesScreen(profilesViewModel, onRootSelected, ProfileType.CHARACTER, language, showBottomBar = false)
                Screen.LIBRARY -> LibraryScreen(onOpenWorldSets, profilesViewModel, onRootSelected, language, showBottomBar = false)
                Screen.SETTINGS -> SettingsScreen(settingsViewModel, onRootSelected, settings, showBottomBar = false)
                else -> Unit
            }
        }
    }
}

@Composable
internal fun RootBottomBar(
    selected: Screen,
    language: AppLanguage,
    onSelect: (Screen) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 0.dp,
        color = iosBarColor(),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Row(
                modifier = Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactBottomItem(language.pick("\u5c0d\u8a71", "\u5bf9\u8bdd"), Icons.Default.Chat, selected == Screen.CONVERSATIONS) { onSelect(Screen.CONVERSATIONS) }
                CompactBottomItem(language.pick("\u89d2\u8272", "\u89d2\u8272"), Icons.Default.Person, selected == Screen.CHARACTERS) { onSelect(Screen.CHARACTERS) }
                CompactBottomItem(language.pick("\u8cc7\u6599\u5eab", "\u8d44\u6599\u5e93"), Icons.Default.Storage, selected == Screen.LIBRARY) { onSelect(Screen.LIBRARY) }
                CompactBottomItem(language.pick("\u8a2d\u5b9a", "\u8bbe\u7f6e"), Icons.Default.Settings, selected == Screen.SETTINGS) { onSelect(Screen.SETTINGS) }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
internal fun CompactTopBar(
    title: String,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(Modifier.fillMaxWidth(), shadowElevation = 0.dp, color = iosBarColor()) {
        Column {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (navigationIcon != null) navigationIcon() else Spacer(Modifier.width(8.dp))
                val titleModifier = if (onTitleClick != null) Modifier.weight(1f).clickable(onClick = onTitleClick) else Modifier.weight(1f)
                Column(titleModifier, verticalArrangement = Arrangement.Center) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

@Composable
private fun RowScope.CompactBottomItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxSize()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = color, fontSize = 13.sp, maxLines = 1)
    }
}

