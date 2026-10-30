# Galaxy・Quest 実機テスト用プレビュー

このプレビューは開発途中です。両アプリの **alpha.7-preview** を使ってください。GalaxyはAndroid 16以降、Questは開発者モードが必要です。GitHub Actionsの成果物は14日間保存され、ダウンロードにはGitHubへのログインが必要です。

1. [Android checks](https://github.com/lingmulongtai/Okuyuki-android/actions/workflows/ci.yml) と [Quest checks](https://github.com/lingmulongtai/Okuyuki-quest/actions/workflows/ci.yml) で、それぞれ手動実行が成功したプレビューの実行を開きます。Artifactsから `android-device-preview` と `quest-device-preview` をダウンロードし、ZIPを展開します。
2. Galaxyで `Okuyuki-android-0.1.0-alpha.7-preview.apk` を開いて更新します。Questでは、USBデバッグを許可したPCから次を実行します。`QUEST_SERIAL` は `adb devices -l` に表示されるQuestのシリアル番号に置き換え、APKのパスは保存先に合わせてください。

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb devices -l
   & $adb -s "QUEST_SERIAL" install -r "$env:USERPROFILE\Downloads\Okuyuki-quest-0.1.0-alpha.7-preview.apk"
   ```

3. 同じWi-Fiに接続し、Galaxyの「Quest」タブで接続待機をオンにします。Questでスマホを選び、両画面の4桁の番号を確認してGalaxyで承認します。見つからない場合はGalaxyに表示されたIPアドレスとポートをQuestに入力します。
4. Galaxyの「ライブラリ」→「360°メディアを開く」で、結合済みの横2:縦1の360°写真・MP4、または「Osmo 360 LRF・OSVを開く」からLRF・OSVを選び、「Questに共有」を押します。写真は64 MiB以下、動画は64 GiB以下で、動画はGalaxy上でシーク可能なファイルが必要です。Questで共有中の写真または動画を開きます。共有を終えるにはGalaxyのQuestタブで該当する共有終了ボタンを押します。接続待機の停止でも共有は終了します。
5. 結合済み360° MP4、LRF、OSVでは、Questの動画画面にある「没入表示を試す（実験的）」から、頭の向きに追従する球面表示を試せます。OSVでは2本の4K映像の同時再生が必要です。起動に失敗した場合はアプリを再度開き、通常ウィンドウ表示を試してください。戻る操作で終了します。
6. Galaxyで対応するLRFを開いた場合は「手ぶれ補正（実験的）」をオンにして回転の平滑化を試せます。映像フレームと姿勢の時刻を確認できない場合は自動でオフになり、その旨が表示されます。シークや一時停止も確認してください。

写真と動画はペアリングを承認したQuestにのみ渡します。通信は暗号化されないため、信頼できる自宅Wi-FiまたはGalaxyのテザリングで試してください。対応するLRF・OSVの校正情報を読めた場合、GalaxyとQuestのプレビューに左右のレンズ校正を適用します。読み取れない撮影モードでは近似表示に戻ります。OSVは2本の4K HEVC映像を同時に扱うため、端末のデコーダー能力によっては再生できません。

**現在の制限:** GalaxyのLRF補正は、提供サンプルと同じ種類の時刻表・校正情報に限る実験的な回転平滑化です。姿勢の正しさ、照合、画質やシークは実機で未検証です。GalaxyのOSVとQuestのLRF・OSVには手ぶれ補正を適用しません。QuestのLRF・OSV没入表示はGPUで魚眼を2048×1024の球面映像に変換する試験機能です。左右の映像の厳密なフレーム同期は未実装で、再生位置を約150 ms以内に合わせる近似的な同期です。黒画面、表示方向や継ぎ目のずれ、端末能力による再生失敗が起きる可能性があります。360°写真の没入表示は未対応です。GalaxyとQuestの実機動作、OSVの同時デコード、シーク、没入画面の入退場と頭部追従は未検証です。

署名が既存版と異なる、またはインストールに失敗した場合は、既存アプリを削除せずに停止してください。開発者モード、ADB、詳細な接続方法は[インストール・接続ガイド](INSTALL.md)を参照してください。成果物には `SHA256SUMS.txt` と `signature.txt` も含まれます。
