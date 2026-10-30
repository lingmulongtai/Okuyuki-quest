# Galaxy・Quest 実機テスト用プレビュー

このプレビューは開発途中です。両アプリの **alpha.5-preview** を使ってください。GalaxyはAndroid 16以降、Questは開発者モードが必要です。GitHub Actionsの成果物は14日間保存され、ダウンロードにはGitHubへのログインが必要です。

1. [Android checks](https://github.com/lingmulongtai/Okuyuki-android/actions/workflows/ci.yml) と [Quest checks](https://github.com/lingmulongtai/Okuyuki-quest/actions/workflows/ci.yml) で、それぞれ手動実行が成功したプレビューの実行を開きます。Artifactsから `android-device-preview` と `quest-device-preview` をダウンロードし、ZIPを展開します。
2. Galaxyで `Okuyuki-android-0.1.0-alpha.5-preview.apk` を開いて更新します。Questでは、USBデバッグを許可したPCから次を実行します。`QUEST_SERIAL` は `adb devices -l` に表示されるQuestのシリアル番号に置き換え、APKのパスは保存先に合わせてください。

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb devices -l
   & $adb -s "QUEST_SERIAL" install -r "$env:USERPROFILE\Downloads\Okuyuki-quest-0.1.0-alpha.5-preview.apk"
   ```

3. 同じWi-Fiに接続し、Galaxyの「Quest」タブで接続待機をオンにします。Questでスマホを選び、両画面の4桁の番号を確認してGalaxyで承認します。見つからない場合はGalaxyに表示されたIPアドレスとポートをQuestに入力します。
4. Galaxyの「ライブラリ」→「360°メディアを開く」で、結合済みの横2:縦1の360°写真・MP4、または「Osmo 360 LRF・OSVを開く」からLRF・OSVを選び、「Questに共有」を押します。写真は64 MiB以下、動画は64 GiB以下で、動画はGalaxy上でシーク可能なファイルが必要です。Questで共有中の写真または動画を開きます。共有を終えるにはGalaxyのQuestタブで該当する共有終了ボタンを押します。接続待機の停止でも共有は終了します。
5. 結合済み360° MP4では、Questの動画画面にある「没入表示を試す（実験的）」から、頭の向きに追従する球面表示を試せます。戻る操作で終了し、必要ならアプリを再度開いて接続してください。

写真はペアリングを承認したQuestにのみ渡します。通信は暗号化されないため、信頼できる自宅Wi-FiまたはGalaxyのテザリングで試してください。スマホ側のLRFは、対応するOsmo 360の校正情報を読めた場合、左右のレンズ校正値を使う実験的な表示になります。情報を読めない場合は近似表示に戻ります。OSVは2本の4K HEVC映像を同時に扱う実験的なプレビューで、端末のデコーダー能力により再生できない場合があります。

**現在の制限:** 没入表示は結合済みの2:1 MP4のみです。360°写真とOSV・LRFはQuestの通常ウィンドウで表示します。Quest側のOSV・LRFとAndroid側OSVはレンズ校正なしの近似表示です。Android側LRFの校正は提供サンプルで確認した実験機能で、撮影モードによってずれる可能性があります。LRF・OSVの手ぶれ補正、フレーム単位の厳密な同期は未実装です。GalaxyとQuestの実機動作、OSVの同時デコード、シーク、没入画面の入退場は未検証です。

署名が既存版と異なる、またはインストールに失敗した場合は、既存アプリを削除せずに停止してください。開発者モード、ADB、詳細な接続方法は[インストール・接続ガイド](INSTALL.md)を参照してください。成果物には `SHA256SUMS.txt` と `signature.txt` も含まれます。
