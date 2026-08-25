package com.aichat.app.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.relocation.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.*
import com.aichat.app.data.*
import com.aichat.app.ui.theme.Ios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

internal fun Modifier.clippedClickable(shape: Shape, onClick: () -> Unit): Modifier =
    clip(shape).clickable(onClick = onClick)

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.clippedCombinedClickable(
    shape: Shape,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = clip(shape).combinedClickable(onClick = onClick, onLongClick = onLongClick)

@Composable internal fun SelectRow(label: String, selected: Boolean, onClick: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(selected, { onClick() }); Text(label) }

@Composable internal fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit) = Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { onClick() }); Text(label) }

@Composable internal fun ToggleRow(label: String, checked: Boolean, onCheck: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, onCheck, colors = minimalSwitchColors()) }

@Composable internal fun DetailedToggleRow(title: String, detail: String, checked: Boolean, onCheck: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, onCheck, colors = minimalSwitchColors()) }

@Composable internal fun minimalSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = MaterialTheme.colorScheme.tertiary,          // iOS systemGreen
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,  // iOS systemFill
    uncheckedBorderColor = Color.Transparent,
)

@Composable internal fun Back(language: AppLanguage, onClick: () -> Unit) = IconButton(onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, language.pick("返回", "返回"), tint = MaterialTheme.colorScheme.primary) }

@Composable internal fun minimalCardContainerColor(): Color = MaterialTheme.colorScheme.surface

/** iOS translucent bar material (approximation without blur). */
@Composable internal fun iosBarColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Ios.BarLight else Ios.BarDark

/** iOS hairline separator (0.5dp). */
@Composable internal fun Hairline(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(0.5.dp).background(MaterialTheme.colorScheme.outlineVariant))

/** iOS inset-grouped card: flat surface, 11dp continuous-ish corners, no elevation. */
@Composable internal fun IosGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

internal fun manualSummaryModeLabel(mode: ManualSummaryMode, language: AppLanguage): String = when (mode) {
    ManualSummaryMode.UN_SUMMARIZED -> language.pick("壓縮未摘要的較早訊息", "压缩未摘要的较早消息")
    ManualSummaryMode.REBUILD_ALL -> language.pick("重新壓縮全部較早訊息", "重新压缩全部较早消息")
}

@Composable internal fun LoadingOverlay(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text(text) } } }

@Composable internal fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) = Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail) } }

@Composable internal fun DeleteConfirmDialog(title: String, message: String, language: AppLanguage, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(language.pick("刪除", "删除"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(language.pick("取消", "取消")) } },
    )
}


@Composable
internal fun ChatBackground(path: String, darkTheme: Boolean, targetWidthPx: Int, targetHeightPx: Int) {
    val bitmap by produceState<Bitmap?>(null, path, targetWidthPx, targetHeightPx) {
        value = if (path.isBlank() || targetWidthPx <= 0 || targetHeightPx <= 0) {
            null
        } else {
            withContext(Dispatchers.IO) { decodeChatBackground(path, targetWidthPx, targetHeightPx) }
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Box(Modifier.fillMaxSize().background(if (darkTheme) Color.Black.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.54f)))
    }
}

private fun decodeChatBackground(path: String, targetWidthPx: Int, targetHeightPx: Int): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
        val (width, height) = fittedImageSize(info.size.width, info.size.height, targetWidthPx, targetHeightPx)
        if (width != info.size.width || height != info.size.height) decoder.setTargetSize(width, height)
        decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
    }
}.getOrNull()

internal fun fittedImageSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Pair<Int, Int> {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1 to 1
    val scale = min(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight).coerceAtMost(1f)
    return (sourceWidth * scale).roundToInt().coerceAtLeast(1) to
        (sourceHeight * scale).roundToInt().coerceAtLeast(1)
}


@Composable
internal fun ErrorDialog(error: UiError, language: AppLanguage, onDismiss: () -> Unit, onSettings: () -> Unit, onTrim: () -> Unit, onNew: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(14.dp), containerColor = MaterialTheme.colorScheme.surface, title = { Text(error.title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(error.message); Text(error.suggestion, fontWeight = FontWeight.Bold) } }, confirmButton = {
        if (error.kind == ErrorKind.CONTEXT_LENGTH) TextButton(onClick = onTrim) { Text(language.pick("裁切舊訊息並重試", "裁切旧消息并重试")) } else TextButton(onClick = onDismiss) { Text(language.pick("關閉", "关闭")) }
    }, dismissButton = { if (error.kind == ErrorKind.CONTEXT_LENGTH) TextButton(onClick = onNew) { Text(language.pick("建立新對話", "建立新对话")) } else TextButton(onClick = onSettings) { Text(language.pick("前往設定", "前往设置"), color = MaterialTheme.colorScheme.primary) } })
}

/** iOS-style large-title page header with optional count chip and circular add button. */
@Composable
internal fun LargeTitleHeader(
    title: String,
    countText: String? = null,
    onAdd: (() -> Unit)? = null,
    addDescription: String = "新增",
    importAction: (() -> Unit)? = null,
    importDescription: String = "匯入",
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 22.dp, end = 18.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        if (countText != null) {
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(countText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        if (importAction != null) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .clickable(onClick = importAction),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.UploadFile, importDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        if (onAdd != null) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, addDescription, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}

private val AVATAR_EMOJIS = listOf(
    "🧝", "🤖", "🌸", "🦊", "🐉", "🌙", "⭐", "🔥",
    "🌊", "🍀", "🎭", "👑", "🧠", "🎯", "🚀", "🐰",
    "🦉", "🎨", "⚔️", "🛡️", "🧙", "🐺", "🌻", "💫",
)

internal fun avatarEmoji(seed: String): String =
    AVATAR_EMOJIS[Math.floorMod(seed.hashCode(), AVATAR_EMOJIS.size)]

/** Rounded icon tile used on list cards (52dp, subtle fill, accent icon). */
@Composable
internal fun IconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
    }
}

/** Dim letterspaced section title with optional trailing badge. */
@Composable
internal fun SectionTitle(text: String, badge: String? = null) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            Modifier.weight(1f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.5.sp,
        )
        badge?.let {
            Box(
                Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Rounded radio-select card. */
@Composable
internal fun RadioCard(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(
                        2.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        CircleShape,
                    )
            )
            if (selected) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Rounded checkbox-select card. */
@Composable
internal fun CheckCard(checked: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(
                    2.dp,
                    if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Gradient avatar with emoji or monogram; color derived from `seed` (unique id). */
@Composable
internal fun AvatarCircle(
    text: String,
    seed: String = text,
    size: Dp = 56.dp,
    modifier: Modifier = Modifier,
    emoji: String? = null,
) {
    val hue = remember(seed) { Math.floorMod(seed.hashCode(), 360).toFloat() }
    val c1 = Color.hsv(hue, 0.52f, 0.95f)
    val c2 = Color.hsv((hue + 42f) % 360f, 0.62f, 0.72f)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(c1, c2)))
            .border(2.dp, Color.White.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (emoji != null) {
            Text(emoji, fontSize = (size.value * 0.52f).sp)
        } else {
            Text(
                text.trim().take(1).ifBlank { "?" }.uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.4f).sp,
            )
        }
    }
}

/** "今天 14:32 / 昨天 22:10 / 3 天前 / 5月8日" relative time label. */
internal fun relativeTimeLabel(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val zone = java.time.ZoneId.systemDefault()
    val dateTime = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone)
    val diff = java.time.temporal.ChronoUnit.DAYS.between(dateTime.toLocalDate(), java.time.LocalDate.now(zone))
    val hhmm = dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return when {
        diff <= 0L -> "今天 $hhmm"
        diff == 1L -> "昨天 $hhmm"
        diff < 7L -> "$diff 天前"
        else -> "${dateTime.monthValue}月${dateTime.dayOfMonth}日"
    }
}
