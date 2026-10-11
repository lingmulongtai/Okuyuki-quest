# Okuyuki Meta Quest 0.1.0-alpha.1

仕様書 v0.3 の「最初の版」を対象にした署名付きアルファ版です。
Android スマホとQuestの両方に、同じ `0.1.0-alpha.1` をインストールしてください。

## できること
- スマホのMediaStoreにあるステレオHEICを一覧化し、元の写真をそのまま配信。
- 同じWi-Fi／スマホのテザリングで自動検出。IPアドレス入力にも対応。
- スマホで番号を確認して接続を許可。登録したQuestの接続許可を解除。
- Questで日付ごとの一覧、写真の選択、前後移動、ウィンドウ内の立体表示。
- HTTP Rangeによる途中からの転送再開、次の写真の先読み、一時保存の上限1 GB。
- Questの停止時と次の起動時に写真の一時保存を削除。

## インストールと接続
[インストール手順](https://github.com/lingmulongtai/Okuyuki-quest/blob/v0.1.0-alpha.1/docs/INSTALL.md)をご覧ください。
Android側はAndroid 16以降、Quest側はQuest 3／3Sが対象です。Questの立体表示にはMeta VR OS SDK 207以降が必要です。

## 検証と制限
自動テスト、Android Lint、署名付きarm64 APKビルド、署名・チェックサムの検証をリリース工程で実施します。
Galaxy S25 UltraとQuest 3Sでの実機確認は未実施です (`hardwareTested=false`)。
Samsungの元のステレオHEICは未提供のため、左右対応、画像方向、実際の立体表示、デコード時間、Wi-Fi／テザリングの検出は実機受け入れチェックが残っています。

アルファ版は接続を許可したQuestだけが一覧や写真を取得できますが、通信は暗号化されないHTTPです。信頼できるプライベートネットワークで使用してください。
立体メタデータがない／壊れているHEIC、256 MBを超える原本、6400万画素を超える画像は対象外です。表示は片目の長辺2048 pxまでです。

没入モード、TLSと証明書固定、共有・遠隔選択、深度調整、お気に入り、動画、ストア配信は後続版の範囲です。

## 配布物
署名付きAPK、SHA256SUMS.txt、署名検証結果、release-manifest.json、libheif／libde265のライセンスと対応ソースを添付します。
元の仕様書に含まれる写真は公開していません。
