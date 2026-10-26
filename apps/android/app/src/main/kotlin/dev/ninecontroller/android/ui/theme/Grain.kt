package dev.ninecontroller.android.ui.theme

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random

/**
 * Film grain: a 128 px tile of light specks, repeated. At 4 % it breaks gradient banding on the
 * glows and gives the dark surfaces a moulded, matte feel. Built once per process.
 */
object Grain {
    private val tile: ImageBitmap by lazy {
        val n = 128
        val rnd = Random(9)
        val px = IntArray(n * n) { (rnd.nextInt(0, 256) shl 24) or 0x00FFFFFF }
        android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val brush: ShaderBrush by lazy { ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)) }
    const val ALPHA = 0.04f
}
