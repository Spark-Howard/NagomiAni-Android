package com.sparkhoward.nagomiani.player

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sparkhoward.nagomiani.ui.theme.NagomiColors
import com.sparkhoward.nagomiani.ui.theme.NagomiPrimaryButton
import com.sparkhoward.nagomiani.ui.theme.NagomiSecondaryButton
import kotlinx.coroutines.delay

/** 半透明面板上使用的浅色文字（面板底色为深色半透明，主题色在此不可读；与弹幕设置弹层一致） */
private val LabelColor = Color.White.copy(alpha = 0.65f)

/**
 * 截图分享弹层（非模态底部覆盖层，从底部滑出，样式与弹幕设置一致）：
 * 样式分段（分享卡/纯净截图）+ 带弹幕开关 + 配文（仅分享卡）+ 保存到相册/分享。
 * 样式与配文变化 250ms 防抖后由 ViewModel 异步重合成输出图；预览始终展示最近一次结果。
 */
@Composable
fun ShareCardSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val capture = viewModel.shareCapture ?: return
    val card = viewModel.shareCard
    val busy = viewModel.shareBusy

    var style by remember { mutableStateOf(ShareCardStyle.SAKURA) }
    var withDanmaku by remember { mutableStateOf(true) }
    var caption by remember { mutableStateOf("") }
    var firstCompose by remember { mutableStateOf(true) }

    // 初次打开立即出图；后续变化防抖重合成（合成约几十毫秒，防抖只为省电省 CPU）
    LaunchedEffect(capture, style, withDanmaku, caption) {
        if (!firstCompose) delay(250)
        firstCompose = false
        viewModel.composeShareCard(style, withDanmaku, caption)
    }

    // API 26-28 保存相册需要存储权限（29+ 走 MediaStore 免权限）
    val context = androidx.compose.ui.platform.LocalContext.current
    var storageGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        storageGranted = granted
        if (granted) viewModel.saveCardToGallery() else viewModel.showStatus("未授予存储权限，无法保存")
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xD9141014), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // 标题行 + 完成键
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("截图分享", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "完成",
                color = NagomiColors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp),
            )
        }

        // 预览（输出图未就绪时显示生成指示）
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            val bmp = card
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "分享图预览",
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .aspectRatio(bmp.width.toFloat() / bmp.height)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = NagomiColors.accent, modifier = Modifier.size(32.dp))
                }
            }
        }

        // 样式分段（深色面板配色，与弹幕设置弹层同视觉语言）
        Row(Modifier.padding(top = 12.dp)) {
            ShareCardStyle.entries.forEach { option ->
                val isSel = option == style
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .background(
                            if (isSel) NagomiColors.accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(999.dp),
                        )
                        .border(
                            1.dp,
                            if (isSel) NagomiColors.accent else Color.White.copy(alpha = 0.25f),
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { style = option }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        if (option == ShareCardStyle.SAKURA) "分享卡" else "纯净截图",
                        color = if (isSel) NagomiColors.accent else Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // 带弹幕开关（两种样式通用）
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("带弹幕", fontSize = 11.sp, color = LabelColor)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = withDanmaku,
                onCheckedChange = { withDanmaku = it },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = NagomiColors.accent,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.25f),
                    uncheckedThumbColor = Color.White,
                ),
                modifier = Modifier.size(40.dp),
            )
        }

        // 配文（仅分享卡）
        if (style == ShareCardStyle.SAKURA) {
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                placeholder = { Text("写点什么…（可选）", fontSize = 13.sp, color = LabelColor) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NagomiColors.accent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                    cursorColor = NagomiColors.accent,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        // 保存 / 分享
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NagomiSecondaryButton(
                if (viewModel.shareSaving) "保存中…" else "保存到相册",
                modifier = Modifier.weight(1f),
                enabled = card != null && !busy && !viewModel.shareSaving,
            ) {
                if (storageGranted) viewModel.saveCardToGallery()
                else permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            NagomiPrimaryButton(
                "分享",
                modifier = Modifier.weight(1f),
                enabled = card != null && !busy,
            ) {
                val uri = viewModel.shareCardUri()
                if (uri != null) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        // ClipData 兜底：部分机型的 chooser 不透传 EXTRA_STREAM 的读授权
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "分享截图"))
                } else {
                    viewModel.showStatus("分享图还没生成好，稍等一下")
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}
