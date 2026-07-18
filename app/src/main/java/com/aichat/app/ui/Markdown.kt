package com.aichat.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.relocation.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.*
import com.aichat.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.roundToInt
internal fun LazyListState.isNearBottom(lastIndex: Int, thresholdPx: Int = 96): Boolean {
    if (lastIndex < 0) return true
    val visibleLast = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    if (visibleLast.index < lastIndex) return false
    val itemBottom = visibleLast.offset + visibleLast.size
    return itemBottom <= layoutInfo.viewportEndOffset + thresholdPx
}


@Composable
internal fun MarkdownText(markdown: String) {
    val blocks = remember(markdown) {
        markdown.split("```").mapIndexedNotNull { index, block ->
            if (index % 2 == 1) true to AnnotatedString(block.substringAfter('\n', block).trimEnd())
            else block.takeIf(String::isNotBlank)?.let { false to inlineMarkdown(it.trim()) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { blocks.forEach { (isCode, block) ->
        if (isCode) Surface(color = MaterialTheme.colorScheme.inverseSurface) { Text(block, Modifier.fillMaxWidth().padding(10.dp), color = MaterialTheme.colorScheme.inverseOnSurface, fontFamily = FontFamily.Monospace) }
        else Text(block)
    } }
}


private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) when {
        text.startsWith("**", index) -> { val end = text.indexOf("**", index + 2); if (end > index) { pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(text.substring(index + 2, end)); pop(); index = end + 2 } else { append("**"); index += 2 } }
        text[index] == '`' -> { val end = text.indexOf('`', index + 1); if (end > index) { pushStyle(SpanStyle(fontFamily = FontFamily.Monospace)); append(text.substring(index + 1, end)); pop(); index = end + 1 } else { append('`'); index++ } }
        else -> { append(text[index]); index++ }
    }
}
