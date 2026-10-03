# Third-party software notices

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

The Xposed declarations in `xposed-stubs/` are minimal compile-time API
signatures and are excluded from the APK. The module relies on the runtime
provided by the user's LSPosed installation; LSPosed itself is not bundled.

Dependency versions are declared in `app/build.gradle.kts`. Their upstream
license texts and notices remain authoritative. No dependency source tree or
binary archive is redistributed in this repository.

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
