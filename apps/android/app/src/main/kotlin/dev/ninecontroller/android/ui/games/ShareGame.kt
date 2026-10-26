package dev.ninecontroller.android.ui.games

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.ninecontroller.android.NineApp
import dev.ninecontroller.android.data.Game
import dev.ninecontroller.android.data.GameShare
import dev.ninecontroller.android.pad.PadPresets
import dev.ninecontroller.android.ui.Page
import dev.ninecontroller.android.ui.PrimaryButton
import dev.ninecontroller.android.ui.pair.QrScanner
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.android.ui.theme.NineType
import kotlinx.coroutines.launch

/** A black-on-white QR for [text] (white quiet zone, so any camera reads it on a dark screen). */
fun qrImage(text: String, px: Int = 720): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(px * px) { i -> if (m[i % px, i / px]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return android.graphics.Bitmap.createBitmap(pixels, px, px, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Shows a game's setup as a QR code another phone scans (Home → Games → Scan). */
@Composable
fun ShareGameDialog(app: NineApp, game: Game, onClose: () -> Unit) {
    val s by app.settings.flow.collectAsState(dev.ninecontroller.android.data.AppSettings())
    val preset = PadPresets.fromSelection(game.layout)
    val code = remember(game, s) { GameShare.encode(game, preset?.let { s.padMappings[it.id] }, preset?.let { s.padModes[it.id] }) }
    val img = remember(code) { qrImage(code) }
    AlertDialog(onDismissRequest = onClose, containerColor = Nine.s1,
        title = { Text("Share ${game.name}") },
        text = {
            Column {
                Image(img, null, Modifier.fillMaxWidth().aspectRatio(1f).clip(Nine.small).background(androidx.compose.ui.graphics.Color.White)
                    .semantics { contentDescription = "QR code with the ${game.name} setup" }, filterQuality = FilterQuality.None)
                Text("On the other phone: Home → Games → Scan a game. It gets this layout, colour, steering and button modes.",
                    style = NineType.small, color = Nine.muted, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done", color = Nine.text) } })
}

/** Scans a friend's game code and makes it the game being played. */
@Composable
fun ScanGameScreen(app: NineApp, nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var error by remember { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else error = "Camera access is needed only to scan the code."
    }
    Page("Scan a game", "Games", nav) {
        Text("Point the camera at the QR code on your friend's phone (their game → Share).", style = NineType.body, color = Nine.muted)
        if (scanning) QrScanner(Modifier.fillMaxWidth().aspectRatio(1f).clip(Nine.card), prefix = GameShare.PREFIX) { text ->
            val shared = GameShare.decode(text) { app.games.uniqueId(it) }
            if (shared == null) { error = "That code isn't a Controller-9 game."; return@QrScanner }
            app.games.save(shared.game)
            scope.launch {
                PadPresets.fromSelection(shared.game.layout)?.let { p ->
                    app.settings.update { it.copy(padMappings = it.padMappings + (p.id to shared.mapping), padModes = it.padModes + (p.id to shared.modes)) }
                }
                app.play(shared.game)
                nav.popBackStack()
            }
        } else PrimaryButton("Allow camera", { camera.launch(Manifest.permission.CAMERA) })
        error?.let { Text(it, color = Nine.bad, style = NineType.small) }
    }
}
