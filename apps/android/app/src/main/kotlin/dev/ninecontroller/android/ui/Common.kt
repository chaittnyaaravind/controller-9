package dev.ninecontroller.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.R
import dev.ninecontroller.android.ui.theme.LocalAccent
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineType

// ---------------------------------------------------------------- brand

/** The Controller-9 mark: the app icon — a liquid-glass controller over RGB light — on a rounded tile. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) =
    Box(modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(size * 0.28f))) {
        Image(painterResource(R.drawable.ic_launcher_glass_background), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        // The icon's foreground is drawn for a 108 dp canvas with a 66 dp safe zone: scale it up to fill the tile.
        Image(painterResource(R.drawable.ic_launcher_glass_foreground), null, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.45f; scaleY = 1.45f })
    }

/** Mark + logotype lockup; cap height is 0.40 of the mark, gap 8/48, as in brand/svg/controller9-wordmark.svg. */
@Composable
fun Wordmark(markSize: Dp = 28.dp, modifier: Modifier = Modifier) =
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Controller-9" }, verticalAlignment = Alignment.CenterVertically) {
        BrandMark(markSize)
        Spacer(Modifier.size(markSize * 8 / 48))
        // One ink for the logotype: the generated drawable's lime dash predates the black-and-metal palette.
        Image(painterResource(R.drawable.c9_logotype), null, Modifier.height(markSize * 0.40f * 22 / 20), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Nine.text))
    }

// ---------------------------------------------------------------- layout

/**
 * Standard screen: back arrow (when [nav] is given), eyebrow micro-label, title, content.
 * Scrolls; respects system bars and cutouts; content width capped for tablets and landscape.
 */
@Composable
fun Page(title: String, eyebrow: String, nav: NavController?, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Nine.bg).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Nine.gutter, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = Nine.target), verticalAlignment = Alignment.CenterVertically) {
                if (nav != null) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Nine.text) }
                Spacer(Modifier.weight(1f))
                actions()
            }
            Micro(eyebrow, Modifier.padding(top = 8.dp), color = LocalAccent.current.color)
            Text(title, style = NineType.display, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp).semantics { heading() })
            Column(verticalArrangement = Arrangement.spacedBy(Nine.gap), content = content)
            Spacer(Modifier.size(40.dp))
        }
    }
}

/** A labelled group: micro-label above one surface whose rows are separated by hairlines. */
@Composable
fun Section(label: String?, modifier: Modifier = Modifier, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Micro(label, Modifier.padding(start = 4.dp).semantics { heading() })
        Column(Modifier.fillMaxWidth().background(Nine.s1, Nine.card).border(1.dp, Nine.line, Nine.card), content = content)
        if (footer != null) Text(footer, style = NineType.small, color = Nine.dim, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
fun Divider() = HorizontalDivider(thickness = 1.dp, color = Nine.line)

/** One tappable row: title, optional detail, trailing content (defaults to a chevron when clickable). */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    detailColor: Color = Nine.muted,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = NineType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = NineType.small, color = detailColor)
        }
        when {
            trailing != null -> trailing()
            onClick != null -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Nine.dim)
        }
    }
}

/** Kept for screens with free-form content. Prefer [Section]. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().background(Nine.s1, Nine.card).border(1.dp, Nine.line, Nine.card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

// ---------------------------------------------------------------- controls

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    Button(onClick, modifier.heightIn(min = Nine.target), enabled = enabled, shape = Nine.pill,
        colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current.color, contentColor = LocalAccent.current.ink, disabledContainerColor = Nine.s3, disabledContentColor = Nine.dim),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) { Text(text, style = NineType.body) }

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Nine.text) =
    OutlinedButton(onClick, modifier.heightIn(min = Nine.target), enabled = enabled, shape = Nine.pill,
        border = androidx.compose.foundation.BorderStroke(1.dp, Nine.line2),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) { Text(text, style = NineType.body) }

/** Single-choice chip (link preference, sensor rate). Selected = hairline accent + soft fill. */
@Composable
fun Choice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Box(modifier.heightIn(min = 40.dp).semantics { this.selected = selected }
        .background(if (selected) LocalAccent.current.soft else Color.Transparent, Nine.pill)
        .border(1.dp, if (selected) LocalAccent.current.color.copy(alpha = 0.6f) else Nine.line2, Nine.pill)
        .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) { Text(text, style = NineType.small, color = if (selected) Nine.text else Nine.muted) }

enum class Tone { OK, WARN, BAD, IDLE }

fun Tone.color(accent: Color = Nine.accent) = when (this) { Tone.OK -> accent; Tone.WARN -> Nine.warn; Tone.BAD -> Nine.bad; Tone.IDLE -> Nine.dim }

@Composable
fun StatusDot(color: Color, size: Dp = 7.dp) = Box(Modifier.size(size).background(color, CircleShape))

/** Small status capsule: dot + uppercase label. */
@Composable
fun Pill(text: String, tone: Tone, modifier: Modifier = Modifier, accent: Color = LocalAccent.current.color, fill: Color = Nine.s2) =
    Row(modifier.background(fill, Nine.pill).border(1.dp, Nine.line, Nine.pill).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        StatusDot(tone.color(accent), 6.dp)
        Text(text.uppercase(), style = NineType.label, color = Nine.text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) = Metric(value, label, modifier)

/** Big monospaced number + micro-label; "—" when not measured. */
@Composable
fun Metric(value: String, label: String, modifier: Modifier = Modifier, color: Color = Nine.text) = Column(modifier) {
    Text(value, style = NineType.metric, color = color)
    Micro(label, color = Nine.dim)
}

fun fmt(v: Double?, digits: Int = 1, unit: String = ""): String = if (v == null || v.isNaN()) "—" else "%.${digits}f%s".format(v, unit)
