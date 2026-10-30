# Okuyuki Quest

Galaxyの3D写真を、同じWi-FiにいるMeta Questから見るためのビューア。
写真はスマホに置いたまま、Questは必要な写真だけを一時的に受け取ります。

Companion: https://github.com/lingmulongtai/Okuyuki-android
Project: https://github.com/users/lingmulongtai/projects/2

## GalaxyとQuest 3Sで試す

**開発版で360°写真・動画の共有を試す場合:** [Galaxy・Quest実機テスト用プレビュー](docs/DEVICE-PREVIEW.md)をご覧ください。

配布中のアルファは **v0.1.0-alpha.12-preview** です。Galaxyは **Android 16** が必要です。

| 入れる端末 | APKの直接ダウンロード | 保存先 |
| --- | --- | --- |
| Galaxy | [Android版APK](https://github.com/lingmulongtai/Okuyuki-android/releases/download/v0.1.0-alpha.12-preview/Okuyuki-android-v0.1.0-alpha.12-preview.apk) | Galaxy |
| Quest 3S／3 | [Quest版APK](https://github.com/lingmulongtai/Okuyuki-quest/releases/download/v0.1.0-alpha.12-preview/Okuyuki-quest-v0.1.0-alpha.12-preview.apk) | Windows PC |

1. **Galaxy**: Android版APKを開き、インストール元の許可を与えて入れます。Okuyukiの写真アクセスを許可し、ライブラリに試したい3D写真があることを確認します。
2. **Quest**: Meta Horizonアプリで開発者モードをオンにし、PCとUSB接続してQuest内のUSBデバッグを許可します。PCからADBでQuest版APKを入れ、アプリライブラリーの「提供元不明」から **Okuyuki Quest** を開きます。
3. **接続**: 両端末を同じWi-Fiに接続します。Galaxyの「つなぐ」タブで **「Quest・テレビとつなぐ」をオン** にし、Questでスマホを選びます。見つからない場合はGalaxyの **IPアドレス:ポート** をQuestに入力します。
4. **承認と閲覧**: 両画面の4桁の番号が同じことを確認し、Galaxyで「許可」を押します。Questの一覧から写真を選びます。終了時はQuestアプリを閉じ、Galaxyの待機をオフにします。

**[初回の設定から詳しく読む → インストール・接続ガイド](docs/INSTALL.md)**

ガイドにはMetaアカウントの初回設定、Windows用ADBとドライバ、コピーできるPowerShellコマンド、Galaxyの自動ブロッカー、接続できない場合の確認方法があります。
通常の写真は3D一覧の対象外です。GalaxyのSamsungステレオHEICの原本を使ってください。
実機での原本デコード・左右の立体表示は未検証です。アルファの通信は承認付きHTTPなので、信頼できる自宅Wi-FiまたはGalaxyのテザリングで試してください。

## Build
JDK 17、Android SDK 36。`./gradlew assembleDebug`（Windows: `gradlew.bat`）。
WindowsではASCIIのパスにcloneまたはjunctionを作ってビルドしてください。

## Alpha status
仕様書の「最初の版」を対象とするアルファです。[範囲と実機チェック](docs/ALPHA-SCOPE.md)。
実機検証の完了を示すものではありません。v1.0の機能は上記の範囲と区別します。
元の仕様書に含まれる写真はこのリポジトリには公開しません。

[署名付きAPKのReleases](https://github.com/lingmulongtai/Okuyuki-quest/releases) · [インストールと接続](docs/INSTALL.md) · [リリース内容](docs/RELEASE-NOTES.md)

[検証記録](docs/VALIDATION.md)

### アプリの更新

起動時に新しいプレビュー版を確認し、安全性を検証したAPKをダウンロードします。「設定」から手動確認もできます。更新が見つかったらインストールを選び、OSの確認に従ってください。初回はOkuyukiからのインストールを許可する必要があります。alpha.9以前には更新機能がないため、alpha.9以前からは下記の手順で手動更新してください。Questのアプリ内インストーラーは実機未検証で、利用できない場合はPCから `adb install -r` してください。

GitHub API の利用上限に達した場合も、公開リリース情報とチェックサムを使って更新を確認します。alpha.10・alpha.11で更新エラーが続く場合は、この版を一度手動で上書きしてください。
