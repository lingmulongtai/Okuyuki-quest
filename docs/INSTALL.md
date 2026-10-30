# GalaxyとQuest 3Sへのインストール・接続

`v0.1.0-alpha.10-preview` の手順です。GalaxyにAndroid版、Quest 3SにQuest版のAPKを入れます。
Quest版はPCからUSB経由でインストールします。導入後はGalaxyとQuestだけで使えます。

## 用意するもの

- **Android 16のGalaxy**。仕様書の対象はS25 Ultraです。Android 15以前にはこのAPKを入れられません。
- OSを更新したQuest 3S（Quest 3にも対応）。
- Windows PCと、データ通信に対応したUSB-Cケーブル。
- GalaxyのMeta Horizonアプリと、Questで使っているMetaアカウント。
- Galaxy内のSamsungステレオHEICの原本。通常のJPEGや立体情報のないHEICは3D一覧の対象外です。
- 両端末が通信できるWi-Fi、またはGalaxyのテザリング。

## 1. APKをダウンロードする

両方を同じバージョンに揃えてください。以下はAPKの直接ダウンロードリンクです。

| 入れる端末 | 保存する場所 | APK |
| --- | --- | --- |
| Galaxy | Galaxyのダウンロードフォルダ | [Okuyuki-android-v0.1.0-alpha.10-preview.apk](https://github.com/lingmulongtai/Okuyuki-android/releases/download/v0.1.0-alpha.10-preview/Okuyuki-android-v0.1.0-alpha.10-preview.apk) |
| Quest 3S | **PCのダウンロードフォルダ** | [Okuyuki-quest-v0.1.0-alpha.10-preview.apk](https://github.com/lingmulongtai/Okuyuki-quest/releases/download/v0.1.0-alpha.10-preview/Okuyuki-quest-v0.1.0-alpha.10-preview.apk) |

[Androidのリリース](https://github.com/lingmulongtai/Okuyuki-android/releases/tag/v0.1.0-alpha.10-preview)・[Questのリリース](https://github.com/lingmulongtai/Okuyuki-quest/releases/tag/v0.1.0-alpha.10-preview)にはチェックサム、署名情報、検証記録もあります。
GitHubの「Source code」のZIPではなく、上の `.apk` を保存してください。

## 2. GalaxyにAndroid版を入れる

1. GalaxyのブラウザでAndroid版APKをダウンロードします。
2. ダウンロード通知、または「マイファイル」→「ダウンロード」からAPKを開きます。
3. インストール元の許可を求められたら、APKを開いたブラウザ／マイファイルの「この提供元を許可」をオンにし、「インストール」を押します。
4. **Okuyuki** を開き、「写真へのアクセスを許可」を押します。「選択した写真」でも使えますが、試したい3D写真を許可対象に含めてください。
5. 「ライブラリ」に3D写真が出ることを確認します。写真を追加した後は「更新」を押せます。

「自動ブロッカー」でインストールが止まる場合は、「設定」→「セキュリティおよびプライバシー」→「自動ブロッカー」を一時的にオフにしてこのAPKを入れます。終わったら元の設定に戻してください。設定名はOne UIの版で異なる場合があります。[Samsungの説明](https://www.samsung.com/jp/support/mobile-devices/protect-your-galaxy-device-with-the-new-auto-blocker-feature/)

提供元の許可を後から探す場合は、設定内で「不明なアプリをインストール」を検索します。[Samsungの説明](https://www.samsung.com/us/support/troubleshoot/TSG10001913/)

## 3. Quest 3Sの開発者モードを有効にする（初回だけ）

すでに開発者モードとUSBデバッグが使える場合は「4」へ進めます。

1. Questで使っているアカウントで[Meta Developer Dashboard](https://developers.meta.com/vr/manage/)にサインインします。
2. 未登録の場合は開発者チーム／組織を作成し、Dashboardが求めるアカウント認証を完了します。条件は[Metaの端末セットアップ](https://developers.meta.com/vr/documentation/native/android/mobile-device-setup/)を確認してください。
3. Galaxyの **Meta Horizonアプリ** で、ヘッドセットのアイコン→Quest 3S→「ヘッドセットの設定」→「開発者モード」をオンにします。
4. データ通信対応USBケーブルでQuest 3SをPCにつなぎます。
5. Questを装着し、「USBデバッグを許可しますか？」で許可します。自分のPCでは「このコンピュータから常に許可」を選べます。

開発者モードの項目がない場合は、アカウントの認証・チーム登録、Meta Horizonアプリとのペアリング、使用しているアカウントを確認します。USBデバッグの確認が出ない場合は、Questの「設定」→「開発者」のMTP通知を有効にして接続し直します。メニュー名は更新で変わる場合があります。[Metaの手順](https://developers.meta.com/vr/documentation/native/android/mobile-device-setup/)

## 4. WindowsからQuest版を入れる

### ADBを用意する

Windowsで未導入の場合は、[Oculus ADB Drivers](https://developers.meta.com/vr/downloads/package/oculus-adb-drivers/)をダウンロード・展開し、`android_winusb.inf` を右クリックして「インストール」します。

[Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)からWindows用ZIPをダウンロードして展開します。以下は `C:\Tools\platform-tools\adb.exe` に置いた例です。PowerShellを開いて実行してください。別の場所に置いた場合はパスを変更します。

```powershell
$adb = "C:\Tools\platform-tools\adb.exe"
& $adb devices -l
```

Android Studioなどで標準のSDKがすでに入っているPCでは、次のパスを使えます。

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
```

Questの行の状態が `device` になっていることを確認します。`unauthorized` ならQuestを装着してUSBデバッグを許可します。何も表示されなければケーブル、USBポート、ADBドライバ、開発者モードを確認します。

### APKをインストールする

**同じPowerShell** で、`QUEST_SERIAL` を `devices -l` に表示されたQuestのシリアル番号に置き換えて実行します。`-s` でQuestを選ぶため、Galaxyなど別の端末が接続されていても対象を指定できます。

```powershell
& $adb -s "QUEST_SERIAL" install -r "$env:USERPROFILE\Downloads\Okuyuki-quest-v0.1.0-alpha.10-preview.apk"
```

`Success` と出たら完了です。保存先やファイル名が異なる場合は最後のパスを変更してください。
Questのアプリライブラリーで「提供元不明（Unknown Sources）」を開き、**Okuyuki Quest** を起動します。絞り込み／メニューの位置はOSの版によって異なります。導入後はUSBケーブルを外して使えます。

`INSTALL_FAILED_UPDATE_INCOMPATIBLE` は、既存アプリの署名と一致しない場合に出ます。同じ証明書の版は `-r` で更新できます。別の証明書の開発版をアンインストールすると保存済みの接続登録を失うため、エラーだけを理由に先に削除しないでください。[MetaのADBインストール手順](https://developers.meta.com/vr/documentation/unity/unity-env-device-setup/)

## 5. GalaxyとQuestを接続する

1. **両端末を同じWi-Fiに接続**します。Galaxyのテザリングでは、Questをそのアクセスポイントにつなぎます。
2. GalaxyでOkuyukiを開き、「ライブラリ」に試したい3D写真があることを確認します。
3. Galaxyの「Quest」タブで **「Questとつなぐ」をオン** にします。通知権限を求められたら許可します。待機通知と手動接続用のアドレスが表示されます。
4. QuestでOkuyuki Questを開き、見つかったスマホを選びます。見つからなければ「アドレスを入力してつなぐ」にGalaxyの **IPアドレスとポートを両方** 入れて「つなぐ」を押します。例: `192.168.1.2:12345`。この例ではなくGalaxyの表示値を使ってください。
5. 両画面に **同じ4桁の番号** が出たことを確認し、Galaxyで「許可」を押します。初回承認中はGalaxyのアプリ画面を開いておきます。
6. Questの一覧から1枚を選び、「前へ」「次へ」で切り替えます。一覧を追加取得する場合は「続きを見る」を押します。
7. 終わるときはQuestアプリを閉じ、Galaxyの待機をオフにします。Galaxyの通知からも停止できます。

原本はGalaxyに置いたままです。Questの一時保存は停止時と次の起動時に削除します。
接続先がない状態が10分続くとGalaxyの待機は自動停止するため、再び試すときはオンにします。
次回は保存済みの許可を使って接続できます。Galaxyの「設定」→接続済みQuestの「解除」で許可を取り消せます。

## うまくいかない場合

| 症状 | 確認すること |
| --- | --- |
| GalaxyにAPKを入れられない | Android 16以上か、提供元の許可と自動ブロッカーを確認します。 |
| QuestがADBに出ない／`unauthorized` | 開発者モード、データ通信ケーブル、ADBドライバ、Quest内のUSBデバッグ許可を確認します。 |
| Questにスマホが見つからない | 同じWi-Fi、Galaxyの待機スイッチを確認し、IPアドレスとポートを手動入力します。 |
| 手動IPでも接続できない | ゲストWi-Fiや端末間通信禁止、VPNを確認します。Galaxyのテザリングも試せます。 |
| スマホの待機がオフになった | 待機をオンに戻します。アドレスが変わったら新しい値で接続します。Questには「再接続」もあります。 |
| 写真が0枚 | まずGalaxy側の一覧とアクセス許可を確認します。通常の写真は対象外です。Samsungの原本ステレオHEICでも出ない場合は形式の実機調査が必要です。 |
| Questに「平面プレビュー」と出る | QuestのOSを更新します。立体表示にはMeta VR OS SDK 207以降に対応するOSが必要です。 |

## アルファの検証範囲

CI・自動テスト・エミュレータでのインストール／接続を検証済みです。
**実際のGalaxy／Quest 3SとSamsungの原本HEICでのデコード・左右の立体表示は未検証**です。`hardwareTested=false` を維持しています。
通信は承認付きHTTPで、暗号化は後続版です。信頼できる自宅のWi-FiまたはGalaxyのテザリングで試してください。

最初はGalaxyの写真一覧、接続承認、Questでの1枚の表示と左右／方向を確認してください。端末・OS・表示されたエラー・どの手順で止まったかを記録すると切り分けやすくなります。
受け入れ項目は [ALPHA-SCOPE.md](ALPHA-SCOPE.md)、実施済み検証は [VALIDATION.md](VALIDATION.md)、追跡先は [Android実機Issue](https://github.com/lingmulongtai/Okuyuki-android/issues/3)・[Quest実機Issue](https://github.com/lingmulongtai/Okuyuki-quest/issues/3)です。

メーカーの設定手順は2026-10-12に公式資料を確認しました。

## 次回以降の更新

起動時に更新を確認し、APKのハッシュ、パッケージ名、署名とバージョンを検証して取得します。画面の「インストール」を選び、初回はこのアプリからのインストールを許可して戻り、もう一度「インストール」を選びます。設定から手動確認もできます。通信エラー時は再試行します。QuestではOSのインストール画面が開くか実機未検証のため、開けない場合は上記のPCからの `adb install -r` を使用してください。alpha.9以前からalpha.10へは初回のみこのページの手動手順が必要です。
