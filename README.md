# 筋トレタイマー（Androidアプリ）

## スマホに入れる
1. Android Studio で「Open」からこの `android` フォルダを開く（初回は必要なものの取得に数分かかる）
2. スマホの「開発者向けオプション」で「USBデバッグ」をオンにし、USBでPCとつなぐ
3. 上部の端末一覧からスマホを選び、緑の ▶（Run）を押す

## テスト
`TimerEngineTest` の単体テストは、パスに日本語が含まれていると起動できない（`ClassNotFoundException`）。
英数字だけのフォルダにコピーして `gradlew testDebugUnitTest` を実行する。アプリのビルド自体は今の場所のままでできる。

## 構成
- `app/src/main/assets/index.html` … 画面。計時はしない（表示とメニュー編集）
- `TimerEngine.kt` … タイマー本体（テストあり）
- `AudioCues.kt` … 合図の音と読み上げ。音楽を止めないようオーディオフォーカスを要求しない
- `TimerController.kt` / `TimerService.kt` … 画面を消しても動かし続ける部分と通知
- `MainActivity.kt` … 画面の表示と、index.html との橋渡し
