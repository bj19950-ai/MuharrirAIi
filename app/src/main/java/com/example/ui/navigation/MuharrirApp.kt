package com.example.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.example.export.ui.WordExportViewModel
import com.example.review.ui.ReviewScreen
import com.example.review.ui.ReviewSummaryScreen
import com.example.review.ui.ReviewViewModel
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.DocumentWorkspaceScreen
import com.example.ui.screens.LaunchSplashScreen
import com.example.ui.screens.NewDocumentScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.viewmodel.MuharrirViewModel

object MuharrirRoutes {
    const val ROUTE_DASHBOARD = "dashboard"
    const val ROUTE_NEW_DOCUMENT = "new_document"
    const val ROUTE_WORKSPACE = "workspace"
    const val ROUTE_REVIEW = "review"
    const val ROUTE_REVIEW_SUMMARY = "review_summary"
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
    val documentPageRanges by viewModel.documentPageRanges.collectAsStateWithLifecycle()
    val pagesNeedingReprocessingCount by viewModel.pagesNeedingReprocessingCount.collectAsStateWithLifecycle()
    val appearancePreferences by viewModel.appearancePreferences.collectAsStateWithLifecycle()
    var showLaunchSplash by rememberSaveable { mutableStateOf(true) }

    val navigateToRoute: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val hostOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "LocalViewModelStoreOwner is required"
    }

    val wordExportViewModel: WordExportViewModel = viewModel(
        viewModelStoreOwner = hostOwner,
        key = "word_export_vm",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WordExportViewModel(dataGateway = viewModel.wordExportDataGateway) as T
            }
        },
    )

    val activeReviewViewModel: ReviewViewModel? = activeDocument?.let { doc ->
        viewModel(
            viewModelStoreOwner = hostOwner,
            key = "review_vm_${doc.id}",
            factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ReviewViewModel(
                        documentId = doc.id,
                        reviewQueueGateway = viewModel.reviewQueueGateway,
                        documentReviewGateway = viewModel.documentReviewGateway,
                        pageBitmapSource = viewModel.pageBitmapSource,
                    ) as T
                }
            },
        )
    }

    val isImmersiveReviewRoute = activeReviewViewModel != null && (
        currentRoute == MuharrirRoutes.ROUTE_REVIEW ||
            currentRoute == MuharrirRoutes.ROUTE_REVIEW_SUMMARY
        )

    val isDashboardRoute = currentRoute == MuharrirRoutes.ROUTE_DASHBOARD
    val showTopBar = !isImmersiveReviewRoute && !isDashboardRoute

    val topBarTitle = when (currentRoute) {
        MuharrirRoutes.ROUTE_NEW_DOCUMENT -> "استيراد مستند PDF"
        MuharrirRoutes.ROUTE_WORKSPACE -> "مساحة عمل المستند"
        MuharrirRoutes.ROUTE_REVIEW -> "مساحة المراجعة"
        MuharrirRoutes.ROUTE_REVIEW_SUMMARY -> "ملخص المراجعة والاعتماد"
        MuharrirRoutes.ROUTE_SETTINGS -> "الإعدادات"
        else -> "المحرّر"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 700.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = if (isImmersiveReviewRoute) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
                topBar = {
                    if (showTopBar) {
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
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                },
            bottomBar = {
                if (!useNavigationRail && !isImmersiveReviewRoute) {
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
                if (useNavigationRail && !isImmersiveReviewRoute) {
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
                            onPageRangeChanged = viewModel::updateNewDocumentPageRange,
                            onStartDocumentTask = { context, startPage, endPage ->
                                viewModel.startNewDocumentTask(context, startPage, endPage) {
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
                            selectedPageRange = activeDocument?.let { documentPageRanges[it.id] },
                            pagesNeedingReprocessingCount = pagesNeedingReprocessingCount,
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
                            onReprocessFailedPages = viewModel::reprocessFailedPages,
                            onRetrySingleOcrPage = viewModel::retrySingleOcrPage,
                            onStartGeminiAnalysis = viewModel::startGeminiAnalysis,
                            onPauseGeminiAnalysis = viewModel::pauseGeminiAnalysis,
                            wordExportViewModel = wordExportViewModel,
                            onOpenSettings = { navigateToRoute(MuharrirRoutes.ROUTE_SETTINGS) },
                            onOpenReviewWorkspace = { navigateToRoute(MuharrirRoutes.ROUTE_REVIEW) },
                            onOpenReviewSummary = { navController.navigate(MuharrirRoutes.ROUTE_REVIEW_SUMMARY) },
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
                        val reviewVm = activeReviewViewModel
                        if (reviewVm == null) {
                            EmptyNoActiveDocumentReviewState(
                                onGoToDashboard = { navigateToRoute(MuharrirRoutes.ROUTE_DASHBOARD) },
                            )
                        } else {
                            ReviewScreen(
                                viewModel = reviewVm,
                                onNavigateBack = {
                                    if (!navController.popBackStack()) {
                                        navigateToRoute(MuharrirRoutes.ROUTE_WORKSPACE)
                                    }
                                },
                                onOpenSummary = {
                                    navController.navigate(MuharrirRoutes.ROUTE_REVIEW_SUMMARY) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                    }

                    composable(MuharrirRoutes.ROUTE_REVIEW_SUMMARY) {
                        val reviewVm = activeReviewViewModel
                        if (reviewVm == null) {
                            EmptyNoActiveDocumentReviewState(
                                onGoToDashboard = { navigateToRoute(MuharrirRoutes.ROUTE_DASHBOARD) },
                            )
                        } else {
                            ReviewSummaryScreen(
                                viewModel = reviewVm,
                                onNavigateBackToReview = {
                                    if (!navController.popBackStack()) {
                                        navigateToRoute(MuharrirRoutes.ROUTE_REVIEW)
                                    }
                                },
                                onNavigateToWorkspaceForExport = {
                                    navigateToRoute(MuharrirRoutes.ROUTE_WORKSPACE)
                                },
                            )
                        }
                    }

                    composable(MuharrirRoutes.ROUTE_SETTINGS) {
                        SettingsScreen(
                            preferences = preferences,
                            appearancePreferences = appearancePreferences,
                            geminiKeys = geminiKeys,
                            geminiUsageStats = geminiUsageStats,
                            testingKeyId = testingKeyId,
                            noticeMessageAr = settingsNotice,
                            onDismissNotice = viewModel::dismissSettingsNotice,
                            onSelectAppearanceMode = viewModel::setAppearanceMode,
                            onSelectThemePalette = viewModel::setThemePalette,
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

        AnimatedVisibility(
            visible = showLaunchSplash,
            exit = fadeOut(animationSpec = tween(durationMillis = 240)),
        ) {
            LaunchSplashScreen(
                onFinished = { showLaunchSplash = false },
            )
        }
    }
}

@Composable
private fun EmptyNoActiveDocumentReviewState(
    onGoToDashboard: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("empty_review_state"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "لا يوجد مستند محدد للمراجعة",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "اختر مستنداً من الرئيسية أو استورد ملف PDF جديداً ثم شغّل OCR وتحليل Gemini للبدء.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onGoToDashboard) {
                Text("الانتقال إلى الرئيسية")
            }
        }
    }
}
