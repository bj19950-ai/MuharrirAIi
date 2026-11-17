package com.example.ui.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DocumentWorkspaceScreen
import com.example.ui.screens.NewDocumentScreen
import com.example.ui.screens.ReviewWorkspaceScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.viewmodel.MuharrirViewModel

object MuharrirRoutes {
    const val ROUTE_DASHBOARD = "dashboard"
    const val ROUTE_NEW_DOCUMENT = "new_document"
    const val ROUTE_WORKSPACE = "workspace"
    const val ROUTE_REVIEW = "review"
    const val ROUTE_SETTINGS = "settings"
}

private data class NavTabItem(
    val route: String,
    val labelAr: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

private val PrimaryTabs = listOf(
    NavTabItem(
        route = MuharrirRoutes.ROUTE_DASHBOARD,
        labelAr = "الرئيسية",
        selectedIcon = Icons.Rounded.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
        testTag = "nav_dashboard",
    ),
    NavTabItem(
        route = MuharrirRoutes.ROUTE_NEW_DOCUMENT,
        labelAr = "مستند جديد",
        selectedIcon = Icons.Rounded.AddBox,
        unselectedIcon = Icons.Outlined.AddBox,
        testTag = "nav_new_document",
    ),
    NavTabItem(
        route = MuharrirRoutes.ROUTE_WORKSPACE,
        labelAr = "مساحة العمل",
        selectedIcon = Icons.Rounded.Description,
        unselectedIcon = Icons.Outlined.Description,
        testTag = "nav_workspace",
    ),
    NavTabItem(
        route = MuharrirRoutes.ROUTE_REVIEW,
        labelAr = "المراجعة",
        selectedIcon = Icons.Rounded.RateReview,
        unselectedIcon = Icons.Outlined.RateReview,
        testTag = "nav_review",
    ),
    NavTabItem(
        route = MuharrirRoutes.ROUTE_SETTINGS,
        labelAr = "الإعدادات",
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuharrirApp(
    viewModel: MuharrirViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route ?: MuharrirRoutes.ROUTE_DASHBOARD

    val dashboardState by viewModel.dashboardState.collectAsStateWithLifecycle()
    val newDocState by viewModel.newDocState.collectAsStateWithLifecycle()
    val activeDocument by viewModel.activeDocument.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val geminiKeys by viewModel.geminiKeys.collectAsStateWithLifecycle()
    val geminiUsageStats by viewModel.geminiUsageStats.collectAsStateWithLifecycle()
    val testingKeyId by viewModel.testingKeyId.collectAsStateWithLifecycle()
    val workspacePreviewBitmap by viewModel.workspacePreviewBitmap.collectAsStateWithLifecycle()
    val workspacePreviewPage by viewModel.workspacePreviewPage.collectAsStateWithLifecycle()
    val isLoadingWorkspacePage by viewModel.isLoadingWorkspacePage.collectAsStateWithLifecycle()
    val pageThumbnails by viewModel.pageThumbnails.collectAsStateWithLifecycle()
    val workspaceNotice by viewModel.workspaceNoticeAr.collectAsStateWithLifecycle()
    val settingsNotice by viewModel.settingsNoticeAr.collectAsStateWithLifecycle()
    val ocrExecutionState by viewModel.ocrExecutionState.collectAsStateWithLifecycle()
    val geminiExecutionState by viewModel.geminiExecutionState.collectAsStateWithLifecycle()

    val navigateToRoute: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val topBarTitle = when (currentRoute) {
        MuharrirRoutes.ROUTE_DASHBOARD -> "مُحَرِّر AI — لوحة التحكم"
        MuharrirRoutes.ROUTE_NEW_DOCUMENT -> "استيراد مستند PDF"
        MuharrirRoutes.ROUTE_WORKSPACE -> "مساحة عمل المستند"
        MuharrirRoutes.ROUTE_REVIEW -> "مساحة المراجعة"
        MuharrirRoutes.ROUTE_SETTINGS -> "الإعدادات"
        else -> "مُحَرِّر AI"
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 700.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = topBarTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (currentRoute != MuharrirRoutes.ROUTE_DASHBOARD) {
                            IconButton(
                                onClick = {
                                    if (!navController.popBackStack()) {
                                        navigateToRoute(MuharrirRoutes.ROUTE_DASHBOARD)
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("topbar_back_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "العودة",
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        PrimaryTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToRoute(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.labelAr,
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.labelAr,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier.testTag(tab.testTag),
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        PrimaryTabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToRoute(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.labelAr,
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.labelAr,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                modifier = Modifier.testTag(tab.testTag),
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = MuharrirRoutes.ROUTE_DASHBOARD,
                    modifier = Modifier.weight(1f),
                ) {
                    composable(MuharrirRoutes.ROUTE_DASHBOARD) {
                        DashboardScreen(
                            state = dashboardState,
                            onCreateNewTask = {
                                viewModel.clearReplacementMode()
                                navigateToRoute(MuharrirRoutes.ROUTE_NEW_DOCUMENT)
                            },
                            onOpenSettings = { navigateToRoute(MuharrirRoutes.ROUTE_SETTINGS) },
                            onSelectFilter = viewModel::setDashboardFilter,
                            onOpenDocument = { doc ->
                                viewModel.selectDocument(doc.id)
                                navigateToRoute(MuharrirRoutes.ROUTE_WORKSPACE)
                            },
                        )
                    }

                    composable(MuharrirRoutes.ROUTE_NEW_DOCUMENT) {
                        NewDocumentScreen(
                            state = newDocState,
                            onPdfPicked = viewModel::onPdfSelected,
                            onChangePreviewPage = viewModel::changeNewDocPreviewPage,
                            onClearSelection = viewModel::clearSelectedPdf,
                            onCancelReplacementMode = viewModel::clearReplacementMode,
                            onStartDocumentTask = { context ->
                                viewModel.startNewDocumentTask(context) {
                                    navigateToRoute(MuharrirRoutes.ROUTE_WORKSPACE)
                                }
                            },
                            onCancelActiveImport = viewModel::cancelActiveImport,
                        )
                    }

                    composable(MuharrirRoutes.ROUTE_WORKSPACE) {
                        DocumentWorkspaceScreen(
                            document = activeDocument,
                            stages = viewModel.getPlannedStages(activeDocument),
                            preferredExportMode = preferences.preferredExportMode,
                            backendConnectionState = viewModel.backendConnectionState,
                            ocrState = ocrExecutionState,
                            geminiState = geminiExecutionState,
                            geminiKeys = geminiKeys,
                            geminiUsageStats = geminiUsageStats,
                            previewBitmap = workspacePreviewBitmap,
                            previewPageIndex = workspacePreviewPage,
                            isLoadingPreviewPage = isLoadingWorkspacePage,
                            pageThumbnails = pageThumbnails,
                            workspaceNoticeAr = workspaceNotice,
                            onDismissNotice = viewModel::dismissWorkspaceNotice,
                            onRequestPreviewPage = viewModel::loadWorkspacePreviewPage,
                            onRequestPageThumbnail = viewModel::requestPageThumbnail,
                            onSelectOcrEngine = viewModel::setOcrEngine,
                            onStartOrResumeOcr = viewModel::startOrResumeOcr,
                            onPauseOcr = viewModel::pauseOcr,
                            onRetryFailedOcr = viewModel::retryFailedOcrPages,
                            onRetrySingleOcrPage = viewModel::retrySingleOcrPage,
                            onStartGeminiAnalysis = viewModel::startGeminiAnalysis,
                            onPauseGeminiAnalysis = viewModel::pauseGeminiAnalysis,
                            onOpenSettings = { navigateToRoute(MuharrirRoutes.ROUTE_SETTINGS) },
                            onOpenReviewWorkspace = { navigateToRoute(MuharrirRoutes.ROUTE_REVIEW) },
                            onCreateNewDocument = {
                                viewModel.clearReplacementMode()
                                navigateToRoute(MuharrirRoutes.ROUTE_NEW_DOCUMENT)
                            },
                            onReplaceDocument = { doc ->
                                viewModel.prepareReplacementImport(doc)
                                navigateToRoute(MuharrirRoutes.ROUTE_NEW_DOCUMENT)
                            },
                            onRemoveDocument = { docId ->
                                viewModel.removeDocument(docId)
                                navigateToRoute(MuharrirRoutes.ROUTE_DASHBOARD)
                            },
                        )
                    }

                    composable(MuharrirRoutes.ROUTE_REVIEW) {
                        ReviewWorkspaceScreen(
                            document = activeDocument,
                            previewBitmap = workspacePreviewBitmap,
                            previewPageIndex = workspacePreviewPage,
                            onRequestPreviewPage = viewModel::loadWorkspacePreviewPage,
                        )
                    }

                    composable(MuharrirRoutes.ROUTE_SETTINGS) {
                        SettingsScreen(
                            preferences = preferences,
                            geminiKeys = geminiKeys,
                            geminiUsageStats = geminiUsageStats,
                            testingKeyId = testingKeyId,
                            noticeMessageAr = settingsNotice,
                            onDismissNotice = viewModel::dismissSettingsNotice,
                            onAddGeminiKey = viewModel::addGeminiApiKey,
                            onTestGeminiKey = viewModel::testGeminiApiKey,
                            onToggleGeminiKey = viewModel::toggleGeminiApiKey,
                            onRemoveGeminiKey = viewModel::removeGeminiApiKey,
                            onSelectGeminiModel = viewModel::selectGeminiModel,
                            onSelectOcrEngine = viewModel::setOcrEngine,
                            onSelectExportMode = viewModel::setExportMode,
                            onToggleHighlightUnresolved = viewModel::setHighlightUnresolved,
                            onToggleAutoSave = viewModel::setAutoSaveCheckpoints,
                            onToggleResumeAfterInterruption = viewModel::setResumeAfterInterruption,
                        )
                    }
                }
            }
        }
    }
}
