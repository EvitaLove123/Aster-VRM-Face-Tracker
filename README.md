# Aster VRM Face Tracker

Android-native face tracking and VRM preview prototype.

## Implemented

- Android front-camera preview using CameraX.
- MediaPipe Face Landmarker in video mode.
- Live left/right blink, jaw opening, smile, and raised-brow scores.
- Approximate head yaw, pitch, and roll from face landmarks.
- Embedded Three.js and three-vrm preview in Android WebView.
- Import VRM 0.x / 1.0 from Android's system document picker.
- Persist read access to the last selected VRM document and reopen it with the Open saved button.
- First-pass mapping to blinkLeft, blinkRight, aa, happy, and surprised expressions.
- Basic head-bone rotation mapping.
- GitHub Actions debug APK build.

## Privacy and model handling

- No VRoid Hub SDK, account login, OAuth, or cloud model browsing.
- The app only accesses a model after the user chooses it in Android's document picker.
- Android's persisted document permission allows the app to reopen the selected file. The model remains in its original storage location; the app does not upload it.

## Build APK

Open Actions, select Build Android APK, and press Run workflow. After the run completes, download the Aster-VRM-Face-Tracker-debug artifact.

The workflow downloads the MediaPipe face-landmarker model during build. The 3D runtime currently loads Three.js and three-vrm from jsDelivr, so internet access is required for the 3D preview.

## Prototype limitations

Expression mapping is a first pass; some VRM models may not contain matching expression presets. Head pose is estimated from 2D landmarks, not a full 3D pose solver. Model file access depends on the original file remaining available to Android. Offline 3D runtime, calibration, smoothing controls, and device-level testing are not yet complete.
