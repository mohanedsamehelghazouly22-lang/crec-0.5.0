# Clash Overlay Assistant v0.5.0

Observe-only Android overlay for Clash Royale. The app never taps the game, sends game input, injects code, modifies memory, or bypasses security.

## v0.5 fixes
- Android 14+ MediaProjection callback registration before `createVirtualDisplay()`.
- One-shot MediaProjection session handling; projection is never reused after stop.
- Robust foreground-service startup and failure handling.
- Capture heartbeat/FPS shown in the overlay so a dead capture pipeline is visible.
- Screen-share cancellation is reported as cancellation, not a crash.
- Safer RGBA ImageReader -> Bitmap conversion with stride validation.
- Named `GameState` construction to eliminate Long/Float positional-argument mistakes.
- Overlay now reports capture/model status and detected opponent deck entries.
- `START_NOT_STICKY` prevents Android from restarting a service without a fresh user-granted projection token.
- TFLite output handling is safer for float/quantized outputs and never invents a card when the model is missing.
- GitHub Actions workflow builds `app-debug.apk` with Gradle 8.9/JDK 17.

## Important: recognition model
A real classifier is required for automatic card recognition. The app intentionally does **not** fake recognition when `app/src/main/assets/models/card_classifier.tflite` is absent or invalid.

The model must match the labels in `app/src/main/assets/models/labels.txt` and the input/output contract documented in `docs/ML_MODEL_CONTRACT.md`.

## Build
Run the GitHub Actions workflow `Build Android APK`. Download the `clash-overlay-assistant-debug` artifact and extract `app-debug.apk`.
