package com.sparkhoward.nagomiani.ui.theme

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** 胶囊分段选择器（NagomiSegmented） */
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
