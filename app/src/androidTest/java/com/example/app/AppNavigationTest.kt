package com.example.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun homeUsesNativeFeedWithoutAYouTubeWebView() {
        compose.onNodeWithText("Dành cho bạn").assertIsDisplayed()
        compose.onNodeWithText("Làm mới").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            fun containsWebView(view: android.view.View): Boolean = view is android.webkit.WebView ||
                (view is android.view.ViewGroup && (0 until view.childCount).any { containsWebView(view.getChildAt(it)) })
            org.junit.Assert.assertFalse(containsWebView(activity.window.decorView))
        }
    }
    @Test fun videoSettingsHaveAllSleepDurationsAndMinimizeKeepsControls() {
        compose.onNodeWithContentDescription("Mở liên kết YouTube").performClick()
        compose.onNodeWithText("Liên kết hoặc ID video").performTextInput("dQw4w9WgXcQ")
        compose.onNodeWithText("Mở", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Cài đặt video").performClick()
        listOf(30, 60, 90, 120).forEach { compose.onNodeWithText("$it phút").assertIsDisplayed() }
        compose.onNodeWithText("30 phút").performClick()
        compose.onNodeWithContentDescription("Thu nhỏ video").performClick()
        compose.onNodeWithContentDescription("Mở rộng video").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Cài đặt video").performClick()
        compose.onNodeWithText("Hủy hẹn giờ").performClick()
        compose.onNodeWithContentDescription("Đóng video").performClick()
        compose.onNodeWithContentDescription("Mở rộng video").assertDoesNotExist()
    }

    @Test fun searchFieldOnlyAppearsWhenRequestedAndHasVoiceAction() {
        compose.onNodeWithText("Tìm trên YouTube").assertDoesNotExist()
        compose.onNodeWithContentDescription("Tìm kiếm").performClick()
        compose.onNodeWithText("Tìm trên YouTube").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tìm bằng giọng nói").assertIsDisplayed()
        compose.onNodeWithContentDescription("Đóng tìm kiếm").performClick()
        compose.onNodeWithText("Tìm trên YouTube").assertDoesNotExist()
    }
    @Test fun navigatesLocalLibraryAndSettingsWithoutGoogleConfiguration() {
        compose.onNodeWithText("Thư viện").performClick()
        compose.onNodeWithText("Bộ sưu tập tài khoản").assertIsDisplayed()
        compose.onNodeWithText("Lịch sử").performScrollTo().performClick()
        compose.onNodeWithText("Lịch sử xem trong ứng dụng").assertIsDisplayed()
        compose.onNodeWithText("Playlist trên máy").performScrollTo().performClick()
        compose.onNodeWithText("Tạo playlist trên máy").assertIsDisplayed()
        compose.onNodeWithText("Trên máy").performClick()
        compose.onNodeWithText("Chọn file video / âm thanh").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cài đặt").performClick()
        compose.onNodeWithText("Giao diện tối").assertIsDisplayed()
        compose.onNodeWithContentDescription("Quay lại").performClick()
        compose.onNodeWithText("Chọn file video / âm thanh").assertIsDisplayed()
    }
}
