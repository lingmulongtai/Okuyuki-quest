# アルファの検証記録

2026-10-12、仕様書v0.3の「最初の版」を対象とする `0.1.0-alpha.1`。

## 自動チェック
WindowsのJDK 17／SDK 36／NDK 28.2.13676358／CMake 3.22.1で以下が成功しました。

```sh
./gradlew :core:protocol:test testDebugUnitTest lintDebug assembleRelease
```

このリポジトリでは計13件のユニットテストを実行し、失敗・エラーは0です。
ProtocolのRange境界、壊れた／切れたHEIFメタデータ、承認・許可解除、転送再開、一時保存上限、接続終了の競合などを、担当するリポジトリのテストで確認しています。
両リポジトリの共通protocol／stereoのソース10ファイルは同一です。

APKにarm64のlibheif、libde265、JNIとC++ランタイムが含まれ、LGPL全文をassetsにも同梱していることを確認しました。
`apksigner verify --verbose --print-certs`が成功し、両APKの署名証明書SHA-256は次の値です。

```text
b67d2d74d73b2649d35c2e81e9e8b547801cf69ab387c2b741f75d78da3abd31
```

PRとタグのGitHub Actionsでもテスト・Lint・APKビルドを実行します。タグの工程では署名、バージョンとタグの一致、対応ソースのSHA-256を検査し、配布APKのmanifestとチェックサムを生成します。

## Androidエミュレータで確認した範囲
Android 36.1の独立した検証用エミュレータで、配布用署名付きarm64 APKをARM変換機能によりインストールして起動しました。

- スマホ側の写真・通知権限、ライブラリと設定画面、待機ForegroundService。
- 実際のKtorホストのhello、未承認の一覧が401、画面からの接続承認後の一覧取得、許可解除後の401。
- 同じ仮想端末内でのmDNSサービス検出。
- 2つの仮想端末をADBポート転送で接続し、Questの手動アドレス入力、両画面の同じ4桁の番号、スマホ側承認、Questの接続済み一覧、アプリ再起動後の保存済み許可による再接続。

ギャラリーは空であり、写真の実デコードや立体描画を確認したことにはなりません。仮想ネットワークでの検証は実際のWi-Fi／テザリングの受け入れには代用しません。

## 残る受け入れ
`hardwareTested=false`。元のSamsungステレオHEICが未提供で、Galaxy S25 Ultra／Quest 3Sが接続されていないため、左右の目の対応、画像方向、MetaのステレオSurface、デコード時間／メモリ、実ネットワークでの検出と終了時の写真削除は未確認です。

実機Issue: https://github.com/lingmulongtai/Okuyuki-quest/issues/3 。具体的な手順は [ALPHA-SCOPE.md](ALPHA-SCOPE.md) と [INSTALL.md](INSTALL.md)。
アルファの通信は承認トークン付きHTTPであり、暗号化は後続版です。
