# Implementation Plan - Technical Refactoring and Fixes

I will perform a series of refactorings to improve the project's stability, modularity, and adherence to Android best practices.

## User Review Required

> [!WARNING]
> **API 24 vs 26:** I will re-add guards for API 26 features. This means users on Android 7.0/7.1 will experience degraded functionality (no notification channel description, legacy overlay types). If you'd rather drop support for Android 7, I can bump the `minSdk` to 26 instead.
> **Logic Extraction:** I will create a new class for screen capture. This changes the internal structure of `MangaOverlayService`.

## Proposed Changes

### [Core Refactoring]

#### [NEW] [ScreenCaptureManager.kt](file:///C:/Users/eduardo.asousa/Projetos/Pessoal/MangaLens/app/src/main/java/com/example/service/ScreenCaptureManager.kt)
- Move all `MediaProjection`, `ImageReader`, and `VirtualDisplay` logic here.
- Handle bitmap capturing and recycling in an isolated way.

#### [MODIFY] [MangaOverlayService.kt](file:///C:/Users/eduardo.asousa/Projetos/Pessoal/MangaLens/app/src/main/java/com/example/service/MangaOverlayService.kt)
- Use `ScreenCaptureManager`.
- Re-add `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)` guards for `TYPE_APPLICATION_OVERLAY`.
- Replace `Thread.sleep` with `delay`.
- Clean up `WindowManager` additions to avoid "already added" or "not attached" crashes.

#### [MODIFY] [GeminiVisualTranslator.kt](file:///C:/Users/eduardo.asousa/Projetos/Pessoal/MangaLens/app/src/main/java/com/example/data/remote/GeminiVisualTranslator.kt)
- Improve JSON parsing robustness.
- Ensure `Result.failure` includes more context on parsing errors.

### [Build Configuration]

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/eduardo.asousa/Projetos/Pessoal/MangaLens/app/build.gradle.kts)
- Ensure all dependencies are correctly grouped and there are no stray comments that might confuse the build system.

## Verification Plan

### Automated Tests
- Run `analyze_file` on all components.
- Verify no new lint warnings about API compatibility.

### Manual Verification
- Deploy to an emulator/device.
- Verify the floating button still appears and drags correctly.
- Verify Manual and Dynamic translation modes still work with the new `ScreenCaptureManager`.
