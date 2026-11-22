package io.github.offlineglass.hook.adapters.chatgpt

import android.graphics.Canvas
import android.graphics.Rect
import android.view.View

/** One verified producer capability shared by every material, including popup windows. */
internal object ChatGptHazeBackdrop {
    fun recordingKey(consumer: Any, retainTranslation: Boolean): Long? =
        ChatGptPageSource.recordingKey(consumer, retainTranslation)
    fun draw(canvas:Canvas,anchor:View,consumer:Any,screen:Rect,localWidth:Float,localHeight:Float,padding:Int)=
        ChatGptPageSource.draw(canvas,consumer,screen,localWidth,localHeight,padding)
}
