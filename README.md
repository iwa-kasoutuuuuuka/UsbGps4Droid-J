<img align="right" alt="App icon" src="app-icon.png" height="115px">

# UsbGps4Droid-J - A USB GPS provider for Android (Japanese and English Edition)

**UsbGps4Droid-J** は、Androidオペレーティングシステム用の外部USB GPSレシーバー接続・供給アプリケーションです。  
オリジナル原作者および前維持者への深い敬意を表し、GPL-3.0ライセンスを遵守した上で、日本のユーザー向けに完全日本語化とアプリ内での任意言語切り替え機能を備え、JavaからKotlinへ完全移行したアップグレード版です。

---

## 📦 APKのダウンロード (Downloads)
ビルド済みのAPKファイルは、以下のGitHub Releasesページからダウンロードできます：  
👉 **[UsbGps4Droid-J Releases (v2.3.0)](https://github.com/iwa-kasoutuuuuuka/UsbGps4Droid-J/releases/tag/v2.3.0)**

---

## 🎯 用途・何に使えるのか (Use Cases / What is it used for?)

本アプリは、以下のようなシチュエーションや目的で非常に役に立ちます：

1. **GPS非搭載タブレットやデバイスでのナビゲーション利用**
   - 安価なAndroidタブレットやFireタブレットなど、**GPS（位置情報センサー）が内蔵されていない端末**にUSB GPSレシーバーを接続することで、**GoogleマップやYahoo!カーナビなどのナビアプリで現在地を表示し、カーナビとして使用可能**にします。

2. **車載Androidヘッドユニット（中華ナビ）のGPS精度改善**
   - 車載用Android端末の内蔵GPSアンテナの感度が悪い、あるいはダッシュボードの下にあって電波を拾いにくい場合、フロントガラス付近に設置した**高感度な外部USB GPSレシーバーの正確な位置情報に置き換える**ことで、自車位置の飛びやズレを解消します。

3. **Raspberry Piなどシングルボードコンピュータでの位置情報利用**
   - Raspberry Piやその他のボード上にインストールしたAndroid OS（LineageOS等）環境で、手軽に位置情報（Mock Location）システムを動作させることができます。

4. **インターネットの届かないオフライン環境での正確な時刻同期（要Root権限）**
   - ネットワーク（NTP）に接続できない電波の届かない山奥や海上などでも、**宇宙のGPS衛星が発信している正確な原子時計の時刻情報を受信**し、Android端末のシステム時刻を狂いなく自動同期させることができます。

5. **GPS走行ログの収集とデバッグ (NMEAロギング)**
   - GPSレシーバーから受信する生のNMEAセンテンスを直接ストレージにテキスト保存（NMEAログ記録）できるため、移動ルートの記録やGPS関連アプリ開発におけるシミュレーション用デバッグデータとして活用できます。

---

## 🌟 Features of UsbGps4Droid-J / 本フォーク（-J）の特徴

1. **Complete Japanese Localization / 完全日本語対応**
   - すべてのメニュー、設定項目、メッセージ、エラー表示を自然な日本語にローカライズしました。

2. **Anytime Language Switching / 任意の言語切り替え**
   - システム設定に依存せず、アプリの設定画面からいつでも「日本語」「英語」「システムデフォルト」を相互に切り替えることができます。

3. **Kotlin Migration / Kotlinへの完全移行**
   - 既存のJavaコードを100% Kotlinコードに置き換え、Null安全性の向上とプログラムの堅牢化を実現しました。

4. **Storage Access Framework (SAF) Support / 現代的な保存フォルダ選択**
   - Android 10 以降のフォルダ保存制限に対応し、フォルダピッカーを用いて安全に保存先フォルダを任意に選択・永続アクセス権限の取得が可能になりました。

5. **Speed Filter (Drift Prevention) / 速度フィルタ機能**
   - 静止時のGPS位置の小刻みなブレ（ドリフト）を抑制するソフトウェアフィルタを実装。しきい値速度（km/h）を設定し、それ未満の極低速・静止時に現在地の微小なふらつきを防ぎます。

6. **Simultaneous GPX Tracking / GPX形式の並行自動保存**
   - NMEAログ保存時、同時に汎用的なGPX形式ファイルでも自動保存されるため、Google Earthなどの他アプリやGPSログビューアーにそのままインポートして軌跡を確認できます。

7. **Cockpit UI Dashboard / コックピット風ステータス表示**
   - メインステータス画面に現在の「時速 (km/h)」および「進行方位（日本語方角＋角度）」を視覚的に分かりやすく表示するダッシュボードレイアウトを追加しました。

---

## 📜 Credits and Respect to Original Authors / 原作者・前維持者への敬意と公式URL

本プロジェクトは、以下の素晴らしい開発者の方々の功績に支えられています。ここに最大の敬意と感謝を表します。

### 1. Herbert von Broeuschmeul (Original Creator)
- 2011年にこの素晴らしいUSB GPS連携の仕組みを考案・作成されたオリジナル開発者です。
- **Original Source Code**: [Herbert's Source Code / 公式アーカイブ](https://github.com/HvB/UsbGps4Droid)

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
