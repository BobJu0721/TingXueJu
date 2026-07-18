package com.aichat.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun mainNavigation() = rule.collect(packageName = PACKAGE_NAME) {
        startActivityAndWait()
        clickText("設定|设置")
        clickText("API 設定|API 设置")
        device.pressBack()
        clickText("角色")
        clickText("資料庫|资料库")
        clickText("對話|对话")
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.clickText(pattern: String) {
        val selector = By.text(Pattern.compile(pattern))
        repeat(3) {
            val item = device.wait(Until.findObject(selector), 5_000)
                ?: error("Unable to find UI text: $pattern")
            if (runCatching { item.click() }.isSuccess) {
                device.waitForIdle()
                return
            }
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.aichat.app"
    }
}
