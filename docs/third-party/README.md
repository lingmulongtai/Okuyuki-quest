# Native HEIC dependencies

libheif v1.23.6 and libde265 v1.1.3 are unmodified LGPL libraries, dynamically linked into the arm64 APK.
Their complete corresponding source archives accompany every GitHub alpha release. CMake build settings
and SHA-256 pins are in core/stereo/src/main/cpp/CMakeLists.txt. No x265/encoder is included.

Users may modify/rebuild the LGPL libraries, replace the APK native libraries and re-sign/install the APK
on their own devices. There is no DRM or integrity restriction on this app. Debug builds and release
source are available for relinking and debugging library changes. The supplied release signature verifies
the distributed artifact, and is not required for running a modified build.

Source: https://github.com/strukturag/libheif/tree/v1.23.6 and
https://github.com/strukturag/libde265/tree/v1.1.3 . Full license text is included beside this file.

Android's MediaMetadataRetriever cannot expose HEIF ster item IDs. It is not used to guess eye indices.
The decoder reads the standard ster group, decodes its explicit left/right handles sequentially and
limits each displayed eye to a 2048-pixel long edge to bound bitmap memory. Actual decoding/eye acceptance
requires a Samsung stereo HEIC on Quest hardware.

## Meta Spatial SDK

Quest immersive video uses Meta Spatial SDK 0.14.0 (base, toolkit and VR). The SDK is licensed under the [Meta Platform Technologies SDK License Agreement](https://developers.meta.com/vr/licenses/oculussdk/); see [Meta Spatial SDK packages](https://developers.meta.com/vr/documentation/spatial-sdk/spatial-sdk-packages/).
