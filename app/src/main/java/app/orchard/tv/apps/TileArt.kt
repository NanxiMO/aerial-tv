package app.orchard.tv.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.annotation.RequiresApi
import android.os.Build
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.orchard.tv.wallpaper.Images
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What a tile draws. tvOS icons are layered so they can shift against each other when focused;
 * Android's adaptive icons happen to be layered the same way, so those keep their two layers here.
 *
 * - [back] always fills the tile: the app's TV banner, the adaptive icon's background layer, or a
 *   plate in the icon's own color.
 * - [fore] is drawn centered on top, [foreFraction] of the tile height tall, and moves with parallax.
 */
class TileArt(
    val back: ImageBitmap,
    val fore: ImageBitmap?,
    val foreFraction: Float,
    val tint: Color,
    /**
     * The whole home backdrop for this app, baked small: its blurred artwork as ambient light plus
     * the shading that keeps the clock and grid legible. Scaled up to full screen it costs the GPU
     * one textured draw per frame, which matters on TV chips.
     */
    val backdrop: ImageBitmap,
)

object TileArtFactory {
    // Rendered at the tile's focused size on a 1080p panel, so the lifted tile is still pixel-sharp.
    const val W = 352
    const val H = 198

    private const val GRAPHITE_TOP = 0xFF3A3A3C.toInt()
    private const val GRAPHITE_BOTTOM = 0xFF242426.toInt()

    fun fromBanner(banner: Drawable): TileArt {
        val back = createBitmap(W, H)
        Canvas(back).apply {
            drawColor(GRAPHITE_BOTTOM)
            drawCover(banner, W, H)
        }
        return finish(back, null, 0f)
    }

    /**
     * A TV input's tile, in the spirit of an app icon: the connector drawn in white over a gradient
     * that tells the kind apart (HDMI blue, tuner teal, AV amber), the input's name under it, and
     * the HDMI-CEC device name (e.g. "Apple TV") as a second line when there is one.
     */
    fun forSource(kind: app.orchard.tv.system.SourceKind, label: String, device: String?): TileArt {
        val (top, bottom) = when (kind) {
            app.orchard.tv.system.SourceKind.Hdmi -> 0xFF3B6FD8.toInt() to 0xFF1D3A86.toInt()
            app.orchard.tv.system.SourceKind.Tuner -> 0xFF1FA88E.toInt() to 0xFF0E5C55.toInt()
            app.orchard.tv.system.SourceKind.Av -> 0xFFE0912F.toInt() to 0xFF9A4F12.toInt()
            app.orchard.tv.system.SourceKind.Other -> 0xFF5E6470.toInt() to 0xFF30343C.toInt()
        }
        val back = createBitmap(W, H)
        Canvas(back).apply {
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, W * 0.4f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            })
            // Soft light from the top left, as on Aerial's own icon.
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = android.graphics.RadialGradient(W * 0.25f, -H * 0.2f, W * 0.8f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = H * 0.035f
                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            }
            val two = device != null
            val cx = W / 2f
            val cy = H * (if (two) 0.3f else 0.36f)
            val u = H * 0.1f
            when (kind) {
                app.orchard.tv.system.SourceKind.Hdmi -> {
                    // The HDMI plug face: a wide trapezoid with chamfered lower corners.
                    val p = android.graphics.Path().apply {
                        moveTo(cx - 2.2f * u, cy - 0.9f * u)
                        lineTo(cx + 2.2f * u, cy - 0.9f * u)
                        lineTo(cx + 2.2f * u, cy + 0.15f * u)
                        lineTo(cx + 1.5f * u, cy + 0.9f * u)
                        lineTo(cx - 1.5f * u, cy + 0.9f * u)
                        lineTo(cx - 2.2f * u, cy + 0.15f * u)
                        close()
                    }
                    drawPath(p, white)
                    drawLine(cx - 1.3f * u, cy - 0.05f * u, cx + 1.3f * u, cy - 0.05f * u, white)
                }
                app.orchard.tv.system.SourceKind.Tuner -> {
                    // An antenna over a screen edge.
                    drawLine(cx, cy + 0.9f * u, cx, cy - 0.3f * u, white)
                    drawLine(cx, cy - 0.3f * u, cx - 1.1f * u, cy - 1.1f * u, white)
                    drawLine(cx, cy - 0.3f * u, cx + 1.1f * u, cy - 1.1f * u, white)
                    drawLine(cx - 1.6f * u, cy + 0.9f * u, cx + 1.6f * u, cy + 0.9f * u, white)
                }
                else -> {
                    // RCA jack: a ring with a pin.
                    drawCircle(cx, cy, 0.95f * u, white)
                    drawCircle(cx, cy, 0.25f * u, Paint(white).apply { style = Paint.Style.FILL })
                }
            }
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = H * 0.17f; textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            drawText(label, cx, H * (if (two) 0.66f else 0.74f), title)
            if (device != null) {
                val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = H * 0.12f; textAlign = Paint.Align.CENTER }
                drawText(device, cx, H * 0.84f, sub)
            }
        }
        return finish(back, null, 0f)
    }

    fun fromIcon(icon: Drawable): TileArt {
        if (Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable && icon.foreground != null) adaptive(icon)?.let { return it }
        return plate(icon) ?: legacy(icon)
    }

    /** Null when the foreground ignores the adaptive safe zone (a legacy icon wrapped as adaptive). */
    @RequiresApi(26)
    private fun adaptive(icon: AdaptiveIconDrawable): TileArt? {
        // The foreground's 108-unit square keeps the logo inside its middle 66 units, so a
        // square as tall as the tile shows the logo at roughly the size tvOS uses.
        val size = H
        val fore = createBitmap(size, size)
        icon.foreground.setBounds(0, 0, size, size)
        icon.foreground.draw(Canvas(fore))
        if (opaqueExtent(fore) > 0.7f) {
            fore.recycle()
            return null
        }
        val back = createBitmap(W, H)
        Canvas(back).apply {
            drawColor(GRAPHITE_BOTTOM)
            icon.background?.let { bg ->
                // The background layer is a square; cover the wide tile with it.
                bg.setBounds(0, (H - W) / 2, W, (H - W) / 2 + W)
                bg.draw(this)
            }
        }
        return finish(back, fore, 1f)
    }

    /** How much of the bitmap's width or height its visible pixels span, 0..1. */
    private fun opaqueExtent(b: Bitmap): Float {
        val n = b.width
        var min = n; var max = -1
        val step = 2
        for (y in 0 until n step step) for (x in 0 until n step step) {
            if (android.graphics.Color.alpha(b.getPixel(x, y)) > 24) {
                min = minOf(min, x, y); max = maxOf(max, x, y)
            }
        }
        return if (max < 0) 0f else (max - min) / n.toFloat()
    }

    /**
     * A legacy icon that is a filled rounded square (most China-market apps ship these: a logo on a
     * white or colored plate). Drawn small in the middle it reads as an icon on a tile, not as the
     * tile. Instead the plate's own color is carried across the whole tile, top edge to bottom
     * edge, and the icon is drawn the full tile height with its sides faded into that color, the
     * way a tvOS icon fills its card. Null for any other shape.
     */
    private fun plate(icon: Drawable): TileArt? {
        val n = 256
        val b = createBitmap(n, n)
        icon.setBounds(0, 0, n, n)
        icon.draw(Canvas(b))
        val px = IntArray(n * n)
        b.getPixels(px, 0, n, 0, 0, n, n)
        fun solid(c: Int) = c ushr 24 > 200
        var l = n; var t = n; var r = -1; var bt = -1
        for (y in 0 until n) for (x in 0 until n) if (solid(px[y * n + x])) {
            l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); bt = maxOf(bt, y)
        }
        val w = r - l + 1
        val h = bt - t + 1
        // A square at least half the canvas, and nearly all of it solid: a rounded square fills
        // about 96% of its box, a circle 79%.
        var filled = 0
        if (r >= 0) for (y in t..bt) for (x in l..r) if (solid(px[y * n + x])) filled++
        if (r < 0 || w < n / 2 || kotlin.math.abs(w - h) > w * 0.06f || filled < w * h * 0.9f) {
            b.recycle()
            return null
        }
        // The plate's color along its top and bottom edges, inside the corner rounding.
        fun band(fromY: Int, toY: Int): Int? {
            var rr = 0L; var gg = 0L; var bb = 0L; var count = 0
            val xs = (l + w / 4)..(r - w / 4)
            for (y in fromY..toY) for (x in xs) {
                val c = px[y * n + x]
                rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF; count++
            }
            val mr = rr / count; val mg = gg / count; val mb = bb / count
            // A logo running into the edge leaves no single color to carry across the tile.
            var spread = 0L
            for (y in fromY..toY) for (x in xs) {
                val c = px[y * n + x]
                spread += kotlin.math.abs(((c shr 16) and 0xFF) - mr) + kotlin.math.abs(((c shr 8) and 0xFF) - mg) + kotlin.math.abs((c and 0xFF) - mb)
            }
            return if (spread / count > 36) null else android.graphics.Color.rgb(mr.toInt(), mg.toInt(), mb.toInt())
        }
        val edge = (h * 0.04f).roundToInt()
        val depth = (h * 0.06f).roundToInt()
        val top = band(t + edge, t + edge + depth)
        val bottom = band(bt - edge - depth, bt - edge)
        if (top == null || bottom == null) {
            b.recycle()
            return null
        }

        val back = createBitmap(W, H)
        Canvas(back).drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
        val fore = createBitmap(H, H)
        Canvas(fore).apply {
            drawBitmap(b, android.graphics.Rect(l, t, r + 1, bt + 1), android.graphics.Rect(0, 0, H, H), Paint(Paint.FILTER_BITMAP_FLAG))
            // Fade the left and right sides, corners included, into the tile.
            val fade = H * 0.24f
            drawRect(0f, 0f, H.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 0f, H.toFloat(), 0f,
                    intArrayOf(0, android.graphics.Color.BLACK, android.graphics.Color.BLACK, 0),
                    floatArrayOf(0f, fade / H, 1f - fade / H, 1f), Shader.TileMode.CLAMP,
                )
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            })
        }
        b.recycle()
        return finish(back, fore, 1f)
    }

    private fun legacy(icon: Drawable): TileArt {
        // A legacy icon. It sits, at the size tvOS gives a logo, on a frosted plate made from
        // itself: the icon blown up past the tile's edges, blurred and dimmed. Full-square icons
        // (many China-market system apps expect the launcher to mask them) get rounded corners.
        val sample = 192
        val iconBitmap = createBitmap(sample, sample)
        icon.setBounds(0, 0, sample, sample)
        icon.draw(Canvas(iconBitmap))
        val fore = if (opaqueCorners(iconBitmap)) roundCorners(iconBitmap, 0.225f) else iconBitmap

        val pw = 48
        val ph = 27
        val plate = createBitmap(pw, ph)
        Canvas(plate).apply {
            drawColor(plateFromTint(dominant(iconBitmap)))
            val inset = (sample * 0.2f).roundToInt()
            drawBitmap(iconBitmap, android.graphics.Rect(inset, inset + sample / 8, sample - inset, sample - inset - sample / 8), android.graphics.Rect(0, 0, pw, ph), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val px = IntArray(pw * ph)
        plate.getPixels(px, 0, pw, 0, 0, pw, ph)
        repeat(3) { Images.boxBlur(px, pw, ph, 3) }
        plate.setPixels(px, 0, pw, 0, 0, pw, ph)
        val back = createBitmap(W, H)
        val frost = ColorMatrix().apply {
            setSaturation(1.3f)
            postConcat(ColorMatrix().apply { setScale(0.7f, 0.7f, 0.7f, 1f) })
        }
        Canvas(back).drawBitmap(plate, null, android.graphics.Rect(0, 0, W, H), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(frost) })
        plate.recycle()
        if (fore !== iconBitmap) iconBitmap.recycle()
        return finish(back, fore, 0.6f)
    }

    private fun opaqueCorners(b: Bitmap): Boolean {
        val i = (b.width * 0.03f).roundToInt()
        val e = b.width - 1 - i
        return listOf(b.getPixel(i, i), b.getPixel(e, i), b.getPixel(i, e), b.getPixel(e, e))
            .all { android.graphics.Color.alpha(it) > 200 }
    }

    private fun roundCorners(src: Bitmap, radiusFraction: Float): Bitmap {
        val out = createBitmap(src.width, src.height)
        val r = src.width * radiusFraction
        Canvas(out).drawRoundRect(
            0f, 0f, src.width.toFloat(), src.height.toFloat(), r, r,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = android.graphics.BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) },
        )
        return out
    }

    private fun finish(back: Bitmap, fore: Bitmap?, foreFraction: Float): TileArt {
        val composed = if (fore == null) back else back.copy(Bitmap.Config.ARGB_8888, true).also {
            val size = (H * foreFraction).roundToInt()
            val left = (W - size) / 2
            val top = (H - size) / 2
            Canvas(it).drawBitmap(fore, null, android.graphics.Rect(left, top, left + size, top + size), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val tint = dominant(composed)
        val backdrop = backdropOf(composed)
        if (composed !== back) composed.recycle()
        return TileArt(
            back = back.asImageBitmap(),
            fore = fore?.asImageBitmap(),
            foreFraction = foreFraction,
            tint = Color(tint),
            backdrop = backdrop.asImageBitmap(),
        )
    }

    private fun Canvas.drawCover(d: Drawable, w: Int, h: Int) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = max(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * scale).roundToInt()
        val dh = (ih * scale).roundToInt()
        val l = (w - dw) / 2
        val t = (h - dh) / 2
        d.setBounds(l, t, l + dw, t + dh)
        d.draw(this)
    }

    /** Saturation-weighted mean color: the hue a person would name when describing the tile. */
    private fun dominant(b: Bitmap): Int {
        val small = b.scale(24, 14)
        var r = 0.0; var g = 0.0; var bl = 0.0; var total = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            if (android.graphics.Color.alpha(c) < 128) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val wgt = hsv[1] * hsv[2] * hsv[2] + 0.04
            r += android.graphics.Color.red(c) * wgt
            g += android.graphics.Color.green(c) * wgt
            bl += android.graphics.Color.blue(c) * wgt
            total += wgt
        }
        if (small !== b) small.recycle()
        if (total == 0.0) return GRAPHITE_TOP
        return android.graphics.Color.rgb((r / total).roundToInt(), (g / total).roundToInt(), (bl / total).roundToInt())
    }

    /** A plate behind a logo that has no background of its own: the logo's hue, deep and calm. */
    private fun plateFromTint(tint: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(tint, hsl)
        if (hsl[1] < 0.18f) return GRAPHITE_TOP
        hsl[1] = hsl[1].coerceAtMost(0.55f)
        hsl[2] = 0.24f
        return ColorUtils.HSLToColor(hsl)
    }

    private fun backdropOf(src: Bitmap): Bitmap {
        val w = 64
        val h = 36
        val small = src.scale(w, h)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { Images.boxBlur(px, w, h, 4) }
        val blurred = createBitmap(w, h)
        blurred.setPixels(px, 0, w, 0, 0, w, h)
        // Lift saturation and pull brightness down: ambient light, not a picture. Pale artwork
        // (white banners) would spread a grey haze, so it gets dimmed much further.
        val hsv = FloatArray(3)
        var sat = 0f; var value = 0f
        for (c in px) { android.graphics.Color.colorToHSV(c, hsv); sat += hsv[1]; value += hsv[2] }
        sat /= px.size; value /= px.size
        val gain = if (sat < 0.2f) 0.82f - 0.5f * value * (1f - sat / 0.2f) else 0.82f
        val cm = ColorMatrix().apply {
            setSaturation(1.5f)
            postConcat(ColorMatrix().apply { setScale(gain, gain, gain, 1f) })
        }
        val bw = 192
        val bh = 108
        val out = createBitmap(bw, bh)
        Canvas(out).apply {
            drawBitmap(
                blurred, null, android.graphics.Rect(0, 0, bw, bh),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) },
            )
            // Light kept up top where the shelf is, near-black under the grid.
            drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, bh.toFloat(),
                    intArrayOf(black(0.10f), black(0.32f), black(0.80f), black(0.94f)),
                    floatArrayOf(0f, 0.42f, 0.72f, 1f), Shader.TileMode.CLAMP,
                )
            })
            // The left edge shaded so the clock reads over bright artwork.
            drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, bw * 0.55f, 0f, black(0.38f), 0, Shader.TileMode.CLAMP)
            })
        }
        small.recycle(); blurred.recycle()
        return out
    }

    private fun black(alpha: Float) = android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0)
}
