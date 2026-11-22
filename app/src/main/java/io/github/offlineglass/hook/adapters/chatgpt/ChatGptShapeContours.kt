package io.github.offlineglass.hook.adapters.chatgpt

/** Native Shape.createOutline must not be hooked: ChatGPT inner shadows accept
 * Rounded outlines only. The consumer material owns its own inward continuous
 * mask and supplies that same contour to the shared refraction shader. */
internal object ChatGptShapeContours
