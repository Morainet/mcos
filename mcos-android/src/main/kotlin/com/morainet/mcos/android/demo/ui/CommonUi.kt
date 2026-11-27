package com.morainet.mcos.android.demo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morainet.mcos.android.demo.McosColor
import com.morainet.mcos.android.demo.McosRadius
import com.morainet.mcos.android.demo.McosSpace

/**
 * Shared page-level UI for the shell's secondary pages (MCP, Skills, …) —
 * the same visual language as the chat page: an icon badge + large title +
 * one-line description, quiet filled fields, white bordered cards.
 */

/** Rounded tinted square holding one icon — the page/card identity mark. */
@Composable
fun IconBadge(
    icon: ImageVector,
    tint: Color,
    size: Int = 40,
    iconSize: Int = 21,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size.dp)
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(McosRadius.md)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize.dp))
    }
}

/** Page header: icon badge + title + description, mirroring the chat greeting. */
@Composable
fun PageHeader(
    icon: ImageVector,
    tint: Color,
    title: String,
    description: String,
) {
    Row(Modifier.fillMaxWidth().padding(top = McosSpace.xl, bottom = McosSpace.xl)) {
        IconBadge(icon = icon, tint = tint, size = 44, iconSize = 23)
        Spacer(Modifier.width(McosSpace.xl))
        Column(Modifier.align(Alignment.CenterVertically)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = McosColor.fgMuted,
            )
        }
    }
}

/** Section label inside a page ("ADD SERVER", "IMPORT" …). */
@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.SemiBold,
        color = McosColor.fgDim,
        modifier = Modifier.padding(bottom = McosSpace.md),
    )
}

/**
 * Quiet filled field — surfaceAlt fill, no border (the composer's treatment).
 * Secondary pages use this instead of outlined fields so the whole shell
 * shares one input language.
 */
@Composable
fun QuietField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    mono: Boolean = false,
    minLinesHeight: Int? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions =
        androidx.compose.foundation.text.KeyboardActions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.let {
            if (minLinesHeight != null) it.heightIn(min = minLinesHeight.dp) else it
        },
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            fontSize = 13.sp,
            fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        ),
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = McosColor.fgDim),
            )
        },
        shape = RoundedCornerShape(McosRadius.md),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = McosColor.surfaceAlt,
            unfocusedContainerColor = McosColor.surfaceAlt,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}


/** Small status dot + text pair (connection state etc.). */
@Composable
fun StatusDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(McosSpace.md))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = McosColor.fgMuted,
        )
    }
}
