# 更新公告

## v1.3

這版加入語言切換、聊天背景圖與更緊湊的介面，並取消底部通知，讓自用測試時畫面更乾淨。

- 設定頁新增繁體中文 / 简体中文切換。
- UI 文案、錯誤提示與內建送給模型的提示文字會跟著語言切換。
- 使用者訊息、AI 回覆、角色卡、Persona 與 World Info 內容不會被自動翻譯。
- 開啟既有對話時會自動捲到最新訊息。
- 串流生成時，若使用者停在底部附近，畫面會跟隨新內容；如果正在看舊訊息，不會強制拉回底部。
- 對話資訊頁新增單一對話背景圖上傳與移除。
- 聊天背景圖只顯示在訊息區，並加固定半透明遮罩維持可讀性。
- 全 App 上方列改為 48dp 緊湊標題列，減少 20:9 手機上的上方留白。
- 取消「設定已儲存」「已重新發送」等底部短通知。
- Room 資料庫升到 v3，對話新增 `backgroundImagePath` 欄位。
- Android 版本資訊更新為 `versionName = 1.3`、`versionCode = 4`。

APK：

- `apk/TingXueJu-v1.3-debug.apk`
- `apk/TingXueJu-debug.apk`

SHA-256：

```text
8E71C3F9BD764625051A79ABFB11E1B1199367AB9C46A31E5CA5043E942CC75E
```

## v1.2

這版把 Android 底部三大金剛鍵隱藏起來，讓聊天畫面更沉浸。

- 進入 App 後自動隱藏 Android navigation bar。
- 保留狀態列，不影響時間、電量與通知區顯示。
- 從螢幕底部上滑時，可以暫時叫出系統導覽鍵。
- Android 版本資訊更新為 `versionName = 1.2`、`versionCode = 3`。

APK：

- `apk/TingXueJu-v1.2-debug.apk`
- `apk/TingXueJu-debug.apk`

SHA-256：

```text
401BF1030CAFF5812B4A4F26AACFDBBCC90BF446F2FFC07800EBD8F0EECE6F9E
```

## v1.1

這版把聊天操作做得更順手，也針對長螢幕手機調整了底部導覽。

- 底部「對話 / 角色 / 資料庫 / 設定」改成更緊湊的 52dp 自訂底欄。
- 每則訊息新增編輯功能，可修改自己輸入的訊息，也可修改 AI 輸出的訊息。
- 每則訊息新增重新發送功能，可從指定訊息位置重新生成後續內容。
- 更新 GitHub 下載 APK。

APK：

- `apk/TingXueJu-v1.1-debug.apk`
- `apk/TingXueJu-debug.apk`

SHA-256：

```text
0FEF1E4857BAE1878851BEDDDFA9DE48A0626C9671272BD6617EC06A99804EFC
```

## v1.0

第一個上傳到 GitHub 的可用版本。

- Android 12+ 原生 Kotlin + Jetpack Compose App。
- 支援 OpenRouter、Groq、Cerebras 與自訂 OpenAI 相容端點。
- 本機聊天紀錄、API 設定、模型選擇與模型搜尋。
- 角色、Persona、世界設定集與文件匯入整理功能。
- 長對話上下文過長時，可自動摘要舊內容後重試。
- 附上 debug APK，方便直接下載安裝。

APK：

- `apk/TingXueJu-v1.0-debug.apk`

SHA-256：

```text
5D376C0DEBEAA308AD293C51FBB7BDCA35C7DC93AB7E76E3F984385C085D9F99
```
