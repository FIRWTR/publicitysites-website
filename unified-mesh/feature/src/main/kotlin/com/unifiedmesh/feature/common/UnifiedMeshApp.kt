package com.unifiedmesh.feature.common

import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.unifiedmesh.feature.diagnostics.DiagnosticsScreen
import com.unifiedmesh.feature.map.MapScreen
import com.unifiedmesh.feature.messages.ConversationScreen
import com.unifiedmesh.feature.messages.MessagesScreen
import com.unifiedmesh.feature.nodes.NodesScreen
import com.unifiedmesh.feature.radios.RadiosScreen
import com.unifiedmesh.feature.settings.BridgeScreen
import com.unifiedmesh.feature.settings.SettingsScreen

/**
 * Material 3 theme.
 *
 * Uses the system wallpaper palette on Android 12+ and a fixed scheme elsewhere.
 * Dark mode follows the system setting, which for a field app matters: a bright
 * screen at night is a real problem, not a preference.
 */
@Composable
fun UnifiedMeshTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> darkColorScheme(
            primary = Color(0xFF7FCBFF),
            tertiary = Color(0xFFC9A7FF),
        )

        else -> lightColorScheme(
            primary = Color(0xFF1F5FBF),
            tertiary = Color(0xFF6B3FB5),
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/**
 * The app shell: bottom navigation, the app bar with the MT/MC indicators, and
 * the navigation graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedMeshApp(
    onExportDiagnostics: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Conversation to open, from a tapped message notification.
     *
     * Handled here rather than by the caller because navigation is only legal
     * once [NavHost] has set the graph. An effect declared alongside this
     * composable can run before that and throw.
     */
    deepLinkConversationId: String? = null,
    onDeepLinkHandled: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
    shellViewModel: AppShellViewModel = hiltViewModel(),
) {
    val connectionStates by shellViewModel.connectionStates.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevel = TopLevelDestination.entries.firstOrNull { it.route == currentRoute }
    val canNavigateBack = topLevel == null && currentRoute != null

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(topLevel?.label ?: titleFor(currentRoute)) },
                navigationIcon = {
                    if (canNavigateBack) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    // The two indicators are on every screen: knowing at a glance
                    // whether both radios are up is the app's most frequent question.
                    ConnectionIndicators(connectionStates)
                },
            )
        },
        bottomBar = {
            if (topLevel != null) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    // Standard bottom-nav behaviour: one entry per
                                    // tab, state preserved when switching back.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.MESSAGES.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevelDestination.MESSAGES.route) {
                MessagesScreen(
                    onOpenConversation = { navController.navigate(Routes.conversation(it)) },
                )
            }
            composable(TopLevelDestination.NODES.route) { NodesScreen() }
            composable(TopLevelDestination.MAP.route) { MapScreen() }
            composable(TopLevelDestination.RADIOS.route) { RadiosScreen() }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    onOpenBridge = { navController.navigate(Routes.BRIDGE) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                )
            }

            composable(
                route = Routes.CONVERSATION,
                arguments = listOf(navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType }),
            ) {
                ConversationScreen()
            }
            composable(Routes.BRIDGE) { BridgeScreen() }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onExport = onExportDiagnostics) }
        }

        // Declared after NavHost so the graph is set by the time effects run.
        // Navigating before that throws, which would turn tapping a notification
        // into a crash.
        LaunchedEffect(deepLinkConversationId) {
            val conversationId = deepLinkConversationId ?: return@LaunchedEffect
            navController.navigate(Routes.conversation(conversationId))
            onDeepLinkHandled()
        }
    }
}

private fun titleFor(route: String?): String = when {
    route == null -> "Unified Mesh"
    route.startsWith("conversation") -> "Conversation"
    route == Routes.BRIDGE -> "Bridge"
    route == Routes.DIAGNOSTICS -> "Diagnostics"
    else -> "Unified Mesh"
}
