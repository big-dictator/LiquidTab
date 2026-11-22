package io.github.offlineglass.rendering

import android.graphics.*
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Bundle
import io.github.offlineglass.hook.GlassHostLayout
import java.io.File

/** Debug-only caller: reproducible GPU pixels, not merely AGSL compilation. */
internal object BlurEdgeProbe {
    fun run(directory: File): Bundle {
        directory.mkdirs()
        val old = render(16f, false)
        val fixed = render(16f, true)
        val zero = render(0f, false)
        val zeroFixed = render(0f, true)
        var zeroBeforeTransparent = 0
        var zeroAfterTransparent = 0
        for (y in 603..652) for (x in 630..870) {
            if (Color.alpha(zero.getPixel(x, y)) < 245) zeroBeforeTransparent++
            if (Color.alpha(zeroFixed.getPixel(x, y)) < 245) zeroAfterTransparent++
        }
        check(zeroAfterTransparent == 0) { "Zero blur still loses optical coverage" }
        zeroFixed.recycle()
        val dynamic = render(0f, false, fullSurface = 1f)
        var interiorChanges = 0
        for (y in 627..629) for (x in 650..850) {
            if (dynamic.getPixel(x, y) != zero.getPixel(x, y)) interiorChanges++
        }
        check(interiorChanges > 0) { "Full-surface optics did not reach the interior" }
        dynamic.recycle()
        val miniBefore = render(16f, false, fullSurface = 1f, spotify = true)
        val miniAfter = render(16f, true, fullSurface = 1f, spotify = true)
        var miniBeforeTransparent = 0
        var miniAfterTransparent = 0
        for (y in 603..652) for (x in 604..895) {
            val dx = when { x < 628 -> 628 - x; x > 872 -> x - 872; else -> 0 }
            val dy = y - 628
            if (dx * dx + dy * dy > 25 * 25 && dx != 0) continue
            if (Color.alpha(miniBefore.getPixel(x, y)) < 245) miniBeforeTransparent++
            if (Color.alpha(miniAfter.getPixel(x, y)) < 245) miniAfterTransparent++
        }
        miniBefore.recycle(); miniAfter.recycle()
        check(miniAfterTransparent == 0) { "Mini-player filtered sampling still has transparent pixels" }
        val pilot = render(16f, true, fullSurface = 1f, pilot = true)
        val interiorReference = render(0f, true, fullSurface = 1f)
        val centrePilot = render(0f, true, fullSurface = 1f, pilot = true)
        var pilotCentreChanges = 0
        for (y in 627..629) for (x in 650..850)
            if (centrePilot.getPixel(x, y) != interiorReference.getPixel(x, y)) pilotCentreChanges++
        interiorReference.recycle()
        centrePilot.recycle()
        check(pilotCentreChanges == 0) { "Rejected trial remains active after rollback" }
        val inactivePilot = render(16f, true, pilot = true)
        check(inactivePilot.sameAs(fixed)) { "Directional pilot changed inactive/static optics" }
        inactivePilot.recycle()
        var pilotTransparent = 0
        for (y in 603..652) for (x in 630..870) {
            if (Color.alpha(pilot.getPixel(x, y)) < 245) pilotTransparent++
        }
        pilot.recycle()
        check(pilotTransparent == 0) { "Directional rim pilot lost filtered coverage" }
        val directions = listOf(0.45f to 0f, -0.45f to 0f, 0f to 0.45f, 0f to -0.45f,
            0.32f to 0.32f, -0.32f to 0.32f, -0.32f to -0.32f, 0.32f to -0.32f)
        directions.forEach { (xTilt, yTilt) ->
            val frame = render(16f, true, fullSurface = 1f, pilot = true, tiltX = xTilt, tiltY = yTilt)
            try {
                for (y in 603..652) for (x in 630..870)
                    check(Color.alpha(frame.getPixel(x, y)) >= 245) { "Pilot coverage failed at $xTilt,$yTilt" }
            } finally { frame.recycle() }
        }
        var beforeReversals = 0
        var afterReversals = 0
        for (tilt in listOf(-0.45f, 0.45f)) {
            val beforeRamp = render(16f, true, 1f, pilot = true, tiltX = tilt, tiltY = 0f,
                monotone = false, ramp = true)
            val afterRamp = render(16f, true, 1f, pilot = true, tiltX = tilt, tiltY = 0f,
                monotone = true, ramp = true)
            for (x in 650..850 step 10) for (y in 615..640) {
                if (Color.green(beforeRamp.getPixel(x, y + 1)) < Color.green(beforeRamp.getPixel(x, y))) beforeReversals++
                if (Color.green(afterRamp.getPixel(x, y + 1)) < Color.green(afterRamp.getPixel(x, y))) afterReversals++
            }
            beforeRamp.recycle(); afterRamp.recycle()
        }
        check(afterReversals == beforeReversals) { "Rollback does not match baseline mapping" }
        var cornerMappingReversals = 0
        val cornerMappingRows = StringBuilder()
        for (tilt in listOf(-0.45f, 0.45f)) {
            val cornerRamp = render(0f, true, 1f, tiltX = tilt, tiltY = 0f, ramp = true)
            try {
                cornerMappingRows.append("tilt=").append(tilt).append(":")
                for (row in listOf(604, 612, 628, 644, 652)) {
                    cornerMappingRows.append(" y=").append(row - 600).append("[")
                    for (x in 628..690 step 6)
                        cornerMappingRows.append(Color.green(cornerRamp.getPixel(x, row))).append(',')
                    cornerMappingRows.append("]")
                }
                // Test the transition too: ordering at a single end-cap column
                // misses upper/lower inversion farther along the bar.
                // Retain measurements after rollback, but do not enforce the
                // rejected cap-field shape as an acceptance criterion.
                // Actual rounded end caps, previously omitted by the centre-only check.
                for (x in (610..621) + (878..889)) for (y in 616..639) {
                    if (Color.green(cornerRamp.getPixel(x, y + 1)) <
                        Color.green(cornerRamp.getPixel(x, y))) cornerMappingReversals++
                    check(Color.alpha(cornerRamp.getPixel(x, y)) >= 245) {
                        "Rounded cap lost sample coverage"
                    }
                }
            } finally { cornerRamp.recycle() }
        }
        // Shape metrics are diagnostics, not visual acceptance or a reason to
        // silently re-enable the rejected corner mapping.
        listOf("before" to old, "after" to fixed, "zero" to zero).forEach { (name, bitmap) ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        var changed = 0
        var oldTransparent = 0
        var newTransparent = 0
        // Stay inside the visible mask, excluding antialiased silhouette pixels.
        for (y in 603..652) for (x in 604..895) {
            val dx = when { x < 628 -> 628 - x; x > 872 -> x - 872; else -> 0 }
            val dy = y - 628
            if (dx * dx + dy * dy > 25 * 25 && dx != 0) continue
            val a = old.getPixel(x, y)
            val b = fixed.getPixel(x, y)
            if (a != b) changed++
            if (Color.alpha(a) < 245) oldTransparent++
            if (Color.alpha(b) < 245) newTransparent++
        }
        old.recycle(); fixed.recycle(); zero.recycle()
        return Bundle().apply {
            putInt("changedPixels", changed)
            putInt("beforeTransparent", oldTransparent)
            putInt("afterTransparent", newTransparent)
            putInt("zeroBeforeTransparent", zeroBeforeTransparent)
            putInt("zeroAfterTransparent", zeroAfterTransparent)
            putInt("dynamicInteriorChangedPixels", interiorChanges)
            putInt("miniBeforeTransparent", miniBeforeTransparent)
            putInt("miniAfterTransparent", miniAfterTransparent)
            putInt("pilotTransparent", pilotTransparent)
            putInt("pilotDirectionsChecked", directions.size)
            putInt("pilotCentreChangedPixels", pilotCentreChanges)
            putInt("beforeMappingReversals", beforeReversals)
            putInt("afterMappingReversals", afterReversals)
            putInt("cornerMappingReversals", cornerMappingReversals)
            putString("cornerMappingRows", cornerMappingRows.toString())
            putString("images", directory.absolutePath)
        }
    }

    private fun render(radius: Float, deferred: Boolean, fullSurface: Float = 0f, spotify: Boolean = false, pilot: Boolean = false,
                       tiltX: Float = 0.45f, tiltY: Float = 0.45f, monotone: Boolean = true, ramp: Boolean = false): Bitmap {
        val w = 1536; val h = 1200
        val pad = 48
        val source = RenderNode("Blur-edge-input")
        val sw = 300 + pad * 2; val sh = 56 + pad * 2
        val input = source.beginRecording(sw, sh)
        val paint = Paint()
        if (ramp) {
            paint.shader = LinearGradient(0f, 0f, 0f, sh.toFloat(), Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP)
            input.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), paint)
        } else for (y in 0 until sh step 8) for (x in 0 until sw step 8) {
            paint.color = if ((x / 8 + y / 8) % 2 == 0) Color.WHITE else Color.BLACK
            input.drawRect(x.toFloat(), y.toFloat(), (x + 8).toFloat(), (y + 8).toFloat(), paint)
        }
        source.endRecording()
        val shader = if (spotify) PostureGlass.shader(FlowingLiquid.kyantLensSource(KYANT_LENS_SHADER))
            else RuntimeShader(GlassHostLayout.ROUNDED_RECT_REFRACTION_SHADER)
        shader.setFloatUniform("size", 300f, 56f)
        shader.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())
        shader.setFloatUniform("cornerRadii", 28f, 28f, 28f, 28f)
        shader.setFloatUniform("refractionHeight", 24f)
        shader.setFloatUniform("refractionAmount", -30f)
        if (spotify) {
            shader.setFloatUniform("postureTilt", 0f, 0f)
            shader.setFloatUniform("flowTilt", tiltX, tiltY)
        } else {
            shader.setFloatUniform("tiltBias", tiltX, tiltY)
            shader.setFloatUniformExact("flowingLiquidEnabled", 1f)
            shader.setFloatUniformExact("unifiedDropletPilot", 0f)
            shader.setFloatUniformExact("directionalRimPilot", if (pilot) 1f else 0f)
            shader.setFloatUniformExact("monotoneFlowPilot", if (pilot && monotone) 1f else 0f)
        }
        shader.setFloatUniformExact("flowMotionEnabled", 1f)
        shader.setFloatUniform("fullSurfaceWeight", fullSurface)
        if (deferred) shader.setFloatUniform("safeSampleBounds", pad + 0.5f, pad + 0.5f,
            pad + 299.5f, pad + 55.5f)
        source.setRenderEffect(RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(shader, "content"), NativeBackdropBlur.effect(radius)))
        val root = RenderNode("Blur-edge-probe")
        root.setPosition(0, 0, w, h)
        val canvas = root.beginRecording(w, h)
        canvas.translate(600f, 600f)
        val path = Path().apply { addRoundRect(0f, 0f, 300f, 56f, 28f, 28f, Path.Direction.CW) }
        source.setPosition(-pad, -pad, 300 + pad, 56 + pad)
        canvas.clipPath(path)
        canvas.clipRect(0f, 0f, 300f, 56f)
        canvas.drawRenderNode(source)
        root.endRecording()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(root)
            renderer.setOpaque(false)
            val status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            check(status and HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND == 0)
            var image = reader.acquireLatestImage()
            for (attempt in 0 until 100) {
                if (image != null) break
                Thread.sleep(10)
                image = reader.acquireLatestImage()
            }
            val frame = checkNotNull(image) { "No rendered probe frame: $status" }
            frame.use {
                val buffer = checkNotNull(it.hardwareBuffer)
                buffer.use {
                    val hardware = checkNotNull(Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)))
                    return hardware.copy(Bitmap.Config.ARGB_8888, false).also { hardware.recycle() }
                }
            }
        } finally {
            renderer.destroy()
            reader.close()
            source.discardDisplayList(); root.discardDisplayList()
        }
    }
}
