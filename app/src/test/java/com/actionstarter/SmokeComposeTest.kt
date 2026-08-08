package com.actionstarter

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * C1 probe smoke test (docs/plans/phase1-ui-skeleton-domain.md section 5, verification item 6).
 * Confirms that Robolectric can host `createComposeRule()` from `src/test` with
 * `testOptions.unitTests.isIncludeAndroidResources = true`, and that string resources
 * resolve correctly for both the composable under test and the assertion side.
 */
@RunWith(RobolectricTestRunner::class)
class SmokeComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mainScreen_displaysAppNameAndSmokeGreeting() {
        val context = RuntimeEnvironment.getApplication()
        val appName = context.getString(R.string.app_name)
        val helloSmoke = context.getString(R.string.hello_smoke)

        composeTestRule.setContent {
            MainScreen()
        }

        composeTestRule.onNodeWithText(appName).assertIsDisplayed()
        composeTestRule.onNodeWithText(helloSmoke).assertIsDisplayed()
    }
}
