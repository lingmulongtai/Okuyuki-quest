# Okuyuki Quest

Galaxyの3D写真を、同じWi-FiにいるMeta Questから見るためのビューア。
写真はスマホに置いたまま、Questは必要な写真だけを一時的に受け取ります。

Companion: https://github.com/lingmulongtai/Okuyuki-android
Project: https://github.com/users/lingmulongtai/projects/2

## Build
JDK 17、Android SDK 36。`./gradlew assembleDebug`（Windows: `gradlew.bat`）。
WindowsではASCIIのパスにcloneまたはjunctionを作ってビルドしてください。

## Alpha status
仕様書の「最初の版」を対象とする開発中のアルファです。[範囲と実機チェック](docs/ALPHA-SCOPE.md)。
実機検証の完了を示すものではありません。v1.0の機能は上記の範囲と区別します。
元の仕様書に含まれる写真はこのリポジトリには公開しません。
