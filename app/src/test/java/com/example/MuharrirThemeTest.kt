package com.example

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import com.example.ui.theme.MuharrirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MuharrirThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `theme enforces right to left layout direction`() {
        var resolvedDirection: LayoutDirection? = null
        composeRule.setContent {
            MuharrirTheme {
                resolvedDirection = LocalLayoutDirection.current
            }
        }
        composeRule.waitForIdle()
        assertEquals(LayoutDirection.Rtl, resolvedDirection)
    }
}
