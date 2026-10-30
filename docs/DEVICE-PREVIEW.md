# Galaxy・Quest 実機テスト用プレビュー

このプレビューは開発途中です。両アプリの **alpha.4-preview** を使ってください。GalaxyはAndroid 16以降、Questは開発者モードが必要です。GitHub Actionsの成果物は14日間保存され、ダウンロードにはGitHubへのログインが必要です。

1. [Android checks](https://github.com/lingmulongtai/Okuyuki-android/actions/workflows/ci.yml) と [Quest checks](https://github.com/lingmulongtai/Okuyuki-quest/actions/workflows/ci.yml) で、それぞれ手動実行が成功したプレビューの実行を開きます。Artifactsから `android-device-preview` と `quest-device-preview` をダウンロードし、ZIPを展開します。
2. Galaxyで `Okuyuki-android-0.1.0-alpha.4-preview.apk` を開いて更新します。Questでは、USBデバッグを許可したPCから次を実行します。`QUEST_SERIAL` は `adb devices -l` に表示されるQuestのシリアル番号に置き換え、APKのパスは保存先に合わせてください。

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb devices -l
   & $adb -s "QUEST_SERIAL" install -r "$env:USERPROFILE\Downloads\Okuyuki-quest-0.1.0-alpha.4-preview.apk"
   ```

3. 同じWi-Fiに接続し、Galaxyの「Quest」タブで接続待機をオンにします。Questでスマホを選び、両画面の4桁の番号を確認してGalaxyで承認します。見つからない場合はGalaxyに表示されたIPアドレスとポートをQuestに入力します。
4. Galaxyの「ライブラリ」→「360°メディアを開く」で、結合済みの横2:縦1の360°写真（64 MiB以下）または360° MP4（64 GiB以下）を選び、「Questに共有」を押します。動画はGalaxy上でシーク可能なファイルが必要です。Questで共有中の写真または動画を開き、ウィンドウ内で見回します。共有を終えるにはGalaxyのQuestタブで該当する共有終了ボタンを押します。接続待機の停止でも共有は終了します。

写真はペアリングを承認したQuestにのみ渡します。通信は暗号化されないため、信頼できる自宅Wi-FiまたはGalaxyのテザリングで試してください。スマホ側ではOsmo 360のLRFとOSVも選べます。OSVは2本の4K HEVC映像を同時に扱う実験的な直接プレビューです。端末のデコーダー能力により再生できない場合があります。LRF・OSVともにレンズ・手ぶれ補正はなく、映像のずれや継ぎ目が残ります。

**現在の制限:** Questの360°写真・動画は通常ウィンドウ内の表示で、頭部追従の没入表示ではありません。Osmo 360のOSV・LRFはQuestへ直接転送できず、Questの動画共有は結合済みMP4を対象とします。LRF・OSVのレンズ校正・手ぶれ補正は未実装です。GalaxyとQuestでの実機動作、OSVの同時デコードや同期は未検証です。

署名が既存版と異なる、またはインストールに失敗した場合は、既存アプリを削除せずに停止してください。開発者モード、ADB、詳細な接続方法は[インストール・接続ガイド](INSTALL.md)を参照してください。成果物には `SHA256SUMS.txt` と `signature.txt` も含まれます。
