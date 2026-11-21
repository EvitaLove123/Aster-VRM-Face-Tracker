# Aster VRM Face Tracker

Android-native face tracking and VRM preview prototype.

## Implemented in this prototype

- Android front-camera preview using CameraX.
- MediaPipe Face Landmarker in video mode.
- Live left/right blink, jaw opening, smile, and raised-brow scores.
- Approximate head yaw, pitch, and roll from face landmarks.
- Embedded Three.js and three-vrm preview in Android WebView.
- Local VRM file picker and VRM 0.x / 1.0 loading through three-vrm.
- First-pass mapping to blinkLeft, blinkRight, aa, happy, and surprised expressions.
- Basic head-bone rotation mapping.
- GitHub Actions debug APK build.

## Build APK

Open Actions, select Build Android APK, and press Run workflow. After the run completes, download the Aster-VRM-Face-Tracker-debug artifact.

The workflow downloads the MediaPipe face-landmarker model during build. The 3D runtime loads from jsDelivr, so internet access is required for the first preview.

## Prototype limitations

Expression mapping is a first pass; some VRM models may not contain matching expression presets. Head pose is estimated from 2D landmarks, not a full 3D pose solver. Recording, calibration, smoothing controls, and offline runtime bundling are not implemented. A successful CI build and device test are still required.
