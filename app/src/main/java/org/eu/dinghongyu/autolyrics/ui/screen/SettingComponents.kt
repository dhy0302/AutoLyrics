package org.eu.dinghongyu.autolyrics.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置页共用的小组件。
 *
 * ## 设计约定（v1.8.0）
 * 分组层级靠三样东西表达，从重到轻：
 *  1. [GroupHeader]  段标题：11sp 大写字距拉开，颜色最暗
 *  2. [SettingCard]  容器：`surfaceContainer` 底 + 22dp 大圆角，整块浮起来
 *  3. 行与行之间用 1px `outlineVariant` 细线分隔（[SettingDivider]），
 *     而不是给每行都套一层卡片 —— 后者正是「一屏五张彩色块」的来源
 *
 * 行内元素统一：标题 14sp / 副标题 11sp（次要色）/ 右侧控件。
 */

/** 段标题：极小字号 + 大字距 + 最暗的次要色，像手账本的分类标签。 */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** 一组设置的容器：大圆角卡片，包住若干行。 */
@Composable
fun SettingCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) { content() }
}

/** 行间分隔线：只画线不画块，是让卡片内部「透气」的关键。 */
@Composable
fun SettingDivider(startIndent: androidx.compose.ui.unit.Dp = 16.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    )
}

/**
 * 开关行。副标题为 null 时不渲染（避免出现空的 11sp 行占位）。
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = ivorySwitchColors(),
        )
    }
}

/**
 * v1.9.0 开关配色：跟随 SaltUI 官方钢蓝强调色。
 *
 * 旧版这里叫 `ivorySwitchColors`、选中态是象牙白 —— v1.8.0 为了"UI 层
 * 不出现彩色"把主色整个去掉了，结果整个设置页没有一个彩色像素，寡淡得
 * 像没做完。现在 primary 是 SaltUI 的 #1478C8，开关的选中态终于有了指向性。
 *
 * 未选中态的处理沿用 v1.8.x 的思路（轨道透明、只留细描边）——这个是对的，
 * SaltUI 自己也这么做（它深色 track 默认就是 subText 10% 的透明叠加）。
 */
@Composable
fun ivorySwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = Color.Transparent,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
)

/**
 * 可点击的「进入某项设置」行——一级目录用它。
 * 右侧一条细chevron，明确表示「点进去还有一层」。
 */
@Composable
fun NavigationRow(
    title: String,
    subtitle: String?,
    icon: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "›",
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/** 滑块行：标题在左、当前值在右，滑块独占下一行（比挤在一行更好读）。 */
@Composable
fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(2.dp))
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
    }
}

/**
 * 一组互斥选项（替代 AssistChip 行）。
 *
 * AssistChip 是 M3 默认那套带描边+圆角胶囊的样式，在深色底上会碎成一地小圆角；
 * 这里改成整行可点、选中行左侧一条 3dp 竖线的高亮，信息更集中。
 */
@Composable
fun OptionGroup(
    options: List<Pair<String, String>>, // label to value
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        options.forEach { (label, value) ->
            val active = value == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(if (active) 18.dp else 0.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
                Spacer(Modifier.width(if (active) 12.dp else 15.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一个只读的说明文本块，用于解释某项设置在做什么。 */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        modifier = modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

/** 颜色快选的一枚色块：选中时外圈加粗描边。 */
@Composable
fun ColorDot(
    argb: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

/** 通用色板（象牙白 / 黑 / 六种预设），歌词页与悬浮窗共用。 */
val QUICK_COLORS: List<Int> = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE8E4DC.toInt(),
    0xFFFF8A80.toInt(), 0xFFFFC46B.toInt(), 0xFF7FD1B0.toInt(), 0xFF8AB4F8.toInt(),
)

/** 一行颜色快选。 */
@Composable
fun QuickColorRow(
    current: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        QUICK_COLORS.forEach { argb ->
            ColorDot(argb = argb, selected = argb == current, onClick = { onPick(argb) })
        }
    }
}
