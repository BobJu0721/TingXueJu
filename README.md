# Android AI 聊天 App 原型（名稱未定）

這是一個自用 Android 12+ 原生聊天 App 原型。專案目前只支援 Android，不處理 iOS、Google Play 上架、帳號系統或雲端同步。

## 功能

- 串接 OpenRouter、Groq、Cerebras，以及自訂 OpenAI 相容 API 端點。
- 使用者自行填入 API Key；App 不內建共享 Key。
- 本機保存聊天紀錄、角色、Persona 與世界設定集。
- 角色與 Persona 支援分段表單，並可從 TXT、JSON、DOCX 文件交給 AI 整理成草稿。
- 世界設定集支援關鍵詞觸發與常駐條目。
- 長對話遇到上下文過長時，可自動摘要舊內容後重試。
- API Key 使用 Android Keystore 保存，並停用 Android 自動備份。

## 建置

Debug APK 位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

本機建置：

```powershell
.\gradlew.bat assembleDebug
```

驗證：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

## 注意

- App 名稱尚未決定，目前只使用暫時名稱。
- Debug APK 適合自用與測試，不適合作為正式發布版本。
- 本機資料解除安裝後會刪除。
- 自訂 HTTP 端點可使用，但 API Key 與聊天內容可能外洩，建議使用 HTTPS。
