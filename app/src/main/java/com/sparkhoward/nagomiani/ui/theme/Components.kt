package com.sparkhoward.nagomiani.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext

/** 圆角统一口径（与 mac 版 nagomiCard 的 8-12pt 对应） */
val cardCorner = RoundedCornerShape(12.dp)
val pillCorner = RoundedCornerShape(999.dp)

/** 樱粉胶囊主按钮（NagomiPrimaryButtonStyle） */
@Composable
fun NagomiPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = pillCorner,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = modifier,
    ) {
        Text(text, fontSize = 15.sp)
    }
}

/** 软粉底次按钮（NagomiSecondaryButtonStyle）——OutlinedButton 自带描边，勿再叠 modifier.border（会成双层椭圆） */
@Composable
fun NagomiSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = pillCorner,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier,
    ) {
        Text(text, fontSize = 15.sp)
    }
}

/** 圆角粉描边卡片 */
@Composable
fun NagomiCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface, cardCorner)
            .border(1.dp, MaterialTheme.colorScheme.outline, cardCorner),
    ) { content() }
}

/** 语义徽章（NagomiBadge） */
@Composable
fun NagomiBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), pillCorner)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp)
    }
}

/** 区块标题（NagomiSectionHeader：粉色竖条 + 图标 + 标题） */
@Composable
fun NagomiSectionHeader(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 4.dp, height = 16.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(16.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** 胶囊分段选择器（NagomiSegmented）。minimumInteractiveTargetSize 挂在最外层：
 *  视觉胶囊保持原尺寸，触控区放大到 48dp（自绘组件没有 M3 IconButton 的触控强制） */
@Composable
fun <T> NagomiSegmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    Row(modifier = modifier) {
        options.forEach { option ->
            val isSel = option == selected
            Box(
                modifier = Modifier
                    .minimumTouchTarget()
                    .padding(end = 6.dp)
                    .background(
                        if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        pillCorner,
                    )
                    .border(
                        1.dp,
                        if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        pillCorner,
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    label(option),
                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** 圆形悬浮小按钮（播放器控制图标钮的浅色态） */
@Composable
fun NagomiCircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(42.dp)
            .background(Color.White.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
}

/** 统一封面加载（全 App AsyncImage 出入口）：crossfade 渐显 + 加载/失败淡粉占位。
 *  调用方的 modifier 继续负责尺寸/圆角/底色，图片加载观感在此收口 */
@Composable
fun NagomiAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val placeholder = ColorPainter(NagomiColors.accent.copy(alpha = 0.06f))
    AsyncImage(
        model = ImageRequest.Builder(context).data(model).crossfade(180).build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        modifier = modifier,
    )
}

/** 骨架屏基础块：樱粉底色呼吸闪烁。调用方组合出列表行/海报/文本条的形状 */
@Composable
fun NagomiSkeletonBox(modifier: Modifier = Modifier, corner: Shape = RoundedCornerShape(8.dp)) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(modifier.clip(corner).background(NagomiColors.accent.copy(alpha = alpha)))
}

/** 自绘可点组件的最小触控目标（48dp，Material 无障碍口径）：节点不足 48dp 时放大并居中
 *  内容——视觉不变、只有触控区变大。须放在 background/clickable 之前（最外层）才不影响外观。
 *  foundation 1.7 无同名 API，BOM 升级后可换官方 minimumInteractiveTargetSize */
fun Modifier.minimumTouchTarget(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val target = 48.dp.roundToPx()
    val width = maxOf(placeable.width, target)
    val height = maxOf(placeable.height, target)
    layout(width, height) {
        placeable.placeRelative((width - placeable.width) / 2, (height - placeable.height) / 2)
    }
}
