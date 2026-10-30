# Galaxy・Quest 実機テスト用プレビュー

両アプリの **v0.1.0-alpha.12-preview** を組み合わせてください。長尺動画の共有・補正には両方の更新が必要です。GalaxyはAndroid 16以降、Quest 3／3Sは開発者モードとUSBデバッグが必要です。この版は実験段階で、Galaxy・Quest実機での動作は未確認です。

1. [Galaxy用APK](https://github.com/lingmulongtai/Okuyuki-android/releases/download/v0.1.0-alpha.12-preview/Okuyuki-android-v0.1.0-alpha.12-preview.apk)をGalaxyに、[Quest用APK](https://github.com/lingmulongtai/Okuyuki-quest/releases/download/v0.1.0-alpha.12-preview/Okuyuki-quest-v0.1.0-alpha.12-preview.apk)をWindows PCにダウンロードします。GalaxyでAPKを開き、必要に応じてインストール元を許可して更新します。
2. Questで開発者モードを有効にし、PCにUSB接続してヘッドセット内でUSBデバッグを許可します。PowerShellで次を実行します。`QUEST_SERIAL` は `devices -l` に表示されるQuestのシリアル番号です。

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb devices -l
   & $adb -s "QUEST_SERIAL" install -r "$env:USERPROFILE\Downloads\Okuyuki-quest-v0.1.0-alpha.12-preview.apk"
   ```

3. Questのアプリライブラリで「提供元不明」からOkuyuki Questを開きます。GalaxyとQuestを同じWi-Fiに接続し、Galaxyの「Quest」タブで接続待機をオンにします。Questでスマホを選び、両画面の4桁の番号を確認してGalaxyで承認します。自動検出されない場合はGalaxyに表示されたIPアドレスとポートをQuestに入力します。
4. Galaxyの「ライブラリ」→「360°メディアを開く」で結合済みの2:1の360°写真・MP4、または「Osmo 360 LRF・OSVを開く」からLRF・OSVを選び、「Questに共有」を押します。Questで共有中の写真または動画を開きます。共有中は元ファイルを編集・置換しないでください。終了時はGalaxyで共有終了、または接続待機をオフにします。
5. 対応するLRF・OSVでは「手ぶれ補正（実験的）」をオンにして、GalaxyとQuestのプレビューで回転の平滑化を試せます。Questでは同じ設定が「没入表示を試す（実験的）」にも引き継がれます。映像と姿勢の時刻を照合できない場合、補正は自動でオフになります。再生、一時停止、シーク、前後左右への視点移動も確認してください。
6. 結合済み360° MP4、LRF、OSVは、Questの動画画面から頭の向きに追従する没入表示を試せます。OSVの没入表示には「高画質」（4096×2048）も選べます。開始時に対応できない場合は標準画質を試します。再生が重い・途中で停止する場合は、高画質をオフにして開き直してください。表示方向や継ぎ目に違和感がある場合は使用を中止し、戻る操作で終了してください。360°写真の没入表示には未対応です。

補正は提供サンプルと同じ構造を検証できたカメラ姿勢データに限る実験的な回転平滑化です。DJI純正の手ぶれ補正と同一の結果を保証するものではなく、移動によるぶれなどは補正しません。姿勢データは最大500,000フレーム（50 fpsで約2時間46分）・合計512 MiBまでです。撮影形式やメタデータの大きさによっては、それより短い動画でも補正できない場合があります。OSVの左右映像は表示時刻が一致するフレームだけを組み合わせ、一致できない場合は停止します。2本の4K HEVC映像を同時に扱えない端末では再生できない場合があります。異なる撮影モードの校正・姿勢データ、画質、シーク、同時デコード、通信、没入表示と頭部追従は実機で未検証です。

360°写真は64 MiB以下、動画は256 GiB以下で、動画はGalaxy上でシーク可能なファイルが必要です。写真と動画はペアリングを承認したQuestにのみ渡しますが、通信は暗号化されないHTTPです。信頼できる自宅Wi-FiまたはGalaxyのテザリングで試してください。

署名の不一致などで更新できない場合は既存アプリを削除せずに止めてください。[詳細なインストール・接続ガイド](INSTALL.md)と、各GitHubリリースの `SHA256SUMS.txt`・`signature.txt` も参照できます。
