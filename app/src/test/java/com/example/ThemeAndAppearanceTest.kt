package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.ExportMode
import com.example.domain.model.ExportedDocument
import com.example.domain.model.GeminiUsageStats
import com.example.domain.model.ProcessingPreferences
import com.example.domain.model.ReviewStatus
import com.example.export.model.DocumentPageModel
import com.example.export.model.ExportDocumentModel
import com.example.export.model.WordExportDataGateway
import com.example.export.model.WordExportMode
import com.example.export.ui.WordExportSection
import com.example.export.ui.WordExportViewModel
import com.example.review.model.BoundingBox
import com.example.review.model.DiscrepancyCategory
import com.example.review.model.DocumentReviewGateway
import com.example.review.model.DocumentReviewInfo
import com.example.review.model.PageBitmapSource
import com.example.review.model.ReviewItemModel
import com.example.review.model.ReviewQueueGateway
import com.example.review.model.ReviewStatusCounts
import com.example.review.ui.ReviewScreen
import com.example.review.ui.ReviewSummaryScreen
import com.example.review.ui.ReviewViewModel
import com.example.ui.screens.LaunchSplashContent
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AppThemePalette
import com.example.ui.theme.AppearanceMode
import com.example.ui.theme.AppearancePreferencesStore
import com.example.ui.theme.MuharrirTheme
import com.example.ui.theme.colorSchemeFor
import com.example.ui.theme.extendedColorsFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeAndAppearanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(AppearancePreferencesStore.PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        AppearancePreferencesStore.resetForTesting()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(AppearancePreferencesStore.PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        AppearancePreferencesStore.resetForTesting()
    }

    @Test
    fun `appearance preferences persist and restore across store instances`() {
        val store1 = AppearancePreferencesStore.getInstance(context)
        assertEquals(AppearanceMode.SYSTEM, store1.preferences.value.appearanceMode)
        assertEquals(AppThemePalette.NEUTRAL, store1.preferences.value.themePalette)

        store1.setAppearanceMode(AppearanceMode.DARK)
        store1.setThemePalette(AppThemePalette.WARM_IVORY)

        // Simulate fresh app launch by clearing singleton and creating a new store from SharedPreferences
        AppearancePreferencesStore.resetForTesting()
        val store2 = AppearancePreferencesStore.getInstance(context)
        assertEquals(AppearanceMode.DARK, store2.preferences.value.appearanceMode)
        assertEquals(AppThemePalette.WARM_IVORY, store2.preferences.value.themePalette)

        store2.setAppearanceMode(AppearanceMode.LIGHT)
        store2.setThemePalette(AppThemePalette.SOFT_SAGE)

        AppearancePreferencesStore.resetForTesting()
        val store3 = AppearancePreferencesStore.getInstance(context)
        assertEquals(AppearanceMode.LIGHT, store3.preferences.value.appearanceMode)
        assertEquals(AppThemePalette.SOFT_SAGE, store3.preferences.value.themePalette)
    }

    @Test
    fun `all curated palettes provide distinct calm light and true dark schemes`() {
        val lightBackgrounds = mutableSetOf<Int>()
        val darkBackgrounds = mutableSetOf<Int>()

        AppThemePalette.entries.forEach { palette ->
            val light = colorSchemeFor(palette, darkTheme = false)
            val dark = colorSchemeFor(palette, darkTheme = true)
            val lightExt = extendedColorsFor(palette, darkTheme = false)
            val darkExt = extendedColorsFor(palette, darkTheme = true)

            assertTrue("${palette.name} light background should be bright", light.background.luminance() > 0.7f)
            assertTrue("${palette.name} dark background should be genuinely dark", dark.background.luminance() < 0.08f)
            assertTrue("${palette.name} dark surface should be dark", dark.surface.luminance() < 0.10f)
            assertTrue("${palette.name} light header should have dark contrast", lightExt.headerGradientStart.luminance() < 0.15f)
            assertTrue("${palette.name} light onHeader should be bright", lightExt.onHeader.luminance() > 0.75f)
            assertTrue("${palette.name} dark onHeader should be bright", darkExt.onHeader.luminance() > 0.70f)
            assertNotEquals(light.background, dark.background)

            lightBackgrounds.add(light.background.hashCode())
            darkBackgrounds.add(dark.background.hashCode())
        }

        assertEquals(AppThemePalette.entries.size, lightBackgrounds.size)
        assertEquals(AppThemePalette.entries.size, darkBackgrounds.size)
    }

    @Test
    fun `launch splash screen displays logo and المحرّر Arabic title`() {
        composeRule.setContent {
            MuharrirTheme(appearanceMode = AppearanceMode.LIGHT, themePalette = AppThemePalette.WARM_IVORY) {
                LaunchSplashContent()
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("launch_splash_screen").assertIsDisplayed()
        try {
            composeRule.onNodeWithTag("launch_splash_logo").assertIsDisplayed()
        } catch (e: AssertionError) {
            val tree = composeRule.onRoot().printToString()
            val node = composeRule.onNodeWithTag("launch_splash_logo").fetchSemanticsNode()
            throw AssertionError("${e.message} id=${node.id} winBounds=${node.boundsInWindow} rootBounds=${node.boundsInRoot}\n$tree", e)
        }
        composeRule.onNodeWithTag("launch_splash_title").assertIsDisplayed()
        composeRule.onNodeWithText("المحرّر").assertIsDisplayed()
    }

    @Test
    fun `settings screen updates and persists light dark auto and palette selections`() {
        val store = AppearancePreferencesStore.getInstance(context)
        var observedBgLuminance = 0f

        composeRule.setContent {
            val prefs by store.preferences.collectAsState()
            MuharrirTheme(
                appearanceMode = prefs.appearanceMode,
                themePalette = prefs.themePalette,
            ) {
                observedBgLuminance = MaterialTheme.colorScheme.background.luminance()
                SettingsScreen(
                    preferences = ProcessingPreferences(),
                    appearancePreferences = prefs,
                    geminiKeys = emptyList(),
                    geminiUsageStats = GeminiUsageStats(),
                    testingKeyId = null,
                    noticeMessageAr = null,
                    onDismissNotice = {},
                    onSelectAppearanceMode = store::setAppearanceMode,
                    onSelectThemePalette = store::setThemePalette,
                    onAddGeminiKey = { _, _ -> },
                    onTestGeminiKey = {},
                    onToggleGeminiKey = { _, _ -> },
                    onRemoveGeminiKey = {},
                    onSelectGeminiModel = {},
                    onSelectOcrEngine = {},
                    onSelectExportMode = {},
                    onToggleHighlightUnresolved = {},
                    onToggleAutoSave = {},
                    onToggleResumeAfterInterruption = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("الوضع النهاري").assertIsDisplayed()
        composeRule.onNodeWithText("الوضع الليلي").assertIsDisplayed()
        composeRule.onNodeWithText("تلقائي / حسب إعدادات الجهاز").assertIsDisplayed()

        // Switch to Dark mode and Muted Blue-Gray palette
        composeRule.onNodeWithTag("appearance_mode_DARK").performClick()
        composeRule.onNodeWithTag("theme_palette_MUTED_BLUE_GRAY").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(AppearanceMode.DARK, store.preferences.value.appearanceMode)
        assertEquals(AppThemePalette.MUTED_BLUE_GRAY, store.preferences.value.themePalette)
        assertTrue("Dark mode should lower background luminance", observedBgLuminance < 0.08f)

        // Switch to Light mode and Warm Ivory palette
        composeRule.onNodeWithTag("appearance_mode_LIGHT").performScrollTo().performClick()
        composeRule.onNodeWithTag("theme_palette_WARM_IVORY").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(AppearanceMode.LIGHT, store.preferences.value.appearanceMode)
        assertEquals(AppThemePalette.WARM_IVORY, store.preferences.value.themePalette)
        assertTrue("Light mode should restore bright background luminance", observedBgLuminance > 0.75f)
    }

    @Test
    fun `review screen renders cleanly in dark theme`() {
        val reviewVm = createTestReviewViewModel()

        composeRule.setContent {
            MuharrirTheme(appearanceMode = AppearanceMode.DARK, themePalette = AppThemePalette.CHARCOAL_SLATE) {
                ReviewScreen(
                    viewModel = reviewVm,
                    onNavigateBack = {},
                    onOpenSummary = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("review_item_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("approve_gemini_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `review summary screen renders cleanly in dark theme`() {
        val reviewVm = createTestReviewViewModel()

        composeRule.setContent {
            MuharrirTheme(appearanceMode = AppearanceMode.DARK, themePalette = AppThemePalette.MUTED_BLUE_GRAY) {
                ReviewSummaryScreen(
                    viewModel = reviewVm,
                    onNavigateBackToReview = {},
                    onNavigateToWorkspaceForExport = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("review_summary_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("summary_stats_card").assertIsDisplayed()
    }

    @Test
    fun `word export section renders cleanly in dark theme`() {
        val exportVm = WordExportViewModel(dataGateway = NoOpWordExportDataGateway())

        composeRule.setContent {
            MuharrirTheme(appearanceMode = AppearanceMode.DARK, themePalette = AppThemePalette.SOFT_SAGE) {
                WordExportSection(
                    documentId = "doc-ui",
                    documentTitle = "نموذج_المراجعة.pdf",
                    isReviewApproved = true,
                    preferredExportMode = ExportMode.ACADEMIC_FORMAT,
                    exportedDocument = ExportedDocument(
                        documentId = "doc-ui",
                        docxFileRef = "/tmp/output_test.docx",
                        exportMode = ExportMode.ACADEMIC_FORMAT,
                        createdAtMillis = 1700000000000L,
                    ),
                    exportViewModel = exportVm,
                    onOpenReviewSummary = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("export_to_word_button").assertIsDisplayed()
        composeRule.onNodeWithTag("exported_docx_summary_card").assertIsDisplayed()
    }

    private fun createTestReviewViewModel(): ReviewViewModel {
        val sampleItems = listOf(
            ReviewItemModel(
                id = 1L,
                documentId = "doc-ui",
                pageNumber = 1,
                category = DiscrepancyCategory.B,
                ocrText = "المدينه",
                suggestedText = "المدينة",
                ocrConfidence = 0.85f,
                geminiConfidence = 0.96f,
                evidence = "التاء المربوطة ظاهرة",
                bbox = BoundingBox(10f, 20f, 120f, 60f),
                reviewStatus = ReviewStatus.APPROVED_GEMINI,
                userText = "المدينة",
                resolvedAt = 1000L,
                orderIndex = 0,
            ),
        )
        val queueGateway = SimpleReviewQueueGateway(sampleItems)
        val docGateway = SimpleDocumentReviewGateway("doc-ui", "نموذج_المراجعة.pdf", approvedAt = 5000L)
        val bitmapSource = object : PageBitmapSource {
            override suspend fun loadPageBitmap(documentId: String, pageNumber: Int): Bitmap =
                Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        }
        return ReviewViewModel(
            documentId = "doc-ui",
            reviewQueueGateway = queueGateway,
            documentReviewGateway = docGateway,
            pageBitmapSource = bitmapSource,
            savedStateHandle = SavedStateHandle(),
        )
    }

    private class SimpleReviewQueueGateway(initialItems: List<ReviewItemModel>) : ReviewQueueGateway {
        private val flow = MutableStateFlow(initialItems)
        override fun getReviewQueue(documentId: String): Flow<List<ReviewItemModel>> = flow
        override fun countByStatus(documentId: String): Flow<ReviewStatusCounts> =
            flow.map { list ->
                ReviewStatusCounts(
                    total = list.size,
                    approvedGemini = list.count { it.reviewStatus == ReviewStatus.APPROVED_GEMINI },
                    manuallyEdited = 0,
                    unresolved = 0,
                    remaining = 0,
                )
            }
        override suspend fun getNextPending(documentId: String, afterItemId: Long): ReviewItemModel? = null
        override suspend fun saveDecision(itemId: Long, newStatus: ReviewStatus, userText: String?, resolvedAt: Long) {}
        override suspend fun reopenItemAsPending(itemId: Long) {}
    }

    private class SimpleDocumentReviewGateway(
        documentId: String,
        title: String,
        approvedAt: Long?,
    ) : DocumentReviewGateway {
        private val flow = MutableStateFlow(DocumentReviewInfo(documentId, title, approvedAt))
        override fun observeDocument(documentId: String): Flow<DocumentReviewInfo?> = flow
        override suspend fun setReviewApprovedAt(documentId: String, approvedAtMillis: Long) {}
    }

    private class NoOpWordExportDataGateway : WordExportDataGateway {
        override suspend fun getDocument(documentId: String): ExportDocumentModel? = null
        override suspend fun getPagesInOrder(documentId: String): List<DocumentPageModel> = emptyList()
        override suspend fun getReviewItemsInOrder(documentId: String): List<ReviewItemModel> = emptyList()
        override suspend fun shouldHighlightUnresolved(): Boolean = true
        override suspend fun recordExportedDocument(
            documentId: String,
            docxFilePath: String,
            mode: WordExportMode,
            exportedAtMillis: Long,
        ) {}
    }
}
