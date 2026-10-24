package app.reviewnotes.timing.ui.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.reviewnotes.timing.ui.theme.Ink
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Quiet panel: the black glass card used for ordinary content.
// ---------------------------------------------------------------------------------------------

private val QuietBase = Ink.Panel.copy(alpha = 0.62f)
private val QuietFill = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.045f), Color.White.copy(alpha = 0.015f)),
)
private val QuietHairline = Color.White.copy(alpha = 0.07f)
private val QuietTopEdge = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.10f),
    0.4f to Color.Transparent,
)

/**
 * Black glass card: a faint white vertical sheen over the panel color at 62 %, a 1 dp hairline
 * and a brighter 1 dp top edge that fades out by 40 % of the height. Does not clip content.
 */
fun Modifier.quietPanel(shape: Shape = RoundedCornerShape(26.dp)): Modifier =
    this
        .background(QuietBase, shape)
        .background(QuietFill, shape)
        .border(1.dp, QuietHairline, shape)
        .border(1.dp, QuietTopEdge, shape)

// ---------------------------------------------------------------------------------------------
// Heavy glass: the thick, liquid look reserved for floating things.
// ---------------------------------------------------------------------------------------------

/** Default specular position, as fractions of the element size (slightly above the top-left). */
val DefaultGlassLight = Offset(0.15f, -0.1f)

/**
 * Floating glass that reads as thick and liquid without needing access to the backdrop:
 *
 *  1. a dark base (lighter on API 33+, where [liquidLensContent] already blurs what is behind),
 *  2. a 155° layered sheen (16 % → 6 % → 2.5 % → 7 % of [tint]),
 *  3. a soft specular bloom at [lightOffset] (fractions of the size; feed it [rememberTiltLight]),
 *  4. an inner top highlight and an inner bottom shadow (stacked strokes, clipped inside),
 *  5. the content,
 *  6. a 1.5 dp rim on top: bright where the light hits (top-left, ~85 %), a reflected glint at the
 *     opposite corner (~50 %), and a faint 4–12 % rim everywhere else.
 *
 * Everything is allocated in drawWithCache; the draw pass itself allocates nothing.
 */
fun Modifier.heavyGlass(
    shape: Shape,
    tint: Color = Color.White,
    lightOffset: Offset = DefaultGlassLight,
): Modifier = this.drawWithCache {
    val w = size.width
    val h = size.height
    val outline = shape.createOutline(size, layoutDirection, this)
    val clip = outlinePath(outline)

    // 1. base
    val base = Color.Black.copy(alpha = if (Build.VERSION.SDK_INT >= 33) 0.30f else 0.62f)

    // 2. 155° sheen (CSS angle convention: 0° = up, 90° = right).
    val dir = Offset(0.4226f, 0.9063f)
    val half = (abs(w * dir.x) + abs(h * dir.y)) / 2f
    val mid = Offset(w / 2f, h / 2f)
    val sheen = Brush.linearGradient(
        0f to tint.copy(alpha = 0.16f),
        0.38f to tint.copy(alpha = 0.06f),
        0.72f to tint.copy(alpha = 0.025f),
        1f to tint.copy(alpha = 0.07f),
        start = mid - dir * half,
        end = mid + dir * half,
    )

    // 3. specular bloom
    val light = Offset(lightOffset.x * w, lightOffset.y * h)
    val specR = max(min(w, h) * 1.1f + max(w, h) * 0.12f, 1f)
    val specular = Brush.radialGradient(
        0f to tint.copy(alpha = 0.22f),
        0.4f to tint.copy(alpha = 0.07f),
        1f to Color.Transparent,
        center = light,
        radius = specR,
    )

    // 4. inner edges: highlight at the top, shadow at the bottom, nothing in the middle.
    val innerEdge = Brush.verticalGradient(
        0f to tint.copy(alpha = 0.24f),
        0.4f to Color.Transparent,
        0.6f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.55f),
    )
    // Strokes straddle the outline; clipped to the inside they become soft bands of 1–9 dp.
    val innerStrokes = arrayOf(
        Stroke(width = 2.dp.toPx()),
        Stroke(width = 5.dp.toPx()),
        Stroke(width = 10.dp.toPx()),
        Stroke(width = 18.dp.toPx()),
    )

    // 6. rim, inset by half its width so it sits fully inside the bounds.
    val rimW = 1.5.dp.toPx()
    val rimOutline = shape.createOutline(
        Size(max(w - rimW, 0f), max(h - rimW, 0f)),
        layoutDirection,
        this,
    )
    val rimStroke = Stroke(width = rimW)
    val rimBase = Brush.verticalGradient(
        0f to tint.copy(alpha = 0.12f),
        0.5f to tint.copy(alpha = 0.04f),
        1f to tint.copy(alpha = 0.10f),
    )
    val rimLight = Brush.radialGradient(
        0f to tint.copy(alpha = 0.85f),
        0.45f to tint.copy(alpha = 0.18f),
        1f to Color.Transparent,
        center = light,
        radius = max(max(h * 1.6f, w * 0.55f), 1f),
    )
    val rimGlint = Brush.radialGradient(
        0f to tint.copy(alpha = 0.50f),
        0.5f to tint.copy(alpha = 0.12f),
        1f to Color.Transparent,
        center = Offset(w * (1f - lightOffset.x), h * (1f - lightOffset.y)),
        radius = max(max(h * 1.2f, w * 0.4f), 1f),
    )

    onDrawWithContent {
        drawOutline(outline, base)
        drawOutline(outline, sheen)
        clipPath(clip) {
            drawCircle(brush = specular, radius = specR, center = light)
            for (s in innerStrokes) {
                drawOutline(outline, innerEdge, alpha = 0.3f, style = s)
            }
        }
        drawContent()
        translate(left = rimW / 2f, top = rimW / 2f) {
            drawOutline(rimOutline, rimBase, style = rimStroke)
            drawOutline(rimOutline, rimLight, style = rimStroke)
            drawOutline(rimOutline, rimGlint, style = rimStroke)
        }
    }
}

internal fun outlinePath(outline: Outline): Path = when (outline) {
    is Outline.Generic -> outline.path
    is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
}

// ---------------------------------------------------------------------------------------------
// Tilt light: the specular follows the device.
// ---------------------------------------------------------------------------------------------

/**
 * Specular position for [heavyGlass] driven by device tilt (TYPE_GAME_ROTATION_VECTOR, falling
 * back to TYPE_ACCELEROMETER), low-pass filtered, at SENSOR_DELAY_UI. x ∈ [0.05, 0.6],
 * y ∈ [-0.25, 0.15]. Returns a constant [DefaultGlassLight] under reduced motion or without a
 * sensor. The listener is unregistered when the caller leaves composition.
 */
@Composable
fun rememberTiltLight(): State<Offset> {
    val reduced = rememberReducedMotion()
    val context = LocalContext.current
    val light = remember { mutableStateOf(DefaultGlassLight) }
    DisposableEffect(context, reduced) {
        if (reduced) {
            light.value = DefaultGlassLight
            return@DisposableEffect onDispose { }
        }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || sensor == null) {
            return@DisposableEffect onDispose { }
        }
        val listener = TiltListener(
            useRotationVector = sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR,
        ) { x, y -> light.value = Offset(x, y) }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    return light
}

private class TiltListener(
    private val useRotationVector: Boolean,
    private val emit: (Float, Float) -> Unit,
) : SensorEventListener {
    private val rv = FloatArray(4)
    private val m = FloatArray(9)
    private var fx = Float.NaN
    private var fy = Float.NaN
    private var sentX = Float.NaN
    private var sentY = Float.NaN

    override fun onSensorChanged(event: SensorEvent?) {
        val v = event?.values ?: return
        // (gx, gy) = world "up" expressed in device coordinates, unit length.
        val gx: Float
        val gy: Float
        if (useRotationVector) {
            // Some devices deliver 5 values, which older getRotationMatrixFromVector rejects.
            if (v.size >= 4) {
                System.arraycopy(v, 0, rv, 0, 4)
                SensorManager.getRotationMatrixFromVector(m, rv)
            } else {
                SensorManager.getRotationMatrixFromVector(m, v)
            }
            gx = m[6]
            gy = m[7]
        } else {
            if (v.size < 3) return
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (n < 0.5f) return
            gx = v[0] / n
            gy = v[1] / n
        }
        // Neutral reading posture (~45° back) maps to the default light position.
        val tx = (0.15f - gx * 0.55f).coerceIn(0.05f, 0.6f)
        val ty = (-0.1f + (0.72f - gy) * 0.45f).coerceIn(-0.25f, 0.15f)
        if (fx.isNaN()) {
            fx = tx
            fy = ty
        } else {
            fx += (tx - fx) * 0.18f
            fy += (ty - fy) * 0.18f
        }
        if (sentX.isNaN() || abs(fx - sentX) > 0.003f || abs(fy - sentY) > 0.003f) {
            sentX = fx
            sentY = fy
            emit(fx, fy)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
