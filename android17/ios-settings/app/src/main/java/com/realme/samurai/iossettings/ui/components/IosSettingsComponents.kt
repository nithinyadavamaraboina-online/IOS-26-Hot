package com.realme.samurai.iossettings.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realme.samurai.iossettings.ui.glass.GlassStyle
import com.realme.samurai.iossettings.ui.glass.LocalBackdrop
import com.realme.samurai.iossettings.ui.glass.backdropSource
import com.realme.samurai.iossettings.ui.glass.liquidGlass
import com.realme.samurai.iossettings.ui.glass.rememberBackdrop
import com.realme.samurai.iossettings.ui.icons.AccountDottedHaloAvatar
import com.realme.samurai.iossettings.ui.icons.CheckmarkIcon
import com.realme.samurai.iossettings.ui.icons.ChevronLeftIcon
import com.realme.samurai.iossettings.ui.icons.ChevronRightIcon
import com.realme.samurai.iossettings.ui.icons.FolderOutlineIcon
import com.realme.samurai.iossettings.ui.icons.MagnifyingGlassIcon
import com.realme.samurai.iossettings.ui.icons.MicrophoneIcon
import com.realme.samurai.iossettings.ui.icons.ReplyOutlineIcon
import com.realme.samurai.iossettings.ui.icons.SettingsIcon
import com.realme.samurai.iossettings.ui.icons.SettingsIconBadge
import com.realme.samurai.iossettings.ui.icons.TrashOutlineIcon
import com.realme.samurai.iossettings.ui.theme.ContinuousSquircleShape
import com.realme.samurai.iossettings.ui.theme.GroupCardShape
import com.realme.samurai.iossettings.ui.theme.IosTheme
import com.realme.samurai.iossettings.ui.theme.IosType
import kotlin.math.roundToInt

/**
 * Exact iOS 26 `UISwitch` measured from `hot0151_settings.png` and `wave5_03e_wifi.png`:
 * - Track: `63.dp × 28.dp` full capsule (`#34C759` / `#30D158` ON, `#C6C6CC` / `#39393D` OFF)
 * - Thumb: `37.dp × 24.dp` oblong white capsule (`2.dp` inset -> `x = 2.dp` OFF, `x = 24.dp` ON)
 */
@Composable
fun Ios26Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = IosTheme.colors
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.switchOn else colors.switchOff,
        animationSpec = tween(durationMillis = 190),
        label = "ios26SwitchTrack",
    )
    val stretch by animateDpAsState(
        targetValue = if (pressed) 3.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
        label = "ios26SwitchStretch",
    )
    val thumbWidth = 37.dp + stretch
    val thumbX by animateDpAsState(
        targetValue = if (checked) 63.dp - 2.dp - thumbWidth else 2.dp,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "ios26SwitchThumbX",
    )

    Box(
        modifier = modifier
            .size(width = 63.dp, height = 28.dp)
            .clip(CircleShape)
            .background(if (enabled) trackColor else trackColor.copy(alpha = 0.45f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = {
                    runCatching { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                    onCheckedChange(!checked)
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbX, y = 2.dp)
                .size(width = thumbWidth, height = 24.dp)
                .shadow(
                    elevation = 2.5.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x26000000),
                    spotColor = Color(0x36000000),
                )
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * Inset grouped section card (`16.dp` margin, `26.dp` continuous squircle, solid opaque `#FFFFFF` / `#1C1C1E`).
 * Never applies blur or translucency to content rows.
 */
@Composable
fun IosGroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = IosTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        if (!header.isNullOrEmpty()) {
            Text(
                text = header,
                style = IosType.sectionHeader,
                color = colors.secondaryText,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp),
            )
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(GroupCardShape)
                .background(colors.groupedCell),
            content = content,
        )
        if (!footer.isNullOrEmpty()) {
            Text(
                text = footer,
                style = IosType.footnote,
                color = colors.secondaryText,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
            )
        }
    }
}

/**
 * Exact `52.dp` iOS 26 Settings row cell with `29.dp` squircle icon tile, hairline `0.5.dp` divider
 * aligned to the text leading edge (`59.dp` with icon, `52.dp` with checkmark gutter, `16.dp` plain),
 * and right accessory (`value`, `Ios26Switch`, `✓`, or `›` chevron).
 */
@Composable
fun IosSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: SettingsIcon? = null,
    customLeading: (@Composable () -> Unit)? = null,
    leadingCheckmarkGutter: Boolean = false,
    leadingChecked: Boolean = false,
    subtitle: String? = null,
    value: String? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    selectedCheckmark: Boolean = false,
    showChevron: Boolean = false,
    titleColor: Color = IosTheme.colors.primaryText,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = IosTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val rowClickable = onClick != null
    val pressHighlight = if (pressed && rowClickable) {
        if (colors.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
    } else {
        Color.Transparent
    }

    val dividerStart = when {
        icon != null || customLeading != null -> 59.dp
        leadingCheckmarkGutter -> 52.dp
        else -> 16.dp
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(pressHighlight)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (subtitle != null) 9.dp else 0.dp,
                    bottom = if (subtitle != null) 9.dp else 0.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                icon != null -> {
                    SettingsIconBadge(icon = icon, size = 29.dp)
                    Spacer(Modifier.width(14.dp))
                }
                customLeading != null -> {
                    customLeading()
                    Spacer(Modifier.width(14.dp))
                }
                leadingCheckmarkGutter -> {
                    Box(
                        modifier = Modifier.width(36.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (leadingChecked) {
                            CheckmarkIcon(color = colors.accent, size = 15.dp)
                        }
                    }
                }
            }

            val hasRightValueOnly = !value.isNullOrEmpty() && subtitle.isNullOrEmpty() && trailing == null && checked == null

            Column(
                modifier = if (hasRightValueOnly) {
                    Modifier
                } else {
                    Modifier.weight(1f)
                },
            ) {
                Text(
                    text = title,
                    style = IosType.body,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = subtitle,
                        style = IosType.footnote,
                        color = colors.secondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (hasRightValueOnly) {
                Spacer(Modifier.width(14.dp))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = value.orEmpty(),
                            style = IosType.body,
                            color = colors.secondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (showChevron || selectedCheckmark) {
                            Spacer(Modifier.width(8.dp))
                        }
                        if (selectedCheckmark) {
                            CheckmarkIcon(color = colors.accent, size = 15.dp)
                        }
                        if (showChevron) {
                            ChevronRightIcon(color = colors.tertiaryText)
                        }
                    }
                }
            } else {
                Spacer(Modifier.width(10.dp))
                when {
                    trailing != null -> trailing()
                    checked != null && onCheckedChange != null -> {
                        Ios26Switch(checked = checked, onCheckedChange = onCheckedChange)
                    }
                    else -> {
                        if (!value.isNullOrEmpty()) {
                            Text(
                                text = value,
                                style = IosType.body,
                                color = colors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (showChevron || selectedCheckmark) {
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        if (selectedCheckmark) {
                            CheckmarkIcon(color = colors.accent, size = 15.dp)
                        }
                        if (showChevron) {
                            ChevronRightIcon(color = colors.tertiaryText)
                        }
                    }
                }
            }
        }

        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = dividerStart, end = 16.dp)
                    .height(0.5.dp)
                    .background(colors.divider),
            )
        }
    }
}

/**
 * Exact top Account Card from `hot0151_settings.png`.
 */
@Composable
fun IosAccountCard(
    title: String,
    subtitle: String,
    signedInInitial: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IosTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(GroupCardShape)
            .background(
                if (pressed) {
                    if (colors.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)
                } else {
                    colors.groupedCell
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountDottedHaloAvatar(size = 62.dp, signedInInitial = signedInInitial)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = IosType.title3.copy(fontWeight = FontWeight.Bold),
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = IosType.footnote.copy(fontSize = 13.5.sp, lineHeight = 18.sp),
                color = colors.secondaryText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        ChevronRightIcon(color = colors.tertiaryText)
    }
}

/**
 * Exact sub-page top Hero Placard Card from `wave5_03e_wifi.png`:
 * `60.dp` squircle icon at top-left, `22.sp` Bold title, `15.sp` description + `#007AFF` `"Learn more..."`,
 * and optional embedded master toggle row (`52.dp`).
 */
@Composable
fun IosHeroPlacardCard(
    icon: SettingsIcon,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    learnMoreText: String = "Learn more...",
    onLearnMoreClick: (() -> Unit)? = null,
    switchLabel: String? = null,
    switchChecked: Boolean? = null,
    onSwitchChange: ((Boolean) -> Unit)? = null,
) {
    val colors = IosTheme.colors
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(GroupCardShape)
            .background(colors.groupedCell),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = 20.dp,
                    bottom = if (switchLabel != null) 16.dp else 20.dp,
                ),
        ) {
            SettingsIconBadge(icon = icon, size = 60.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = IosType.title2,
                color = colors.primaryText,
            )
            Spacer(Modifier.height(4.dp))
            val annotated = buildAnnotatedString {
                withStyle(SpanStyle(color = colors.secondaryText)) {
                    append(description)
                    if (learnMoreText.isNotEmpty()) append(" ")
                }
                if (learnMoreText.isNotEmpty()) {
                    withStyle(SpanStyle(color = colors.accent)) {
                        append(learnMoreText)
                    }
                }
            }
            Text(
                text = annotated,
                style = IosType.subheadline,
                modifier = if (onLearnMoreClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLearnMoreClick,
                    )
                } else {
                    Modifier
                },
            )
        }

        if (switchLabel != null && switchChecked != null && onSwitchChange != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(0.5.dp)
                    .background(colors.divider),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(start = 20.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = switchLabel,
                    style = IosType.body,
                    color = colors.primaryText,
                    modifier = Modifier.weight(1f),
                )
                Ios26Switch(checked = switchChecked, onCheckedChange = onSwitchChange)
            }
        }
    }
}

/**
 * Floating `44.dp` circular `<` Back droplet (`ios26_08_ios261_clear_vs_tinted.png`).
 */
@Composable
fun GlassBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IosTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "backPress",
    )

    Box(
        modifier = modifier
            .size(44.dp)
            .shadow(
                elevation = 5.dp,
                shape = CircleShape,
                ambientColor = Color(0x16000000),
                spotColor = Color(0x20000000),
            )
            .clip(CircleShape)
            .liquidGlass(
                shape = CircleShape,
                style = GlassStyle(
                    blur = 6.dp,
                    refractionHeight = 14.dp,
                    refractionAmount = 16.dp,
                    dispersion = 0.10f,
                ),
                pressProgress = pressProgress,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ChevronLeftIcon(
            color = colors.primaryText,
            modifier = Modifier.offset(x = (-1).dp),
        )
    }
}

/**
 * Standard iOS 26 sub-page scaffold with solid `#F2F2F7`/`#000000` canvas, `Modifier.backdropSource`
 * on the scrolling `LazyColumn`, and floating Liquid Glass `<` Back droplet + centered navigation title.
 */
@Composable
fun IosSubPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    alwaysShowTitle: Boolean = false,
    showBackButton: Boolean = true,
    trailingAction: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val colors = IosTheme.colors
    val backdrop = rememberBackdrop()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val showCollapsedTitle by remember(alwaysShowTitle) {
        derivedStateOf {
            alwaysShowTitle || listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 90
        }
    }

    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.groupedBackground),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .backdropSource(backdrop),
                contentPadding = PaddingValues(
                    top = statusTop + if (showBackButton || alwaysShowTitle) 62.dp else 18.dp,
                    bottom = navBottom + 36.dp,
                ),
                content = content,
            )

            // Top floating navigation bar with progressive top scrim when scrolled
            val isScrolled by remember {
                derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 12 }
            }
            if (isScrolled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(statusTop + 62.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    colors.groupedBackground,
                                    colors.groupedBackground.copy(alpha = 0.90f),
                                    colors.groupedBackground.copy(alpha = 0f),
                                )
                            )
                        ),
                )
            }
            if (showBackButton || alwaysShowTitle || trailingAction != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = statusTop + 6.dp, start = 16.dp, end = 16.dp)
                        .height(48.dp),
                ) {
                    if (showBackButton) {
                        GlassBackButton(
                            onClick = onBack,
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                    }
                    AnimatedVisibility(
                        visible = showCollapsedTitle,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(120)),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Text(
                            text = title,
                            style = IosType.headline,
                            color = colors.primaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (trailingAction != null) {
                        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                            trailingAction()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Exact bottom floating Liquid Glass Search bar from `hot0151_settings.png`:
 * - Resting: `46.dp` capsule pill (`22.dp` horizontal margin), `MagnifyingGlassIcon` (`primaryText`)
 *   + `"Search"` (`secondaryText`) + `MicrophoneIcon` (`primaryText`), refracting scrolling rows underneath!
 * - Focused/Active: smooth spring expansion + `"Cancel"` button.
 */
@Composable
fun BottomFloatingSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IosTheme.colors
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val horizPad by animateDpAsState(
        targetValue = if (focused || query.isNotEmpty()) 16.dp else 22.dp,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 460f),
        label = "searchBarPad",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x14000000),
                    spotColor = Color(0x1E000000),
                )
                .clip(CircleShape)
                .liquidGlass(
                    shape = CircleShape,
                    style = GlassStyle(
                        blur = 5.dp,
                        refractionHeight = 16.dp,
                        refractionAmount = 22.dp,
                        dispersion = 0.14f,
                        saturation = 1.45f,
                    ),
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MagnifyingGlassIcon(color = colors.primaryText, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search",
                        style = IosType.body,
                        color = colors.secondaryText,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = IosType.body.copy(color = colors.primaryText),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChange(it.isFocused) },
                )
            }
            Spacer(Modifier.width(8.dp))
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(colors.tertiaryText)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "×",
                        color = colors.groupedCell,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                MicrophoneIcon(color = colors.primaryText, size = 19.dp)
            }
        }

        AnimatedVisibility(visible = focused || query.isNotEmpty()) {
            Text(
                text = "Cancel",
                style = IosType.body,
                color = colors.accent,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onQueryChange("")
                        onFocusChange(false)
                        focusManager.clearFocus()
                    },
            )
        }
    }
}

/**
 * Smooth iOS 26 horizontal slider row (`52.dp` height, `#007AFF` active fill, `28.dp` shadowed white knob).
 */
@Composable
fun IosSliderRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    showDivider: Boolean = false,
) {
    val colors = IosTheme.colors
    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    val clamped = value.coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(12.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onValueChange((offset.x / trackWidthPx).coerceIn(0f, 1f))
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            onValueChange((change.position.x / trackWidthPx).coerceIn(0f, 1f))
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                // Inactive track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(if (colors.isDark) Color(0xFF39393D) else Color(0xFFE5E5EA)),
                )
                // Active `#007AFF` track
                Box(
                    modifier = Modifier
                        .fillMaxWidth(clamped)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                )
                // White oblong iOS 26 slider thumb (`30dp × 22dp`)
                Box(
                    modifier = Modifier
                        .offset {
                            val usable = (trackWidthPx - 30.dp.toPx()).coerceAtLeast(0f)
                            IntOffset((usable * clamped).roundToInt(), 0)
                        }
                        .size(width = 30.dp, height = 22.dp)
                        .shadow(3.dp, CircleShape, ambientColor = Color(0x28000000), spotColor = Color(0x36000000))
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }

            if (trailingIcon != null) {
                Spacer(Modifier.width(12.dp))
                trailingIcon()
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.5.dp)
                    .background(colors.divider),
            )
        }
    }
}

/**
 * Exact iOS 26.1 `Liquid Glass` preview card (`ios26_08_ios261_clear_vs_tinted.png`):
 * - Mint-cyan, ice-white, cobalt, and deep-sapphire glass wave canvas recorded into a local `Backdrop`
 * - Centered floating `208.dp × 58.dp` Liquid Glass toolbar pill refracting the waves behind it
 *   with `TrashOutlineIcon`, `FolderOutlineIcon`, and `ReplyOutlineIcon`!
 */
@Composable
fun LiquidGlassPreviewCard(
    isTinted: Boolean,
    modifier: Modifier = Modifier,
) {
    val previewBackdrop = rememberBackdrop()
    val cardShape = ContinuousSquircleShape(24.dp)

    CompositionLocalProvider(LocalBackdrop provides previewBackdrop) {
        Box(
            modifier = modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(194.dp)
                .clip(cardShape),
            contentAlignment = Alignment.Center,
        ) {
            // Mint-cyan & cobalt layered glass wave artwork matching ios26_08_ios261_clear_vs_tinted.png
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .backdropSource(previewBackdrop),
            ) {
                val w = size.width
                val h = size.height
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFFD9F3F4), Color(0xFFEAF3FA), Color(0xFFB4D3F8)),
                        start = Offset.Zero,
                        end = Offset(w, h),
                    )
                )
                // Deep sapphire right petal
                val rightWave = Path().apply {
                    moveTo(w * 0.58f, 0f)
                    cubicTo(w * 0.85f, h * 0.08f, w * 0.96f, h * 0.42f, w, h * 0.72f)
                    lineTo(w, 0f)
                    close()
                }
                drawPath(
                    rightWave,
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF0032A8), Color(0xFF0A4FD6)),
                        start = Offset(w * 0.65f, 0f),
                        end = Offset(w, h * 0.7f),
                    ),
                )
                // Cobalt mid ribbon
                val midRibbon = Path().apply {
                    moveTo(w * 0.34f, 0f)
                    cubicTo(w * 0.62f, h * 0.14f, w * 0.78f, h * 0.48f, w * 0.68f, h)
                    lineTo(w * 0.90f, h)
                    cubicTo(w * 0.94f, h * 0.55f, w * 0.84f, h * 0.14f, w * 0.64f, 0f)
                    close()
                }
                drawPath(
                    midRibbon,
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF6BA0F5), Color(0xFF2865DF), Color(0xFF9BC2FA)),
                        start = Offset(w * 0.35f, 0f),
                        end = Offset(w * 0.85f, h),
                    ),
                )
                // Mint-turquoise foreground wave (left -> bottom-center)
                val mintWave = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(w * 0.24f, 0f)
                    cubicTo(w * 0.50f, h * 0.26f, w * 0.64f, h * 0.64f, w * 0.72f, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    mintWave,
                    Brush.linearGradient(
                        colors = listOf(Color(0xFFA0E9E0), Color(0xFF7ADACA), Color(0xFF43C6BA)),
                        start = Offset(0f, 0f),
                        end = Offset(w * 0.7f, h),
                    ),
                )
            }

            // Floating Liquid Glass toolbar pill
            Row(
                modifier = Modifier
                    .size(width = 212.dp, height = 58.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = CircleShape,
                        ambientColor = Color(0x1A000000),
                        spotColor = Color(0x24000000),
                    )
                    .clip(CircleShape)
                    .liquidGlass(
                        shape = CircleShape,
                        style = GlassStyle(
                            blur = if (isTinted) 14.dp else 4.dp,
                            refractionHeight = 18.dp,
                            refractionAmount = if (isTinted) 10.dp else 26.dp,
                            dispersion = if (isTinted) 0.06f else 0.20f,
                            saturation = 1.6f,
                            tint = if (isTinted) Color(0xE0F4F8FA) else Color(0x66FFFFFF),
                            ignoreSystemScale = true,
                        ),
                        forceTinted = isTinted,
                    )
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val glyphColor = Color(0xFF0C1B2A)
                TrashOutlineIcon(color = glyphColor)
                FolderOutlineIcon(color = glyphColor)
                ReplyOutlineIcon(color = glyphColor)
            }
        }
    }
}

/**
 * Exact iOS `Display & Brightness` Light vs Dark visual phone-thumbnail selector.
 */
@Composable
fun DisplayAppearanceSelectorRow(
    isDarkSelected: Boolean,
    onSelectDark: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = IosTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp, horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        AppearancePhoneOption(
            label = "Light",
            darkPreview = false,
            selected = !isDarkSelected,
            onClick = { onSelectDark(false) },
        )
        AppearancePhoneOption(
            label = "Dark",
            darkPreview = true,
            selected = isDarkSelected,
            onClick = { onSelectDark(true) },
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(0.5.dp)
            .background(colors.divider),
    )
}

@Composable
private fun AppearancePhoneOption(
    label: String,
    darkPreview: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = IosTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        Canvas(
            modifier = Modifier
                .size(width = 68.dp, height = 132.dp)
                .clip(ContinuousSquircleShape(14.dp)),
        ) {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.verticalGradient(
                    colors = if (darkPreview) {
                        listOf(Color(0xFF0F2042), Color(0xFF060B18))
                    } else {
                        listOf(Color(0xFF7BC6FF), Color(0xFFD6EEFF))
                    }
                )
            )
            // Simulated clock & glass cards inside mini phone
            val cardColor = if (darkPreview) Color(0xCC1C1C1E) else Color(0xE6FFFFFF)
            drawRoundRect(
                color = cardColor,
                topLeft = Offset(w * 0.14f, h * 0.42f),
                size = Size(w * 0.72f, h * 0.18f),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
            drawRoundRect(
                color = cardColor,
                topLeft = Offset(w * 0.14f, h * 0.65f),
                size = Size(w * 0.72f, h * 0.18f),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            )
            drawRoundRect(
                color = if (darkPreview) Color(0x55FFFFFF) else Color(0x33000000),
                topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
                size = Size(w - 2.dp.toPx(), h - 2.dp.toPx()),
                cornerRadius = CornerRadius(13.dp.toPx(), 13.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = IosType.subheadline,
            color = colors.primaryText,
        )
        Spacer(Modifier.height(8.dp))
        if (selected) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                CheckmarkIcon(color = Color.White, size = 12.dp)
            }
        } else {
            Canvas(modifier = Modifier.size(22.dp)) {
                drawCircle(
                    color = colors.tertiaryText,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx()),
                )
            }
        }
    }
}
