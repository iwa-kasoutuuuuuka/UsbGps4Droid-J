<img align="right" alt="App icon" src="app-icon.png" height="115px">

# UsbGps4Droid-J - A USB GPS provider for Android (Japanese and English Edition)

**UsbGps4Droid-J** は、Androidオペレーティングシステム用の外部USB GPSレシーバー接続・供給アプリケーションです。  
オリジナル原作者および前維持者への深い敬意を表し、GPL-3.0ライセンスを遵守した上で、日本のユーザー向けに完全日本語化とアプリ内での任意言語切り替え機能を備え、JavaからKotlinへ完全移行したアップグレード版です。

---

## 🌟 Features of UsbGps4Droid-J / 本フォーク（-J）の特徴

1. **Complete Japanese Localization / 完全日本語対応**
   - すべてのメニュー、設定項目、メッセージ、エラー表示を自然な日本語にローカライズしました。

2. **Anytime Language Switching / 任意の言語切り替え**
   - システム設定に依存せず、アプリの設定画面からいつでも「日本語」「英語」「システムデフォルト」を相互に切り替えることができます。

3. **Kotlin Migration / Kotlinへの完全移行**
   - 既存のJavaコードを100% Kotlinコードに置き換え、Null安全性の向上とプログラムの堅牢化を実現しました。

---

## 📜 Credits and Respect to Original Authors / 原作者・前維持者への敬意と公式URL

本プロジェクトは、以下の素晴らしい開発者の方々の功績に支えられています。ここに最大の敬意と感謝を表します。

### 1. Herbert von Broeuschmeul (Original Creator)
- 2011年にこの素晴らしいUSB GPS連携の仕組みを考案・作成されたオリジナル開発者です。
- **Original Source Code**: [Herbert\'s Source Code / 公式アーカイブ]

### 2. Oliver Bell (freshollie)
- 現代のAndroid OS（Android 5.x / 6.x）やパーミッション管理（ランタイムパーミッション）に対応させ、フォーク版を維持・改良した前開発者です。
- **Forked Repository**: [freshollie/UsbGps4Droid](https://github.com/freshollie/UsbGps4Droid)

---

## ⚖️ License / ライセンスについて

本ソフトウェアは **GPL v3 (GNU General Public License v3)** のもとで公開されているオープンソースソフトウェアです。

- **License**: [GPL v3](LICENSE)
- 二次配布や改変を行う場合は、GPL-3.0ライセンスの規約を厳格に遵守する必要があります。元の著作権表示を維持し、ソースコードを公開する義務があります。
- ソースコードは以下のGitHubリポジトリで公開されています：
  **GitHub**: [https://github.com/iwa-kasoutuuuuuka/UsbGps4Droid-J](https://github.com/iwa-kasoutuuuuuka/UsbGps4Droid-J)

---

## 🛠️ Usage / 使用方法

### USB Permissions Popup
Androidの標準セキュリティ制限により、USBデバイスが抜き差しされる度に「USBデバイスの使用許可」を求めるポップアップが表示されます。

- **Root化されたデバイスの場合**:  
  システムのポップアップを抑止し、自動的にパーミッションを付与する手法を適用可能です。
- **未Rootデバイスの場合**:  
  差し込む度にポップアップの許可をタップする必要があります。これはAndroid OSの仕様制限です。

### Service behavior / サービスの動作
アプリのバックグラウンドサービスは、デバイスの起動時（Boot完了時）に自動的に開始するように設定可能です。  
また、シェルコマンド（Root権限）やIntent経由で直接開始させることも可能です。

```bash
am startservice -a org.broeuschmeul.android.gps.usb.provider.action.START_GPS_PROVIDER -n org.broeuschmeul.android.gps.usb.provider/.driver.USBGpsProviderService
```
