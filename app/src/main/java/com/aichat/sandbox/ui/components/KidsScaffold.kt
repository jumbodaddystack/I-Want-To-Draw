package com.aichat.sandbox.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.ui.theme.kids.KidsTheme

/**
 * Shared playful chrome for the kid-facing gallery screens (home + notebook).
 *
 * This is the kids-app counterpart to [AppScreenScaffold]: it gives every
 * top-level kid screen the *same* big title + subtitle header, back behaviour
 * and tokenised background, so the screens stop each rolling their own raw
 * `Scaffold` + `Column` header with hardcoded colours. It deliberately keeps the
 * large, friendly header (rather than the slim Material one) because that is the
 * on-brand identity for a 4–10 audience.
 *
 * Must be called inside a [KidsTheme] scope.
 */
@Composable
fun KidsScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (contentPadding: androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    val c = KidsTheme.colors
    val sp = KidsTheme.spacing
    Scaffold(
        modifier = modifier,
        containerColor = c.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding)
                    .padding(horizontal = sp.l, vertical = sp.m)
                    .heightIn(min = KidsTheme.sizing.touchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Go back",
                            tint = c.inkStrong,
                        )
                    }
                    Spacer(Modifier.size(sp.s))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = KidsTheme.type.title,
                        color = c.inkStrong,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = KidsTheme.type.body,
                            color = c.inkMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
            // Content fills the height *below* the header (weighted) so grids and
            // lists don't overflow under it. It gets the horizontal screen inset
            // for free via a uniform PaddingValues so child screens don't
            // re-invent margins.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                content(androidx.compose.foundation.layout.PaddingValues(horizontal = sp.l))
            }
        }
    }
}

/**
 * The one big primary button used across the kid screens. Same colour, shape and
 * size everywhere so "the big button" is learnable. Use [icon] for the leading
 * glyph.
 */
@Composable
fun BigKidButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = KidsTheme.colors
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = KidsTheme.sizing.bigButton),
        shape = KidsTheme.shapes.button,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = c.primary,
            contentColor = c.onPrimary,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(KidsTheme.spacing.s))
        Text(text, style = KidsTheme.type.button)
    }
}
