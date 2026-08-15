package com.wathemer.app.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Two AGSL programs, because the blur must sit between them: refraction needs a sharp backdrop
 * and is blurred after; the light pass reads no image and runs at full resolution, never blurred.
 */
object GlassShader {

    private const val TAG = "wtLiquid.Shader"

    /** Shared prelude (SDF, gradient, lens profile), included in both programs so geometry cannot drift. */
    private val COMMON = """
        uniform float2 size;
        uniform float4 radii;        // per-corner radius, TL TR BR BL; four equal values are the old single radius
        uniform float  bevel;        // band width, px
        uniform float  depth;        // glass thickness, px
        uniform float  cornerSmooth;
        /**
         * How far the surface continues past the drawn area, per side (l, t, r, b).
         *
         * For a surface whose edge is off-screen (a full-width band under the status bar), an SDF
         * edge at the view boundary paints a bright bevel rim along the top of the display, which is
         * exactly the seam such a band exists to avoid. Expanding the SDF's extents past the view
         * puts that edge where nobody can see it and leaves the visible region on the flat plateau.
         */
        uniform float4 expand;
        /** Px over which coverage ramps to zero at the bottom. 0 disables. */
        uniform float  fadeBottom;
        /**
         * Top fade: `x` = where the ramp starts in surface-local px (may be negative, i.e. above the
         * surface), `y` = its length. 0 length disables.
         *
         * Separate from [fadeBottom] because it is not the same effect: the bottom fade dissolves a
         * band into the content below it, while this dissolves a surface as it travels up the screen
         * past a fixed line. A chat bubble needs the second one, and its ramp has to be positioned in
         * screen space rather than at the surface's own edge.
         */
        uniform float2 fadeTop;

        /**
         * Polynomial smooth-max, to kill an artefact rather than for elegance.
         *
         * A rounded-rect SDF's interior is `max(q.x, q.y)`, whose gradient flips discontinuously
         * across the diagonal where q.x == q.y. Those diagonals run inward from each corner and any
         * normal derived from the gradient inherits the jump, so a wide bevel paints two hard
         * 45-degree seams in from every corner and the surface reads as a trapezium.
         */
        float smaxp(float a, float b, float k) {
            float h = clamp(0.5 + 0.5 * (a - b) / k, 0.0, 1.0);
            return mix(b, a, h) + k * h * (1.0 - h);
        }

        // Inigo Quilez rounded box. Negative inside, zero on the edge.
        float sdRoundRect(float2 p, float2 halfSize, float r, float k) {
            float2 q = abs(p) - halfSize + r;
            return length(max(q, 0.0)) + min(smaxp(q.x, q.y, k), 0.0) - r;
        }

        /**
         * Quarter-circle lens cross-section. Returns (height, slope).
         *
         * t is 0 at the rim and 1 at `bevel` inside. Near-flat in the interior, vertical at
         * the very edge, which is where a real lens does all its bending.
         */
        float2 lensProfile(float t) {
            if (t <= 0.0) return float2(0.0, 8.0);
            if (t >= 1.0) return float2(1.0, 0.0);
            float u = 1.0 - t;
            float h = sqrt(max(1.0 - u * u, 1e-6));
            return float2(h, min(u / max(h, 1e-3), 8.0));
        }

        /**
         * Everything both programs need about the surface at one point, in one float4: `xy` is the
         * normal's xy, `z` is t (0 at the rim, 1 at the flat interior), `w` the signed distance.
         *
         * The normal's z is recoverable: unit length, and positive because the surface faces the
         * viewer. Packing it this way avoids an out parameter. AGSL is a restricted dialect
         * and anything it rejects fails at runtime compile, taking refraction and lighting with it.
         */
        float4 surfaceAt(float2 fragCoord) {
            // The SDF works in the expanded surface, not the drawn one; see expand. With all four
            // sides expanded the whole visible area is interior, so there is no bevel and no rim.
            float2 halfSize = (size + float2(expand.x + expand.z, expand.y + expand.w)) * 0.5;
            float2 p = (fragCoord + float2(expand.x, expand.y)) - halfSize;
            float k = max(cornerSmooth, 0.5);
            // Quadrant-selected radius. Safe: the SDF's value on a straight edge is radius-independent,
            // so the switch lines at the axis midpoints cannot seam; only the corners differ.
            float r = (p.x < 0.0) ? ((p.y < 0.0) ? radii.x : radii.w)
                                  : ((p.y < 0.0) ? radii.y : radii.z);
            float d = sdRoundRect(p, halfSize, r, k);

            float2 g = float2(
                sdRoundRect(p + float2(1.0, 0.0), halfSize, r, k) -
                sdRoundRect(p - float2(1.0, 0.0), halfSize, r, k),
                sdRoundRect(p + float2(0.0, 1.0), halfSize, r, k) -
                sdRoundRect(p - float2(0.0, 1.0), halfSize, r, k)
            );
            float gl = length(g);
            float2 n2 = gl > 0.0001 ? g / gl : float2(0.0, -1.0);

            float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
            float2 hp = lensProfile(t);
            float s = (depth / max(bevel, 1.0)) * hp.y;
            float3 n = normalize(float3(n2 * s, 1.0));
            return float4(n.xy, t, d);
        }

        /** Recover the normal's z. Unit length, and positive because it faces the viewer. */
        float normalZ(float2 nxy) { return sqrt(max(1.0 - dot(nxy, nxy), 0.0)); }

        /**
         * Coverage multiplier for both fades. One definition for both programs, because the
         * transmitted image and the lighting must vanish over the same pixels or the tint outlives
         * the backdrop and the band ends in a pale ghost of itself.
         *
         * `t*t` rather than smoothstep. smoothstep has zero derivative at both ends, so coverage
         * stays near 1 for the first quarter of the ramp and the fade appears to start ~30 px below
         * where it does. A square is steep where it meets full coverage and flat where it lands,
         * which is the right way round: the eye forgives a kink at full opacity and does not
         * forgive one at the vanishing end, where Mach banding lives.
         */
        float fadeAt(float2 fragCoord) {
            float f = 1.0;
            if (fadeBottom > 0.0) {
                float t = clamp((size.y - fragCoord.y) / fadeBottom, 0.0, 1.0);
                f *= t * t;
            }
            // Linear here, deliberately. AbsListView's fading edge is a linear gradient, and it is
            // dissolving the text of the same bubbles this dissolves the glass of; a squared ramp
            // would visibly separate the two as a row travels up.
            if (fadeTop.y > 0.0) {
                f *= clamp((fragCoord.y - fadeTop.x) / fadeTop.y, 0.0, 1.0);
            }
            return f;
        }
    """.trimIndent()

    /** Transmission: refract the sharp capture. Outputs colour only, no lighting and no tint. */
    val REFRACT_AGSL: String = """
        uniform shader content;
        uniform float ior;
        uniform float dispersion;
        uniform float magnify;
        uniform float maxDisplace;    // px, soft ceiling on the bend
        uniform float transGamma;     // <1 lifts the transmitted darks; 1 is off

        $COMMON

        half4 main(float2 fragCoord) {
            float4 sf = surfaceAt(fragCoord);
            float3 n = float3(sf.xy, normalZ(sf.xy));
            float  t = sf.z;

            // Snell, then walk the bent ray down through the slab to the backdrop plane. On the
            // flat plateau n = +z, the ray comes straight back out and the displacement is exactly
            // zero, so the readable middle falls out of the maths rather than being a special case.
            float eta  = 1.0 / max(ior, 1.0001);
            float cosi = n.z;
            float kk   = 1.0 - eta * eta * (1.0 - cosi * cosi);
            float2 off = float2(0.0);
            if (kk > 0.0) {
                float  f   = eta * cosi - sqrt(kk);
                float2 txy = f * n.xy;                       // f < 0 for a convex bevel: inward
                float  tz  = -eta + f * n.z;
                off = txy * (depth / max(-tz, 1e-4));
            }

            // A soft ceiling, not a clamp. Displacement grows with thickness, which tracks the
            // band, which tracks the surface size, so on a large panel the physical answer is a
            // smear rather than a lens, 241 px on the chat-list card. A hard clamp would flatten a
            // wide region to one value and draw a visible ring; this asymptotes toward the limit,
            // so the profile's shape survives and only its amplitude is bounded.
            float len = length(off);
            if (len > 0.0) off *= maxDisplace / (maxDisplace + len);

            // Optional whole-surface magnification: contract the sampled coordinate toward the
            // centre, tapering off near the rim so it does not fight the lensing there.
            if (magnify > 0.0) {
                float2 halfSize = max(size * 0.5, float2(1.0));
                float2 nd = (fragCoord - size * 0.5) / halfSize;
                off -= nd * halfSize * magnify * (1.0 - clamp(dot(nd, nd), 0.0, 1.0));
            }

            // Per-channel spread, gated by edge weight so the fringe only exists at the rim.
            float  w  = 1.0 - t;
            float2 dR = off * (1.0 - dispersion * w);
            float2 dB = off * (1.0 + dispersion * w);

            float2 lo = float2(0.5, 0.5);
            float2 hi = size - float2(0.5, 0.5);
            half4 cg = content.eval(clamp(fragCoord + off, lo, hi));
            half  cr = content.eval(clamp(fragCoord + dR,  lo, hi)).r;
            half  cb = content.eval(clamp(fragCoord + dB,  lo, hi)).b;
            half4 outc = half4(cr, cg.g, cb, cg.a);

            // Transmission gamma, which is what stops the backdrop reading as frosted. See
            // GlassParams.transGamma for the measurements behind the default.
            //
            // Applied only where the capture is opaque. These are premultiplied colours, so `pow`
            // on the rgb of a partially transparent pixel would break the premultiplication and
            // fringe the edge; the underlay covers the whole screen, so a > 0.99 holds wherever it
            // matters. half3(...) is spelled out because a half4 built from a float3 and a half is
            // a combination AGSL need not accept, and an AGSL error is a runtime compile failure
            // that takes the whole program with it.
            if (transGamma < 0.999 && outc.a > 0.99) {
                outc = half4(half3(pow(float3(outc.rgb), float3(transGamma))), outc.a);
            }
            // Premultiplied, so one multiply fades colour and alpha together and correctly.
            return outc * half(fadeAt(fragCoord));
        }
    """.trimIndent()

    /** Lighting and tint. Reads no image, so it runs at full resolution and stays crisp. */
    val LIGHT_AGSL: String = """
        uniform float4 tint;             // straight (non-premultiplied) rgba
        uniform float  specStrength;
        uniform float  specPower;
        uniform float  specPower2;
        uniform float2 light1;
        uniform float2 light2;
        uniform float  fresnelStrength;
        uniform float  fresnelPower;
        uniform float  microAmp;         // normal-tilt noise amplitude; 0 disables
        uniform float  microScale;       // noise period, px
        uniform float  innerShadow;      // dark band just inside the rim; 0 disables
        uniform float  sheenStrength;    // broad third lobe; 0 disables
        uniform float  sheenPower;
        uniform float2 light3;
        uniform float2 pressPos;         // surface-local px; a press pools light here
        uniform float  pressAmp;         // added to the light scalar; 0 disables
        uniform float  pressR;           // pool radius, px

        $COMMON

        /**
         * Hash and value noise for the micro-distortion window. Written out rather than looped,
         * because AGSL only accepts compile-time unrollable loops, and kept in the light program
         * alone so the transmission carries no dead code.
         *
         * Dave Hoskins' "hash without sine" hash12, chosen for precision rather than taste: every
         * intermediate stays small, `fract` first so values are in [0,1), then a dot product
         * reaching at most ~35. Sine-based hashes and the common
         * `fract(p * 123.34; dot(q, q + 45.32); fract(q.x * q.y))` form both push an intermediate to
         * ~8300 before the final `fract`, which is fine in fp32 and keeps almost no fractional bits
         * in mediump. A hash that collapses toward a few values does not look broken, it looks like
         * flat or blocky glass, and this has to run on hardware we do not have.
         */
        float hash21(float2 p) {
            float3 q = fract(float3(p.x, p.y, p.x) * 0.1031);
            q += dot(q, q.yzx + 33.33);
            return fract((q.x + q.y) * q.z);
        }

        float vnoise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            float2 u = f * f * (3.0 - 2.0 * f);   // smoothstep, so the field is continuous
            float a = hash21(i);
            float b = hash21(i + float2(1.0, 0.0));
            float c = hash21(i + float2(0.0, 1.0));
            float d = hash21(i + float2(1.0, 1.0));
            return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
        }

        half4 main(float2 fragCoord) {
            float4 sf = surfaceAt(fragCoord);
            float2 nxy = sf.xy;

            // Micro distortion, windowed to zero at both ends of the band.
            //
            // At t == 1 it is correctness: GlassView draws this program only over the bevel band and
            // fills the interior with flatInteriorColor(), a CPU-computed constant for the t == 1
            // case, so anything surviving to t == 1 disagrees with that constant and steps the
            // band's inner edge.
            //
            // At t == 0 it is appearance: noise that peaks at the rim tilts the one crisp feature
            // the effect depends on, the hairline highlight, and the outline reads speckled.
            //
            // 4t(1-t) peaks at t = 0.5 and falls to 0 at both ends. The specular arcs sit at
            // t ~= 0.65 for the shipped light directions, where |n.xy| matches the half-vector's
            // 0.349 tilt, so they still get texture while the outline stays clean.
            //
            // Two uncorrelated samples for the two axes rather than a gradient of one: a tilt field
            // need not be curl-free, and this halves the noise evaluations.
            if (microAmp > 0.0) {
                float tt = clamp(sf.z, 0.0, 1.0);
                float w = 4.0 * tt * (1.0 - tt);
                if (w > 0.0) {
                    float2 q = fragCoord / max(microScale, 1.0);
                    float2 j = float2(vnoise(q), vnoise(q + float2(37.7, 11.3))) - 0.5;
                    nxy += j * (2.0 * microAmp * w);
                }
            }

            float3 n = float3(nxy, normalZ(nxy));
            float  d = sf.w;

            // Coverage from the SDF gives a properly antialiased rounded rect, so the pane
            // needs no explicit clip of its own.
            float cov = clamp(0.5 - d, 0.0, 1.0) * fadeAt(fragCoord);
            if (cov <= 0.002) return half4(0.0);

            // Suppress the highlight on the flat interior. n.z is 1 there, so `rim` is 0.
            //
            // Squared, not `min(rim * 6, 1)` as the reference has it. That form saturates as soon
            // as the surface tilts at all, which suits a narrow band but not one a quarter of the
            // surface's smaller dimension: it held the mask at full strength across most of the
            // band, and the lobe stopped being an arc and became a dome over every rounded cap.
            float rim = 1.0 - n.z * n.z;
            float rimMask = rim * rim;

            float3 V = float3(0.0, 0.0, 1.0);
            float3 H1 = normalize(normalize(float3(light1, 1.0)) + V);
            float3 H2 = normalize(normalize(float3(light2, 1.0)) + V);
            float spec = (pow(max(dot(n, H1), 0.0), specPower) +
                          pow(max(dot(n, H2), 0.0), specPower2) * 0.45) * specStrength * rimMask;

            // A deliberately broad third lobe. specPower 96 and specPower2 44 both draw thin arcs;
            // a low exponent spreads a wide wash along the same band, and the two together read as
            // a lit surface rather than as one drawn highlight. Multiplied by rimMask like the
            // others, so it cannot disturb flatInteriorColor() on the plateau.
            if (sheenStrength > 0.0) {
                float3 H3 = normalize(normalize(float3(light3, 1.0)) + V);
                spec += pow(max(dot(n, H3), 0.0), sheenPower) * sheenStrength * rimMask;
            }

            // Fresnel stays, demoted: a weak whitening around the whole perimeter rather than
            // the entire lighting model.
            float f0 = 0.04;
            float fres = (f0 + (1.0 - f0) * pow(1.0 - n.z, fresnelPower)) * fresnelStrength;

            float light = clamp(spec + fres, 0.0, 1.0);

            // Press pool, ADDED TO THE SCALAR: a normal bump hits the pow(1-n.z,5) rim as microAmp did.
            if (pressAmp > 0.0) {
                float pw = 1.0 - clamp(length(fragCoord - pressPos) / max(pressR, 1.0), 0.0, 1.0);
                light = clamp(light + pressAmp * pw * pw, 0.0, 1.0);
            }

            // A soft dark band just inside the bright rim, peaking at t = 0.35 and gone by 0.75, so
            // it too vanishes before the plateau. At a real glass edge the specular line is
            // separated from the body by a darker zone, because light at that angle refracts away
            // from the viewer; without it a translucent panel's edge reads as a stroke drawn on top.
            //
            // Added as premultiplied black: rgb contributes nothing and alpha rises, so it
            // composites as a darkening of whatever shows through rather than as grey paint.
            float shade = 0.0;
            if (innerShadow > 0.0) {
                float tt = clamp(sf.z, 0.0, 1.0);
                shade = innerShadow * smoothstep(0.0, 0.35, tt) * (1.0 - smoothstep(0.35, 0.75, tt));
            }

            // Premultiplied: the tint contributes rgb*a, the light adds white with its own
            // alpha so it brightens without darkening what shows through.
            float3 rgb = clamp(tint.rgb * tint.a + float3(light), 0.0, 1.0);
            float  a   = clamp(tint.a + light + shade, 0.0, 1.0);
            return half4(half3(rgb), half(a)) * half(cov);
        }
    """.trimIndent()

    /** Latched once a program fails to compile, so the failure is not retried per surface. */
    private var refractFailed = false
    private var lightFailed = false

    /** True when this device can run the refraction tier at all. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun compile(src: String, what: String): RuntimeShader? =
        runCatching { RuntimeShader(src) }
            .onFailure { Log.e(TAG, "AGSL $what failed to compile; falling back", it) }
            .getOrNull()

    /** A fresh transmission shader per surface, never shared: uniforms live on the shader, last caller wins. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun newRefractShader(): RuntimeShader? {
        if (refractFailed) return null
        val rs = compile(REFRACT_AGSL, "refract")
        if (rs == null) refractFailed = true
        return rs
    }

    /** A fresh light shader for one surface. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun newLightShader(): RuntimeShader? {
        if (lightFailed) return null
        val rs = compile(LIGHT_AGSL, "light")
        if (rs == null) lightFailed = true
        return rs
    }

    /** The band after the corner-radius cap, shared so nothing disagrees; wider grows corner hemispheres. */
    fun effectiveBevel(params: GlassParams, w: Int, h: Int): Float {
        // 12dp with the surface's own density; a hard-coded density made the floor wrong off-device.
        val radiusCap = maxOf(params.cornerRadius, 12f * params.density)
        return params.bevelThickness
            .coerceAtMost(radiusCap)
            .coerceIn(1f, maxOf(1f, minOf(w, h) * 0.5f))
    }

    /** Geometry uniforms for both programs, in one place so the passes agree where the bevel is. */
    private fun setGeometry(rs: RuntimeShader, params: GlassParams, w: Int, h: Int, es: Float) {
        val bevel = effectiveBevel(params, w, h)
        // Everything divides by es, the pass's resolution divisor, here so both passes agree on the bevel.
        rs.setFloatUniform("size", w / es, h / es)
        val rr = params.cornerRadii
        if (rr != null && rr.size == 4) {
            rs.setFloatUniform("radii", rr[0] / es, rr[1] / es, rr[2] / es, rr[3] / es)
        } else {
            val r = params.cornerRadius / es
            rs.setFloatUniform("radii", r, r, r, r)
        }
        rs.setFloatUniform("bevel", bevel / es)
        rs.setFloatUniform("depth", (bevel * params.depthRatio / es).coerceAtLeast(0.01f))
        // Round the corner ridges over about a third of the band; capped, smoothing bulges the field ~k/4.
        rs.setFloatUniform("cornerSmooth", ((bevel * 0.35f) / es).coerceIn(2f, 64f))
        // Scaled by es like every other length, or a fade in full-res px covers the wrong rows.
        rs.setFloatUniform(
            "expand",
            params.edgeExpandLeft / es, params.edgeExpandTop / es,
            params.edgeExpandRight / es, params.edgeExpandBottom / es,
        )
        rs.setFloatUniform("fadeBottom", params.fadeBottomPx / es)
        rs.setFloatUniform("fadeTop", params.fadeTopOffsetPx / es, params.fadeTopLenPx / es)
    }

    /** Wrap one surface's transmission shader as an effect. Chain the blur after this, never before. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun buildRefractEffect(
        rs: RuntimeShader,
        params: GlassParams,
        w: Int,
        h: Int,
        es: Float = 1f,
    ): RenderEffect {
        applyRefractUniforms(rs, params, w, h, es)
        return RenderEffect.createRuntimeShaderEffect(rs, "content")
    }

    /** Transmission uniforms without the RenderEffect wrap, so bitmap-backed bubbles run the same program. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun applyRefractUniforms(
        rs: RuntimeShader,
        params: GlassParams,
        w: Int,
        h: Int,
        es: Float = 1f,
    ) {
        setGeometry(rs, params, w, h, es)
        rs.setFloatUniform("ior", params.ior)
        rs.setFloatUniform("dispersion", params.dispersion)
        rs.setFloatUniform("magnify", params.magnify)
        rs.setFloatUniform("maxDisplace", (params.maxDisplacePx / es).coerceAtLeast(0.5f))
        rs.setFloatUniform("transGamma", params.transGamma)
    }

    /**
     * Push one surface's light uniforms. Gains arrive as parameters: writing them into params would
     * rebuild the refraction chain per frame. flatInteriorColor must use the same fresnelStrength.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun applyLightUniforms(
        rs: RuntimeShader,
        params: GlassParams,
        w: Int,
        h: Int,
        specStrength: Float = params.specStrength,
        fresnelStrength: Float = params.fresnelStrength,
        density: Float = 1f,
        tintScale: Float = 1f,
        light1X: Float = params.lightX,
        light1Y: Float = params.lightY,
        light2X: Float = params.light2X,
        light2Y: Float = params.light2Y,
        pressX: Float = 0f,
        pressY: Float = 0f,
        pressAmp: Float = 0f,
        pressRadiusPx: Float = 0f,
    ) {
        // Always full resolution: this pass draws the rim, which the cost saving must not soften.
        setGeometry(rs, params, w, h, 1f)
        val c = params.tintColor
        rs.setFloatUniform(
            "tint",
            ((c shr 16) and 0xFF) / 255f,
            ((c shr 8) and 0xFF) / 255f,
            (c and 0xFF) / 255f,
            // A parameter like the two strengths, never a params write: a setter fires onChanged and rebuilds the effect chain.
            ((c ushr 24) and 0xFF) / 255f * tintScale.coerceIn(0f, 1f),
        )
        rs.setFloatUniform("specStrength", specStrength)
        rs.setFloatUniform("specPower", params.specPower)
        rs.setFloatUniform("specPower2", params.specPower2)
        rs.setFloatUniform("light1", light1X, light1Y)
        rs.setFloatUniform("light2", light2X, light2Y)
        rs.setFloatUniform("fresnelStrength", fresnelStrength)
        rs.setFloatUniform("fresnelPower", params.fresnelPower)
        rs.setFloatUniform("innerShadow", params.innerShadow)
        rs.setFloatUniform("sheenStrength", params.sheenStrength)
        rs.setFloatUniform("sheenPower", params.sheenPower)
        rs.setFloatUniform("light3", params.light3X, params.light3Y)
        rs.setFloatUniform("microAmp", params.microAmp)
        // dp to px here, the one non-px length; density defaults to 1 so a Context-less caller is honest.
        rs.setFloatUniform("microScale", (params.microScaleDp * density).coerceAtLeast(1f))
        rs.setFloatUniform("pressPos", pressX, pressY)
        rs.setFloatUniform("pressAmp", pressAmp)
        rs.setFloatUniform("pressR", pressRadiusPx)
    }
}
