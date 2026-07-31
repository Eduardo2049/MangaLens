# Fix Capture Failure and API 404 (Year 2026 Compatibility)

The app is failing to capture the screen ("Falha total") and receiving 404 from Gemini. This is because:
1.  **Retired Model**: In July 2026, `gemini-1.5-flash` is retired. We must use `gemini-3.5-flash`.
2.  **Capture Reliability**: The `VirtualDisplay` setup might be incompatible with the current device (Miui rodin).
3.  **API Version**: We should use the `v1` endpoint for modern stable models.

## User Review Required

> [!IMPORTANT]
> I am moving the app to the **Gemini 3.5 Flash** model. This is the current stable standard for 2026.
> I am also adjusting the screen capture flags to improve compatibility.

## Proposed Changes

### [GeminiVisualTranslator](file:///C:/Users/eduardo.asousa/Projetos/Pessoal/MangaLens/app/src/main/java/com/example/data/remote/GeminiVisualTranslator.kt)

#### [MODIFY] [GeminiVisualTranslator.kt](file:///C:/Users/eduardo.asousa/Projetos/Pessoal/MangaLens/app/src/main/java/com/example/data/remote/GeminiVisualTranslator.kt)

- **Update Model**: Change `gemini-1.5-flash` to `gemini-3.5-flash`.
- **Update Endpoint**: Change `v1beta` to `v1` in the request URL.

### [ScreenCaptureManager](file:///C:/Users/eduardo.asousa/Projetos/Pessoal/MangaLens/app/src/main/java/com/example/service/ScreenCaptureManager.kt)

#### [MODIFY] [ScreenCaptureManager.kt](file:///C:/Users/eduardo.asousa/Projetos/Pessoal/MangaLens/app/src/main/java/com/example/service/ScreenCaptureManager.kt)

- **Fix VirtualDisplay Flags**: Use `DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` combined with `VIRTUAL_DISPLAY_FLAG_PUBLIC` or simply `0` to see if it fixes the empty frames.
- **Improve Buffer Handling**: Increase `ImageReader` max images to `3` to avoid blocking.
- **Update Metrics Logic**: Use modern `WindowMetrics` for devices on Android 11+.

## Verification Plan

### Automated Tests
- Build check.

### Manual Verification
1. Open Logcat with `MangaLens` filter.
2. Verify `Capture: OK` appears.
3. Verify `API: Resposta recebida (Código: 200)` appears.
4. Verify translations show up on screen.
