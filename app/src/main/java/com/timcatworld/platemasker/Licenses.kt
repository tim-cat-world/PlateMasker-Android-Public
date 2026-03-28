package com.timcatworld.platemasker

object LicenseData {
    val OSS_LICENSES = listOf(
        LicenseInfo(
            name = "Ultralytics YOLO11",
            copyright = "Copyright (c) Ultralytics Inc.",
            licenseName = "AGPL-3.0 License",
            url = "https://github.com/ultralytics/ultralytics",
            description = "This app uses YOLO11 for license plate detection. Under AGPL-3.0, the source code of this application is available at: https://github.com/tim-cat-world/PlateMasker-Android-Public"
        ),
        LicenseInfo(
            name = "OpenCV",
            copyright = "Copyright (c) Intel Corporation / Itseez Inc. / Willow Garage / OpenCV Foundation",
            licenseName = "Apache License 2.0",
            url = "https://opencv.org/"
        ),
        LicenseInfo(
            name = "LiteRT (TensorFlow Lite)",
            copyright = "Copyright 2024 The TensorFlow Authors.",
            licenseName = "Apache License 2.0",
            url = "https://ai.google.dev/edge/litert"
        ),
        LicenseInfo(
            name = "Android Jetpack & Compose",
            copyright = "Copyright (c) The Android Open Source Project",
            licenseName = "Apache License 2.0",
            url = "https://developer.android.com/jetpack"
        )
    )
}

data class LicenseInfo(
    val name: String,
    val copyright: String,
    val licenseName: String,
    val url: String,
    val description: String? = null
)
