# PlateMasker

PlateMasker is an Android application that automatically detects license plates in images and applies protection (blur, solid color, etc.) to ensure privacy.

## Features
- **Automatic Detection:** High-speed license plate detection using AI.
- **Dynamic Orientation:** Supports both portrait and landscape modes during editing, adapting to your device's rotation.
- **Image Manipulation:** Rotate the source image anytime to ensure the best orientation for detection and editing.
- **Full Re-detection:** One-tap button to re-run AI detection on the current image orientation.
- **Precise Refinement:** Quadrangle refinement for accurate masking of tilted plates.
- **Masking Options:** Blur, Average Color, Solid Color, and Custom Image overlays.
- **Region Support:** Optimized for various plate formats (Japan, EU, USA, etc.).
- **Manual Tools:** Easily add or adjust mask areas with intuitive touch controls.

## Optimization
- Optimized for mobile devices with efficient memory management for high-resolution images.
- Smooth editing experience with real-time mask processing.

## Technology Stack
- **AI Engine:** Ultralytics YOLO11 (Running on LiteRT / TensorFlow Lite)
- **Image Processing:** OpenCV for Android
- **UI Framework:** Jetpack Compose
- **Language:** Kotlin

## License

This project is licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**. 

### Attribution
- **Ultralytics YOLO11:** This application uses YOLO11 for license plate detection. YOLO11 is developed by Ultralytics and is licensed under AGPL-3.0. For more details, visit [Ultralytics GitHub](https://github.com/ultralytics/ultralytics).

### Source Code Availability
In accordance with the AGPL-3.0 license, the source code for this application is available at:
[https://github.com/tim-cat-world/PlateMasker-Android.git](https://github.com/tim-cat-world/PlateMasker-Android.git)

Other libraries used in this project are subject to their respective licenses (e.g., Apache License 2.0 for OpenCV and LiteRT).
