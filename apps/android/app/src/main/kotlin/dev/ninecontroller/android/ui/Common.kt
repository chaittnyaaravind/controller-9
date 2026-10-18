package dev.ninecontroller.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.ninecontroller.android.ui.theme.Micro
import dev.ninecontroller.android.ui.theme.Nine

@Composable
fun Page(title: String, eyebrow: String, nav: NavController?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Nine.bg).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (nav != null) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Nine.text) }
        }
        Micro(eyebrow, color = Nine.accent)
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp).semantics { heading() })
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        Spacer(Modifier.size(32.dp))
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().background(Nine.s1, Nine.card).border(1.dp, Nine.line, Nine.card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

@Composable
fun StatusDot(color: Color) = Box(Modifier.size(7.dp).background(color, CircleShape))

@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) = Column(modifier) {
    Text(value, fontFamily = Nine.mono, style = MaterialTheme.typography.titleLarge)
    Micro(label, color = Nine.dim)
}

fun fmt(v: Double?, digits: Int = 1, unit: String = ""): String = if (v == null || v.isNaN()) "—" else "%.${digits}f%s".format(v, unit)
