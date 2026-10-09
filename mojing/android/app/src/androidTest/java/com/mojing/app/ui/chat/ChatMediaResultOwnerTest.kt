package com.mojing.app.ui.chat

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.core.app.ActivityOptionsCompat
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatMediaResultOwnerTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun speechResultUsesPendingBranchAndRejectsChangedBranch() {
        val owner = TestActivityResultOwner()
        var branch by mutableStateOf("main")
        val accepted = mutableListOf<String>()
        lateinit var launchers: ChatInputMediaLaunchers

        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
            ) {
                launchers = rememberChatInputMediaLaunchers(
                    sessionId = 7L,
                    currentBranchId = { branch },
                    isCurrentBranch = { it == branch },
                    hasMicrophonePermission = { false },
                    isSpeechRecognitionAvailable = { true },
                    onSpeechText = { text, _ -> accepted += text },
                    onSpeechUnavailable = {},
                    onMicPermissionDenied = {},
                    onImagePicked = { _, _ -> },
                    onGalleryPermissionResult = { _, _, _ -> },
                )
                Button(onClick = { branch = "branch-b" }) { Text("switch") }
            }
        }
        compose.runOnIdle { launchers.launchSpeech() }
        val permissionRequest = owner.lastRequestCode
        owner.dispatch(permissionRequest, true)
        val speechRequest = owner.lastRequestCode
        compose.runOnIdle { branch = "branch-b" }
        owner.dispatch(
            speechRequest,
            ActivityResult(Activity.RESULT_OK, Intent().putStringArrayListExtra("android.speech.extra.RESULTS", arrayListOf("迟到"))),
        )

        compose.runOnIdle { assertTrue(accepted.isEmpty()) }
    }

    @Test
    fun pickerCancelClearsPendingSoItCanBeRetried() {
        val owner = TestActivityResultOwner()
        var launches = 0
        lateinit var launchers: ChatInputMediaLaunchers
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launchers = rememberChatInputMediaLaunchers(
                    sessionId = 7L,
                    currentBranchId = { "main" },
                    isCurrentBranch = { it == "main" },
                    hasMicrophonePermission = { false },
                    isSpeechRecognitionAvailable = { true },
                    onSpeechText = { _, _ -> },
                    onSpeechUnavailable = {},
                    onMicPermissionDenied = {},
                    onImagePicked = { _, _ -> launches++ },
                    onGalleryPermissionResult = { _, _, _ -> },
                )
            }
        }
        compose.runOnIdle { launchers.launchImage() }
        val first = owner.lastRequestCode
        owner.dispatch(first, null as android.net.Uri?)
        compose.runOnIdle { launchers.launchImage() }
        assertEquals(2, owner.launchCount)
    }

    @Test
    fun saveablePendingPickerSurvivesStateRestoration() {
        val owner = TestActivityResultOwner()
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        var picked = 0
        lateinit var launchers: ChatInputMediaLaunchers
        restoration.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launchers = rememberChatInputMediaLaunchers(
                    sessionId = 7L,
                    currentBranchId = { "main" },
                    isCurrentBranch = { it == "main" },
                    hasMicrophonePermission = { false },
                    isSpeechRecognitionAvailable = { true },
                    onSpeechText = { _, _ -> },
                    onSpeechUnavailable = {},
                    onMicPermissionDenied = {},
                    onImagePicked = { _, _ -> picked++ },
                    onGalleryPermissionResult = { _, _, _ -> },
                )
            }
        }
        compose.runOnIdle { launchers.launchImage() }
        restoration.emulateSavedInstanceStateRestore()
        owner.dispatch(owner.lastRequestCode, android.net.Uri.parse("content://test/image"))

        compose.runOnIdle { assertEquals(1, picked) }
    }

    @Test
    fun deniedMicrophonePermissionClearsPendingSoItCanBeRetried() {
        val owner = TestActivityResultOwner()
        lateinit var launchers: ChatInputMediaLaunchers
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launchers = rememberChatInputMediaLaunchers(
                    sessionId = 7L,
                    currentBranchId = { "main" },
                    isCurrentBranch = { it == "main" },
                    hasMicrophonePermission = { false },
                    isSpeechRecognitionAvailable = { true },
                    onSpeechText = { _, _ -> },
                    onSpeechUnavailable = {},
                    onMicPermissionDenied = {},
                    onImagePicked = { _, _ -> },
                    onGalleryPermissionResult = { _, _, _ -> },
                )
            }
        }
        compose.runOnIdle { launchers.launchSpeech() }
        owner.dispatch(owner.lastRequestCode, false)
        compose.runOnIdle { launchers.launchSpeech() }
        assertEquals(2, owner.launchCount)
    }

    @Test
    fun unavailableSpeechDoesNotRequestPermissionAndCanBeRetried() {
        val owner = TestActivityResultOwner()
        var unavailable = 0
        lateinit var launchers: ChatInputMediaLaunchers
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launchers = rememberChatInputMediaLaunchers(
                    sessionId = 7L,
                    currentBranchId = { "main" },
                    isCurrentBranch = { it == "main" },
                    hasMicrophonePermission = { false },
                    isSpeechRecognitionAvailable = { false },
                    onSpeechText = { _, _ -> },
                    onSpeechUnavailable = { unavailable++ },
                    onMicPermissionDenied = {},
                    onImagePicked = { _, _ -> },
                    onGalleryPermissionResult = { _, _, _ -> },
                )
            }
        }
        compose.runOnIdle { launchers.launchSpeech() }
        compose.runOnIdle { launchers.launchSpeech() }

        assertEquals("unavailable speech must never request microphone permission", 0, owner.launchCount)
        assertEquals(2, unavailable)
    }

    @Test
    fun canceledSpeechClearsOwnerSoTheNextAttemptCanLaunch() {
        val owner = TestActivityResultOwner()
        lateinit var launchers: ChatInputMediaLaunchers
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launchers = rememberChatInputMediaLaunchers(
                    sessionId = 7L,
                    currentBranchId = { "main" },
                    isCurrentBranch = { it == "main" },
                    hasMicrophonePermission = { true },
                    isSpeechRecognitionAvailable = { true },
                    onSpeechText = { _, _ -> },
                    onSpeechUnavailable = {},
                    onMicPermissionDenied = {},
                    onImagePicked = { _, _ -> },
                    onGalleryPermissionResult = { _, _, _ -> },
                )
            }
        }
        compose.runOnIdle { launchers.launchSpeech() }
        val firstRequest = owner.lastRequestCode
        owner.dispatch(firstRequest, ActivityResult(Activity.RESULT_CANCELED, null))
        compose.runOnIdle { launchers.launchSpeech() }

        assertEquals(2, owner.launchCount)
    }

    @Test
    fun speechServiceCanDisappearAfterPermissionAndNextAttemptCanRetry() {
        val owner = TestActivityResultOwner()
        var available = true
        var unavailable = 0
        lateinit var launchers: ChatInputMediaLaunchers
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launchers = rememberChatInputMediaLaunchers(
                    sessionId = 7L,
                    currentBranchId = { "main" },
                    isCurrentBranch = { it == "main" },
                    hasMicrophonePermission = { false },
                    isSpeechRecognitionAvailable = { available },
                    onSpeechText = { _, _ -> },
                    onSpeechUnavailable = { unavailable++ },
                    onMicPermissionDenied = {},
                    onImagePicked = { _, _ -> },
                    onGalleryPermissionResult = { _, _, _ -> },
                )
            }
        }
        compose.runOnIdle { launchers.launchSpeech() }
        val permissionRequest = owner.lastRequestCode
        available = false
        owner.dispatch(permissionRequest, true)
        assertEquals("service disappearance must not launch speech", 1, owner.launchCount)
        assertEquals("service disappearance is reported once per attempt", 1, unavailable)
        available = true
        compose.runOnIdle { launchers.launchSpeech() }
        assertEquals("fresh attempt may request permission again", 2, owner.launchCount)
        owner.dispatch(owner.lastRequestCode, true)
        assertEquals("fresh grant starts recognition", 3, owner.launchCount)
    }

    @Test
    fun emptySpeechResultIsConsumedWithoutDraftDeliveryAndCanRetry() {
        val owner = TestActivityResultOwner()
        val delivered = mutableListOf<String>()
        lateinit var launchers: ChatInputMediaLaunchers
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launchers = rememberChatInputMediaLaunchers(
                    sessionId = 7L,
                    currentBranchId = { "main" },
                    isCurrentBranch = { true },
                    hasMicrophonePermission = { true },
                    isSpeechRecognitionAvailable = { true },
                    onSpeechText = { text, _ -> delivered += text },
                    onSpeechUnavailable = {},
                    onMicPermissionDenied = {},
                    onImagePicked = { _, _ -> },
                    onGalleryPermissionResult = { _, _, _ -> },
                )
            }
        }
        compose.runOnIdle { launchers.launchSpeech() }
        owner.dispatch(
            owner.lastRequestCode,
            ActivityResult(
                Activity.RESULT_OK,
                Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("  ")),
            ),
        )
        compose.runOnIdle { launchers.launchSpeech() }

        assertEquals(emptyList<String>(), delivered)
        assertEquals(2, owner.launchCount)
    }

    @Test
    fun saveableTextFieldValueRestoresSelectionAfterStateRecreation() {
        val restoration = StateRestorationTester(compose)
        lateinit var value: TextFieldValue
        restoration.setContent {
            var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
                mutableStateOf(TextFieldValue("abcdef", TextRange(2, 4)))
            }
            value = field
            Button(onClick = { field = field.copy(selection = TextRange(5)) }) { Text("move") }
        }
        compose.onNodeWithText("move").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(TextRange(5), value.selection) }
    }

    @Test
    fun acceptedSpeechIsDeliveredOnceAndGalleryPermissionKeepsItsOriginalTarget() {
        val owner = TestActivityResultOwner()
        var branch by mutableStateOf("main")
        val speech = mutableListOf<String>()
        val galleries = mutableListOf<Long>()
        lateinit var launchers: ChatInputMediaLaunchers
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launchers = rememberChatInputMediaLaunchers(
                    sessionId = 7L, currentBranchId = { branch }, isCurrentBranch = { it == branch },
                    hasMicrophonePermission = { false }, isSpeechRecognitionAvailable = { true },
                    onSpeechText = { text, _ -> speech += text }, onSpeechUnavailable = {},
                    onMicPermissionDenied = {}, onImagePicked = { _, _ -> },
                    onGalleryPermissionResult = { id, _, granted -> if (granted) galleries += id },
                )
            }
        }
        compose.runOnIdle { launchers.launchSpeech() }
        owner.dispatch(owner.lastRequestCode, true)
        val result = ActivityResult(Activity.RESULT_OK,
            Intent().putStringArrayListExtra("android.speech.extra.RESULTS", arrayListOf(" 已识别 ")))
        owner.dispatch(owner.lastRequestCode, result)
        owner.dispatch(owner.lastRequestCode, result)
        compose.runOnIdle {
            assertEquals(listOf("已识别"), speech)
            launchers.requestGalleryPermission(41L)
            launchers.requestGalleryPermission(42L)
        }
        val originalPermission = owner.lastRequestCode
        compose.runOnIdle { branch = "other" }
        owner.dispatch(originalPermission, true)
        compose.runOnIdle {
            assertTrue(galleries.isEmpty())
            launchers.requestGalleryPermission(43L)
        }
        owner.dispatch(owner.lastRequestCode, true)
        compose.runOnIdle { assertEquals(listOf(43L), galleries) }
    }

    @Test
    fun lateImageDoesNotEnterAnotherBranchAndFreshPickerStillWorks() {
        val owner = TestActivityResultOwner()
        var branch by mutableStateOf("main")
        val delivered = mutableListOf<ChatMediaRequest>()
        lateinit var launchers: ChatInputMediaLaunchers
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                launchers = rememberChatInputMediaLaunchers(
                    sessionId = 7L, currentBranchId = { branch }, isCurrentBranch = { it == branch },
                    onSpeechText = { _, _ -> }, onSpeechUnavailable = {}, onMicPermissionDenied = {},
                    onImagePicked = { _, request -> delivered += request },
                    onGalleryPermissionResult = { _, _, _ -> },
                )
            }
        }
        compose.runOnIdle { launchers.launchImage(); launchers.launchImage() }
        assertEquals(1, owner.launchCount)
        compose.runOnIdle { branch = "other" }
        owner.dispatch(owner.lastRequestCode, android.net.Uri.parse("content://test/old"))
        compose.runOnIdle { assertTrue(delivered.isEmpty()); launchers.launchImage() }
        owner.dispatch(owner.lastRequestCode, android.net.Uri.parse("content://test/new"))
        compose.runOnIdle { assertEquals(listOf(ChatMediaRequest(7L, "other")), delivered) }
    }

    private class TestActivityResultOwner : ActivityResultRegistryOwner {
        override val activityResultRegistry = RecordingRegistry()
        val lastRequestCode: Int get() = activityResultRegistry.lastRequestCode
        val launchCount: Int get() = activityResultRegistry.launchCount

        fun <O> dispatch(requestCode: Int, result: O) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                activityResultRegistry.dispatchResult(requestCode, result)
            }
        }
    }

    private class RecordingRegistry : ActivityResultRegistry() {
        var lastRequestCode: Int = -1
        var launchCount: Int = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            lastRequestCode = requestCode
            launchCount++
        }
    }
}
