package app.reviewnotes.timing.ui.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.RenderEffect as AndroidRenderEffect

/** One floating glass element: its bounds (root coordinates) and corner radius, in px. */
data class LensRect(val rect: Rect, val radius: Float)

/**
 * Where the floating glass sits, so the content underneath can be refracted.
 * At most [MaxLenses] lenses; slots are filled by [reportLens] and cleared when it leaves.
 *
 * [rects] holds root-coordinate rects; [liquidLensContent] converts them into the content
 * box's own space by subtracting the content box's position in root.
 */
@Stable
class LensState {
    var rects: List<LensRect> by mutableStateOf(emptyList())

    internal var contentOrigin: Offset by mutableStateOf(Offset.Zero)

    private val slots = arrayOfNulls<LensRect>(MaxLenses)

    /** Sets (or clears, with null) lens [index]; ignored when nothing changed. */
    fun set(index: Int, lens: LensRect?) {
        if (index < 0 || index >= MaxLenses) return
        if (slots[index] == lens) return
        slots[index] = lens
        rects = slots.filterNotNull()
    }

    companion object {
        const val MaxLenses = 2
    }
}

/**
 * Apply to the (scrolling) content container that floating glass hovers over. On Android 13+
 * the container is rendered through an AGSL lens: inside each reported rounded rect the content
 * is bent outward near the rim like a convex lens, split slightly into r/g/b, softly blurred,
 * brightened ×1.08 and a little more saturated. Outside the lenses pixels pass through untouched,
 * and with no lenses reported the effect is removed entirely. Below API 33 this is a no-op.
 *
 * The floating glass itself must NOT be a child of this container (it would refract itself);
 * draw it as a sibling on top. The content box should cover the area behind the glass — easiest
 * is filling the window from (0, 0), but any position works because the box's own position in
 * root is subtracted from the lens rects.
 */
fun Modifier.liquidLensContent(state: LensState): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        this.then(lensEffect33(state))
    } else {
        this
    }

/**
 * Reports this element's bounds as lens [index] (0 or 1) of [state], with corner [radius].
 * Bounds come from positionInRoot, i.e. relative to the ComposeView root. The slot is cleared
 * when the element leaves composition.
 */
fun Modifier.reportLens(state: LensState, index: Int, radius: Dp): Modifier = composed {
    val density = LocalDensity.current
    DisposableEffect(state, index) {
        onDispose { state.set(index, null) }
    }
    Modifier.onGloballyPositioned { coords ->
        val pos = coords.positionInRoot()
        val s = coords.size
        val r = with(density) { radius.toPx() }
        state.set(
            index,
            LensRect(Rect(pos, Size(s.width.toFloat(), s.height.toFloat())), r),
        )
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun lensEffect33(state: LensState): Modifier = Modifier.composed {
    val density = LocalDensity.current
    val lens = remember { LensShader.createOrNull() }
    if (lens == null) {
        Modifier
    } else {
        val bezelMax = with(density) { 22.dp.toPx() }
        val blur = with(density) { 2.5.dp.toPx() }
        Modifier
            .onGloballyPositioned { state.contentOrigin = it.positionInRoot() }
            .graphicsLayer {
                // State reads here only re-run this block (no recomposition).
                renderEffect = lens.effectFor(state.rects, state.contentOrigin, bezelMax, blur)
            }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class LensShader private constructor(private val shader: RuntimeShader) {
    private var lastRects: List<LensRect>? = null
    private var lastOrigin = Offset.Zero
    private var lastBezel = -1f
    private var lastBlur = -1f
    private var effect: RenderEffect? = null
    private var broken = false

    /** Returns a cached effect unless the uniforms changed; null when there is nothing to bend. */
    fun effectFor(rects: List<LensRect>, origin: Offset, bezelMax: Float, blur: Float): RenderEffect? {
        if (rects.isEmpty() || broken) return null
        return try {
            build(rects, origin, bezelMax, blur)
        } catch (t: Throwable) {
            // A driver / AGSL problem must never take the screen down: fall back to plain content.
            broken = true
            null
        }
    }

    private fun build(rects: List<LensRect>, origin: Offset, bezelMax: Float, blur: Float): RenderEffect {
        val cached = effect
        if (cached != null && rects == lastRects && origin == lastOrigin &&
            bezelMax == lastBezel && blur == lastBlur
        ) {
            return cached
        }
        val l0 = rects[0]
        val l1 = if (rects.size > 1) rects[1] else l0
        shader.setFloatUniform(
            "lens0",
            l0.rect.left - origin.x, l0.rect.top - origin.y,
            l0.rect.right - origin.x, l0.rect.bottom - origin.y,
        )
        shader.setFloatUniform(
            "lens1",
            l1.rect.left - origin.x, l1.rect.top - origin.y,
            l1.rect.right - origin.x, l1.rect.bottom - origin.y,
        )
        shader.setFloatUniform("radius0", l0.radius)
        shader.setFloatUniform("radius1", l1.radius)
        shader.setFloatUniform("lensCount", rects.size.coerceAtMost(LensState.MaxLenses).toFloat())
        shader.setFloatUniform("bezelMax", bezelMax)
        shader.setFloatUniform("blurRadius", blur)
        // RenderEffect snapshots the shader, so a new one is needed whenever uniforms change.
        val e = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        effect = e
        lastRects = rects
        lastOrigin = origin
        lastBezel = bezelMax
        lastBlur = blur
        return e
    }

    companion object {
        /** Null if the shader fails to compile on this device: the content then renders plainly. */
        fun createOrNull(): LensShader? = try {
            LensShader(RuntimeShader(LENS_AGSL))
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * AGSL lens. Coordinates are in px of the content layer. lensN = (left, top, right, bottom).
 *
 * Inside a lens, at distance `depth` from the rim, with bezel = min(h * 0.45, bezelMax):
 *   t    = 1 - depth / bezel            (1 at the rim, 0 where the flat middle starts)
 *   disp = 0.9 * bezel * t^2            (displacement along the outward normal)
 * so content from just outside the glass is pulled in and compressed at the rim, like the
 * curved edge of a thick convex lens. r/g/b sample at 1.0 / 0.9 / 0.8 of the displacement.
 */
private const val LENS_AGSL = """
uniform shader content;
uniform float4 lens0;
uniform float4 lens1;
uniform float radius0;
uniform float radius1;
uniform float lensCount;
uniform float bezelMax;
uniform float blurRadius;

float rrSdf(float2 p, float4 r, float rad) {
    float2 c = (r.xy + r.zw) * 0.5;
    float2 hs = max((r.zw - r.xy) * 0.5, float2(0.5));
    float rr = clamp(rad, 0.0, min(hs.x, hs.y));
    float2 q = abs(p - c) - hs + rr;
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rr;
}

float2 rrNormal(float2 p, float4 r, float rad) {
    float2 c = (r.xy + r.zw) * 0.5;
    float2 hs = max((r.zw - r.xy) * 0.5, float2(0.5));
    float rr = clamp(rad, 0.0, min(hs.x, hs.y));
    float2 d = p - c;
    float2 s = float2(d.x < 0.0 ? -1.0 : 1.0, d.y < 0.0 ? -1.0 : 1.0);
    float2 q = abs(d) - hs + rr;
    if (q.x > 0.0 && q.y > 0.0) {
        return s * normalize(q);
    }
    if (q.x > q.y) {
        return float2(s.x, 0.0);
    }
    return float2(0.0, s.y);
}

half4 main(float2 p) {
    half4 base = content.eval(p);
    if (lensCount < 0.5) {
        return base;
    }
    float4 r = lens0;
    float rad = radius0;
    float d = rrSdf(p, lens0, radius0);
    if (lensCount > 1.5) {
        float d1 = rrSdf(p, lens1, radius1);
        if (d1 < d) {
            d = d1;
            r = lens1;
            rad = radius1;
        }
    }
    if (d >= 0.0) {
        return base;
    }

    float depth = -d;
    float bezel = max(min((r.w - r.y) * 0.45, bezelMax), 1.0);
    float t = clamp(1.0 - depth / bezel, 0.0, 1.0);
    float disp = 0.9 * bezel * t * t;
    float2 off = rrNormal(p, r, rad) * disp;

    float4 acc = float4(0.0);
    for (int i = 0; i < 12; i++) {
        float fi = float(i);
        float a = fi * 2.39996323;
        float rr = blurRadius * sqrt((fi + 0.5) / 12.0);
        float2 o = float2(cos(a), sin(a)) * rr;
        if (disp > 0.5) {
            float4 g = float4(content.eval(p + off * 0.9 + o));
            float cr = float(content.eval(p + off + o).r);
            float cb = float(content.eval(p + off * 0.8 + o).b);
            acc += float4(cr, g.g, cb, g.a);
        } else {
            acc += float4(content.eval(p + off + o));
        }
    }
    float4 c = acc / 12.0;

    float luma = dot(c.rgb, float3(0.2126, 0.7152, 0.0722));
    float3 rgb = mix(float3(luma), c.rgb, 1.22) * 1.08;
    rgb = clamp(rgb, float3(0.0), float3(c.a));
    float4 lensed = float4(rgb, c.a);

    float aa = clamp(depth, 0.0, 1.0);
    return half4(mix(float4(base), lensed, aa));
}
"""
