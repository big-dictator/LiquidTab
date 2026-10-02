# LiquidTab 2.0 development handoff

## Source-of-truth map

- Application identity, UI, Xposed entry point: `app/src/main/`.
- One application's dedicated code: `app/src/main/java/io/github/offlineglass/hook/adapters/<key>/`.
- Registry and formal scope: `targets/TargetSpecRegistry.kt`, `targets/AppCatalog.kt`, and `app/src/main/assets/xposed_scope`.
- Shared hook lifecycle and host: `hook/HookEntry.kt`, `HookCoordinator.kt`, `TargetHooks.kt`, `NavigationFinder.kt`, `GlassInstaller.kt`, `GlassHostLayout.kt`.
- Shared drawing algorithms: `rendering/`.
- Shared configuration contract and persistence: `config/`.
- Module UI and secondary-page navigation: `ui/`.
- Build/version/dependencies: root Gradle files and `app/build.gradle.kts`.

## Application adapter directories

The registry currently knows these adapter keys:

`amap`, `bilibili`, `bilibili_in`, `cainiao`, `douyin`, `hellobike`, `jd`,
`meituan`, `meituan_main`, `mi_calendar`, `mi_community`, `mi_file_manager`,
`mi_gallery`, `mi_health`, `mi_market`, `mi_messages`, `mi_notes`, `mi_phone`,
`mi_theme`, `mihome`, `netease`, `pdd`, `qqmusic`, `system_picker`, `taobao`,
`tieba`, `wechat`, `weibo`, `xhs`, `xianyu`, `xjtu`, `youtube`, `zhihu`.

Only entries in `AppCatalog.targets` are active in the 2.0 module UI. The
following are excluded from the formal scope: phone, calendar, notes, messages,
HelloBike, file manager, gallery, theme store, and system picker. The LSPosed
scope list must exactly match active target package names. Do not remove an
adapter directory merely because it is excluded from the release; treat it as
archived development code until explicitly retired.

## Safe handoff workflow

1. Before changing an app, read its `WORKSPACE.md` if present and inspect the
   target adapter's current package/version assumptions.
2. Keep app-specific discovery, navigation, hook signals, visual policy, and
   page exceptions inside that app's directory whenever the adapter contract
   supports it. Do not move app-specific branches into a different adapter.
3. Modify shared rendering only for genuinely cross-app behavior. Record the
   affected rendering path and check representative adapters that use it.
4. Keep `AppCatalog.targets` and `app/src/main/assets/xposed_scope` consistent.
   Verify both before release.
5. Never copy APKs, decompilation trees, device dumps, logs, screenshots,
   credentials, local SDK paths, or signing keys into the public source tree.
6. Build with `./gradlew.bat :app:assembleRelease`; compilation alone is not
   runtime verification. State which target versions and scenarios were
   actually tested.

## Back and image presentation notes

- The app-list detail navigation in both `MaterialUi.kt` and `MiuixUi.kt` uses a
  normal pop transition. Its `predictivePopTransitionSpec` is intentionally
  `None`; this applies only to the app-list detail stack.
- Donation QR layout in `ManagerInfoPages.kt` uses `ContentScale.Fit` plus the
  image's square aspect ratio. Do not switch it to `Crop` or a crop-based
  content scale.

## Release invariants

- Keep `applicationId` as `io.github.offlineglass` for in-place upgrades.
- Increment both version values for future releases; 2.0 is code 217.
- Never put signing credentials in Git. Keep the previous 1.0.0 baseline in
  its separate archive; do not overwrite it during a release.
- Bottom progressive/gradient blur is disabled globally. Do not re-enable or
  invent per-app bottom gradient blur as part of app-specific adaptations.

Run the portable structure/scope check from the repository root with:
`pwsh -NoProfile -File tools/verify-adapter-workspaces.ps1`.
