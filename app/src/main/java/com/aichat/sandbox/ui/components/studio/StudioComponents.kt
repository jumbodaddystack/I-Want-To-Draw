package com.aichat.sandbox.ui.components.studio

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.aichat.sandbox.ui.theme.studio.StudioText
import com.aichat.sandbox.ui.theme.studio.StudioTheme

/**
 * Shared Studio Bench chrome primitives. These encode the identity's
 * departures from stock Material — flat hairline surfaces, all-caps stage
 * markers, the accent reserved for active/primary state, and the "rack focus"
 * accent glow — so screens stay declarative and consistent.
 */

/** All-caps, wide-tracked stage marker. Replaces generic section headers. */
@Composable
fun StudioSectionMarker(
    label: String,
    modifier: Modifier = Modifier,
) {
    StudioText(
        text = label.uppercase(),
        style = StudioTheme.type.section,
        color = StudioTheme.colors.inkMuted,
        modifier = modifier.padding(
            top = StudioTheme.spacing.xl,
            bottom = StudioTheme.spacing.s,
        ),
    )
}

/** Hairline rule — the surface-divider language that replaces card edges. */
@Composable
fun StudioHairline(modifier: Modifier = Modifier, strong: Boolean = false) {
    HorizontalDivider(
        modifier = modifier,
        thickness = if (strong) StudioTheme.sizing.hairlineStrong else StudioTheme.sizing.hairline,
        color = if (strong) StudioTheme.colors.hairlineStrong else StudioTheme.colors.hairline,
    )
}

/**
 * Scope-free hairline for surfaces that aren't wrapped in [StudioTheme] (e.g.
 * the drawing canvas's bottom palette, which is intentionally app-themed).
 * Pass an explicit color from the Studio palette.
 */
@Composable
fun StudioHairlineRaw(
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = color)
}

/**
 * The "live" element — a tool chip or mode toggle. When active it fills with
 * the signature accent (rack-focus glow-in); at rest it's a flat hairline-
 * bordered tile. This is the ONE place the loud color appears in chrome.
 */
@Composable
fun StudioToolChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = StudioTheme.colors
    val reduceMotion = StudioTheme.reduceMotion
    val motion = StudioTheme.motion

    val targetBg = when {
        !enabled -> colors.surfaceRail
        selected -> colors.accentSignature
        else -> colors.surfaceRail
    }
    val bg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(motion.duration(motion.rackFocusMillis, reduceMotion), easing = motion.easeOut),
        label = "toolChipBg",
    )
    val contentColor = when {
        !enabled -> colors.inkFaint
        selected -> colors.onAccent
        else -> colors.inkDefault
    }
    val shape = RoundedCornerShape(StudioTheme.radius.pill)

    Row(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .then(
                if (selected) Modifier
                else Modifier.border(
                    BorderStroke(StudioTheme.sizing.hairline, colors.hairline),
                    shape,
                )
            )
            .heightIn(min = StudioTheme.sizing.touchTarget)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .padding(horizontal = StudioTheme.spacing.m, vertical = StudioTheme.spacing.s)
            .semantics {
                this.selected = selected
                contentDescription = label
            },
        horizontalArrangement = Arrangement.spacedBy(StudioTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        StudioText(text = label, style = StudioTheme.type.label, color = contentColor)
    }
}

/** Primary action — solid accent, sharp-but-distinct. The other place the accent lives. */
@Composable
fun StudioPrimaryAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = StudioTheme.colors
    val shape = RoundedCornerShape(StudioTheme.radius.pill)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) colors.accentSignature else colors.surfaceRaised)
            .heightIn(min = StudioTheme.sizing.touchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = StudioTheme.spacing.l, vertical = StudioTheme.spacing.m)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(StudioTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) colors.onAccent else colors.inkFaint,
            modifier = Modifier.size(20.dp),
        )
        StudioText(
            text = label,
            style = StudioTheme.type.label,
            color = if (enabled) colors.onAccent else colors.inkFaint,
        )
    }
}

/**
 * A flat, hairline-bordered field row — the surface language that replaces
 * shadowed cards. Use for list rows, panels, and inspector groups.
 */
@Composable
fun StudioField(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = StudioTheme.colors
    val shape = RoundedCornerShape(StudioTheme.radius.m)
    val clickMod = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
    } else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRail)
            .border(
                BorderStroke(
                    if (active) StudioTheme.sizing.hairlineStrong else StudioTheme.sizing.hairline,
                    if (active) colors.accentSignature else colors.hairline,
                ),
                shape,
            )
            .then(clickMod)
            .padding(horizontal = StudioTheme.spacing.m, vertical = StudioTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
