package io.github.offlineglass.ui

/** Debug-only main-thread probe. No manager settings, pages or target apps change. */
internal fun verifyManagerBottomBarMotion(): android.os.Bundle {
    val result = android.os.Bundle()
    val latch = java.util.concurrent.CountDownLatch(1)
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
    main.post {
        val motion = ManagerBottomBarMotion(0f, 3)
        var done = false
        fun finish(error: Throwable? = null) {
            if (done) return
            done = true; motion.dispose()
            if (error == null) result.putString("result", "PASS")
            else result.putString("error", error.stackTraceToString())
            latch.countDown()
        }
        fun stage(delay: Long, action: () -> Unit) {
            main.postDelayed({ if (!done) try { action() } catch (e: Throwable) { finish(e) } }, delay)
        }
        motion.press(true)
        motion.dragTo(1f, 100f)
        stage(160L) {
            check(motion.scaleY > 1.02f && motion.pressProgress > 0.5f) { "Press did not enlarge" }
            check(motion.velocity > 0.001f) { "Drag did not feed the public velocity spring" }
            result.putFloat("heldScaleY", motion.scaleY)
            result.putFloat("dragVelocity", motion.velocity)
            motion.dragTo(2f, 100f)
            stage(120L) {
                motion.releaseTo(2f, 2f)
                stage(520L) {
                    check(kotlin.math.abs(motion.position - 2f) < 0.01f)
                    check(kotlin.math.abs(motion.scaleY - 1f) < 0.03f && motion.pressProgress < 0.03f) { "Release did not settle" }
                    check(motion.restingTintProgress > 0.99f) { "Release tint did not return" }
                    motion.followSelection(0f)
                    stage(800L) {
                        check(kotlin.math.abs(motion.position) < 0.01f && kotlin.math.abs(motion.scaleY - 1f) < 0.03f) { "Tap did not arrive and retract" }
                        motion.press(true); motion.dragTo(1f, 50f)
                        motion.press(true) // Rapid re-grab cancels the previous release phase.
                        motion.releaseTo(0f, 0.4f)
                        stage(700L) {
                            check(kotlin.math.abs(motion.position) < 0.01f && motion.pressProgress < 0.03f) { "Cancel/re-grab left a pressed lens" }
                            finish()
                        }
                    }
                }
            }
        }
    }
    if (!latch.await(5L, java.util.concurrent.TimeUnit.SECONDS)) result.putString("error", "Timed out waiting for Android display frames")
    return result
}
