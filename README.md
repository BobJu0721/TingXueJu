<div align="center">

<img src="docs/app-icon.svg" width="96" height="96" alt="聽雪居 App 圖示">

# 聽雪居

一款由使用者自行連接模型 API、以本機資料管理為核心的 Android AI 聊天 App。

[![Release](https://img.shields.io/github/v/release/BobJu0721/TingXueJu?label=Release)](https://github.com/BobJu0721/TingXueJu/releases/latest)
![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)

[下載 APK](#下載與安裝) · [主要功能](#主要功能) · [快速開始](#快速開始) · [從原始碼建置](#從原始碼建置) · [問題回報](#問題回報)

</div>

聽雪居不綁定單一模型服務。你可以選擇內建供應商，也可以保存多個 OpenAI 相容端點，在同一個 App 內管理對話、角色、Persona 與世界設定。

> [!IMPORTANT]
> 聽雪居不提供共用 API Key，也不包含本地模型。使用前需要準備支援供應商的 API Key；模型費用、速率限制與資料處理由該供應商決定。

## 主要功能

- **多端點管理**：內建 OpenRouter、Groq、Cerebras、Agnes、Cloudflare Workers AI，也可新增多個自訂 OpenAI 相容端點。
- **模型與思考模式**：取得、搜尋或手動輸入模型 ID；每段對話可獨立設定「自動、開啟、關閉」思考模式。
- **串流聊天**：即時顯示正文與可折疊的思考內容，支援複製、編輯、重新生成及快速回到最新訊息。
- **角色與 Persona**：分別保存角色設定與使用者身份，可從 TXT、JSON、DOCX 文件交由 AI 整理成草稿。
- **世界設定集**：依關鍵詞注入地點、人物、關係與規則，也能設定每次生成固定附加的條目。
- **長對話管理**：手動摘要較早訊息，或在上下文過長時裁切並重試，不會刪除畫面中的原始聊天紀錄。
- **對話外觀**：每段對話可使用自訂背景圖並調整訊息背景透明度。
- **Android 體驗**：支援繁體中文、简体中文、深色模式、Predictive Back 與主分頁滑動切換。

## 下載與安裝

### 系統需求

- Android 12（API 31）或以上版本
- 能連線至所選 API 供應商的網路環境
- 使用者自己的 API Key

### 最新版本

目前版本為 **v1.12**：

- [下載簽章版 APK：TingXueJu-v1.12-release.apk](https://github.com/BobJu0721/TingXueJu/releases/download/v1.12/TingXueJu-v1.12-release.apk)
- [查看 v1.12 發布說明](https://github.com/BobJu0721/TingXueJu/releases/tag/v1.12)
- [瀏覽所有 Releases](https://github.com/BobJu0721/TingXueJu/releases)

安裝方式：

1. 從 GitHub Releases 下載 APK。
2. Android 詢問時，允許目前的瀏覽器或檔案管理器「安裝未知應用程式」。
3. 開啟 APK 並完成安裝。
4. 若裝置上已有相同簽章的舊版本，可直接覆蓋升級並保留本機資料。

> [!WARNING]
> 請只從本專案的 GitHub Releases 下載 APK。解除安裝會刪除 App 的本機資料，重要內容請先自行保存。

## 快速開始

1. 安裝並開啟聽雪居。
2. 進入「設定」→「API 與端點」。
3. 選擇內建供應商，或新增自訂端點並填入名稱、Base URL 與 API Key。
4. 按下「設為目前使用」。
5. 從對話首頁新增對話，或到角色頁選擇角色後開始聊天。
6. 在聊天頁點擊模型名稱，選擇模型及該對話的思考模式。

## API 相容性

| 類型 | 設定需求 | 模型清單 |
| --- | --- | --- |
| OpenRouter、Groq、Cerebras、Agnes | API Key | 自動取得 |
| Cloudflare Workers AI | Account ID、API Key | 使用 Cloudflare 模型搜尋接口 |
| 自訂端點 | 名稱、Base URL、API Key | 嘗試 OpenAI 相容的 `/models`，也可手動輸入模型 ID |

> [!NOTE]
> 「OpenAI 相容」不代表所有供應商都支援相同參數。思考模式是否生效仍取決於端點與模型；不支援的請求會保留 API 錯誤，不會暗中改用其他模式重送。

## 資料與隱私

- 對話、角色、Persona、世界設定與一般設定保存在裝置本機。
- API Key 由 Android Keystore 保護，App 停用系統自動備份。
- 聊天內容與必要設定會送往你目前選擇的 API 供應商；請先確認其隱私政策。
- App 允許設定 HTTP 端點，但傳輸可能遭攔截；建議只使用 HTTPS。
- 專案不會替你同步或備份本機聊天資料。

## 技術架構

| 範圍 | 技術 |
| --- | --- |
| UI | Kotlin、Jetpack Compose、Material 3 |
| 導航 | Navigation Compose、Predictive Back |
| 本機資料 | Room、DataStore、Android Keystore |
| 網路與非同步 | OkHttp、Kotlin Coroutines、串流 SSE |
| 效能 | R8、資源縮減、Baseline Profile、Startup Profile |
| 測試 | JUnit、Macrobenchmark、Baseline Profile Generator |

## 從原始碼建置

需要：

- JDK 17
- Android SDK 35
- Git

```bash
git clone https://github.com/BobJu0721/TingXueJu.git
cd TingXueJu
```

Windows PowerShell：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

macOS、Linux 或 Git Bash：

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Debug APK 會輸出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 專案結構

```text
app/                  Android App、資料層、網路層與 Compose UI
baselineprofile/      Baseline Profile 與啟動效能測試
docs/                 文件與畫面資產
更新公告.md            歷史版本更新內容
```

## 專案狀態

- 最新穩定版本：**v1.12**
- 最低系統版本：**Android 12 / API 31**
- 目前僅支援雲端 API，不支援直接連接本地模型
- v1.12 主要更新為介面重設計、思考模式相容性及導覽行為調整

詳細版本內容請查看[更新公告](更新公告.md)與 [GitHub Releases](https://github.com/BobJu0721/TingXueJu/releases)。

## 問題回報

發現 Bug 或有功能建議，請建立 [GitHub Issue](https://github.com/BobJu0721/TingXueJu/issues/new)。

請盡量附上：

- 聽雪居版本
- Android 版本與手機型號
- API 供應商及模型名稱
- 可重現問題的操作步驟
- 已遮蔽敏感資訊的截圖或錯誤文字

> [!CAUTION]
> 請勿在 Issue、截圖或日誌中公開 API Key、Account ID、私人對話或其他敏感資料。
