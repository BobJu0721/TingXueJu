package com.aichat.app

import com.aichat.app.data.AppLanguage

fun AppLanguage.pick(traditional: String, simplified: String): String =
    if (this == AppLanguage.SIMPLIFIED_CHINESE) simplified else traditional

data class PromptLabels(
    val continueConversation: String,
    val aiCharacter: String,
    val persona: String,
    val worldHits: String,
    val olderSummary: String,
    val summary: String,
    val personality: String,
    val background: String,
    val exampleDialogue: String,
    val greetingReference: String,
    val extraInstructions: String,
)

fun AppLanguage.promptLabels(): PromptLabels =
    if (this == AppLanguage.SIMPLIFIED_CHINESE) {
        PromptLabels(
            continueConversation = "请自然地延续对话。以下资料是本次对话可使用的背景设定。",
            aiCharacter = "AI 扮演的角色",
            persona = "用户身份 Persona",
            worldHits = "本次命中的世界设定",
            olderSummary = "较早对话摘要",
            summary = "简介",
            personality = "个性",
            background = "背景",
            exampleDialogue = "范例对话",
            greetingReference = "开场白参考",
            extraInstructions = "额外指示",
        )
    } else {
        PromptLabels(
            continueConversation = "請自然地延續對話。以下資料是本次對話可使用的背景設定。",
            aiCharacter = "AI 扮演的角色",
            persona = "使用者身份 Persona",
            worldHits = "本次命中的世界設定",
            olderSummary = "較早對話摘要",
            summary = "簡介",
            personality = "個性",
            background = "背景",
            exampleDialogue = "範例對話",
            greetingReference = "開場白參考",
            extraInstructions = "額外指示",
        )
    }

fun AppLanguage.organizeProfileInstruction(): String =
    pick("整理其中與單一身份有關的資訊，保留重要細節。", "整理其中与单一身份有关的信息，保留重要细节。")

fun AppLanguage.organizeWorldInstruction(): String =
    pick("整理其中的世界觀、地點、人物關係與規則，保留可用細節。", "整理其中的世界观、地点、人物关系与规则，保留可用细节。")

fun AppLanguage.profileJsonInstruction(label: String): String =
    pick(
        "將文件整理成一份$label。只回傳 JSON，不要 markdown。欄位固定為 name, summary, personality, background, exampleDialogue, greeting, alternateGreetings, extraInstructions。alternateGreetings 必須是字串陣列。",
        "将文件整理成一份$label。只返回 JSON，不要 markdown。字段固定为 name, summary, personality, background, exampleDialogue, greeting, alternateGreetings, extraInstructions。alternateGreetings 必须是字符串数组。",
    )

fun AppLanguage.worldJsonInstruction(): String =
    pick(
        "將文件拆成可用關鍵詞觸發的世界設定條目。只回傳 JSON，不要 markdown。根物件欄位為 name 與 entries；每個 entry 欄位固定為 title, keywords, content, alwaysInclude。keywords 必須是字串陣列；只有每次都必須知道的核心規則才設 alwaysInclude=true。",
        "将文件拆成可用关键词触发的世界设定条目。只返回 JSON，不要 markdown。根对象字段为 name 与 entries；每个 entry 字段固定为 title, keywords, content, alwaysInclude。keywords 必须是字符串数组；只有每次都必须知道的核心规则才设 alwaysInclude=true。",
    )

fun AppLanguage.chunkInstruction(instruction: String, index: Int, total: Int): String =
    pick(
        "$instruction 這是第 ${index + 1}/$total 段。請輸出精簡但完整的純文字筆記。",
        "$instruction 这是第 ${index + 1}/$total 段。请输出精简但完整的纯文字笔记。",
    )

fun AppLanguage.summarizeSystemInstruction(): String =
    pick(
        "將角色扮演對話濃縮成精準的繁體中文前情摘要。保留人物、關係、承諾、事件、位置與尚未解決的事項。只輸出摘要。",
        "将角色扮演对话浓缩成精准的简体中文前情摘要。保留人物、关系、承诺、事件、位置与尚未解决的事项。只输出摘要。",
    )

fun AppLanguage.mergeSummaryInstruction(): String =
    pick(
        "合併以下分段摘要，移除重複內容但保留關鍵細節。只輸出合併後摘要。",
        "合并以下分段摘要，移除重复内容但保留关键细节。只输出合并后摘要。",
    )
