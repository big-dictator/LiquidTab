# Third-party software notices

## Rendering attribution: Kyant0 / AndroidLiquidGlass

Special thanks to Kyant (kyant0), author of AndroidLiquidGlass (Backdrop):
https://github.com/Kyant0/AndroidLiquidGlass

LiquidTab's liquid-glass refraction implementation draws on and adapts this
project's open-source rendering work. These upstream contributions are not
claimed as original LiquidTab work. AndroidLiquidGlass is licensed under the
Apache License 2.0; the license text is included at `licenses/Apache-2.0.txt`.
Attribution does not mean that the complete upstream library is bundled, or
that all blur, highlighting, native View integration and app adaptation code
originates from this one project. Preserve upstream copyright and license
headers wherever upstream source is adapted.


LiquidTab's own source code is distributed under GNU GPL v3; see `LICENSE`.
The following direct dependencies are distributed under Apache License 2.0:

| Component | Use | License |
|---|---|---|
| AndroidX Activity Compose and Jetpack Compose (Animation, Foundation, Material, Material 3, UI, Tooling Preview) | Module UI and animation | Apache-2.0 |
| AndroidX Navigation 3 Runtime/UI and Navigation Event Compose | Module secondary-page navigation | Apache-2.0 |
| AndroidX Graphics Shapes | Smoothed corner geometry | Apache-2.0 |
| Miuix Compose UI, Icons, Preference, Blur, Squircle and Navigation 3 | MIUIX-style module UI and shared effects | Apache-2.0 |
| LSPosed AndroidHiddenApiBypass 6.1 | Access to Android hidden APIs where required | Apache-2.0 |

The Apache License 2.0 text is included at `licenses/Apache-2.0.txt`.

The native View lens bridge in `rendering/KyantLensShader.kt` includes an
adaptation of Kyant0/AndroidLiquidGlass's rounded-rectangle refraction shader,
Copyright 2025 Kyant, Apache-2.0. Upstream:
https://github.com/Kyant0/AndroidLiquidGlass (backdrop/internal/Shaders.kt).
The adaptation exposes the shader to Android RenderEffect instead of Compose.

The module compiles against the modern libxposed API and relies on the runtime
provided by the user's LSPosed installation; LSPosed itself is not bundled.

The optional iOS-style manager controls in `ui/ios/` adapt the upstream
catalog LiquidToggle, LiquidSlider and DampedDragAnimation (Kyant,
Apache-2.0). They retain the upstream drag/press animation and control geometry,
with a Miuix Backdrop bridge, Android monotonic timing, current-state callbacks,
accessibility semantics and cancellation handling. Continuous card outlines
reuse LiquidTab's shared bottom-bar shape. Preserve the source headers.

`ui/RootPermissionNotice.kt` adapts the Backdrop Catalog `DialogContent.kt`
from https://github.com/Kyant0/AndroidLiquidGlass (Kyant, Apache-2.0).
Its glass material, lens parameters and capsule-button layout are bridged to
Miuix Backdrop; LiquidTab adds bounded background Root checks and a same-window
modal overlay. Preserve its attribution when modifying or redistributing it.

Dependency versions are declared in `app/build.gradle.kts`. Their upstream
license texts and notices remain authoritative.
