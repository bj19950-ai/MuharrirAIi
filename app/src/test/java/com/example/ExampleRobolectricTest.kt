package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `app name matches Arabic identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("مُحَرِّر AI", appName)
    }

    @Test
    fun `app launches with Arabic dashboard and working navigation across all screens`() {
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()
        composeRule.onNodeWithText("مهمة تحويل جديدة").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("dashboard_empty_state"))
        composeRule.onNodeWithTag("dashboard_empty_state").assertIsDisplayed()

        // Navigate to New Document screen
        composeRule.onNodeWithTag("nav_new_document").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("new_document_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("select_pdf_button").assertIsDisplayed()

        // Navigate to Document Workspace screen (empty state)
        composeRule.onNodeWithTag("nav_workspace").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("document_workspace_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("empty_workspace_state").assertIsDisplayed()

        // Navigate to Review Workspace screen
        composeRule.onNodeWithTag("nav_review").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("review_workspace_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("review_original_page_panel").assertIsDisplayed()

        // Navigate to Settings screen
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("add_api_key_button").assertIsDisplayed()
    }
}
