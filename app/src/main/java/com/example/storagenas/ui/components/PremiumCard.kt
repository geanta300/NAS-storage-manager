package com.example.storagenas.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = containerColor,
        ),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.outlinedCardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 1.dp,
            focusedElevation = 3.dp,
            hoveredElevation = 3.dp,
        ),
        shape = MaterialTheme.shapes.large,
        content = {
            Column(
                modifier = Modifier.padding(18.dp),
                content = content,
            )
        },
    )
}
