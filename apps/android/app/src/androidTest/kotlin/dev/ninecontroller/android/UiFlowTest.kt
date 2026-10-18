package dev.ninecontroller.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.ninecontroller.android.pad.PadMapping
import dev.ninecontroller.android.pad.PadPresets
import dev.ninecontroller.android.ui.pad.PadDiagram
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Production UI on a real device. Saves review screenshots to
 * /sdcard/Android/data/dev.ninecontroller.android/files/ui-review/ (pulled by tools/ui-review.sh).
 * The user's selected layout is restored afterwards.
 */
class UiFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val app get() = rule.activity.application as NineApp
    private fun shot(name: String, bmp: Bitmap) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun selectingALayoutUpdatesTheAppAndPersistsAcrossRestart() {
        val original = runBlocking { app.settings.flow.first().activeProfile }
        try {
            runBlocking { app.settings.update { it.copy(activeProfile = "pad:xbox") } }
            rule.waitForIdle()
            shot("01-home", rule.onRoot().captureToImage().asAndroidBitmap())
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("Xbox-style"))
            rule.onNodeWithText("Xbox-style").performClick() // layout section → Layouts
            rule.waitForIdle()
            shot("02-layouts", rule.onRoot().captureToImage().asAndroidBitmap())
            rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("DualSense-style"))
            rule.onNode(hasContentDescription("DualSense-style")).performClick() // card → detail
            rule.waitForIdle()
            rule.onNodeWithText("Use this layout").performClick()
            rule.waitUntil(3000) { runBlocking { app.settings.flow.first().activeProfile } == "pad:dualsense" }
            rule.onNodeWithText("This is the layout on your controller screen.").assertIsDisplayed()
            shot("03-layout-detail", rule.onRoot().captureToImage().asAndroidBitmap())

            rule.activityRule.scenario.recreate()
            rule.waitForIdle()
            assertEquals("pad:dualsense", runBlocking { app.settings.flow.first().activeProfile })
            rule.onNode(hasScrollAction()).performScrollToNode(hasText("DualSense-style"))
            rule.onNodeWithText("DualSense-style").assertIsDisplayed()
        } finally {
            runBlocking { app.settings.update { it.copy(activeProfile = original) } }
        }
    }

    @Test fun everyPresetDiagramRendersWithUnavailableControlsDescribed() {
        for (p in PadPresets.all) {
            rule.activity.runOnUiThread {
                rule.activity.setContent {
                    NineTheme { Box(Modifier.background(Nine.bg)) { PadDiagram(PadMapping.default(p), Modifier.size(640.dp, 320.dp)) } }
                }
            }
            rule.waitForIdle()
            val node = rule.onNode(hasContentDescription(p.name, substring = true))
            node.assertIsDisplayed()
            p.elements.filter { it.default == null }.forEach { e ->
                rule.onNode(hasContentDescription("${e.label} (unavailable)", substring = true)).assertExists()
            }
            shot("preset-${p.id}", node.captureToImage().asAndroidBitmap())
        }
    }
}
