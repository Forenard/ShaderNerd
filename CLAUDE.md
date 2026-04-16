# CLAUDE.md - Shader Nerd Project Guide

## Development Management

- **`ROADMAP.md`** - 開発の道筋・計画。未着手 (Next) と進行中 (In Progress) のタスクのみ記載
- **`CHANGELOG.md`** - 開発の履歴。バージョンごとの変更点を記録

**ルール: 完了したタスクは ROADMAP.md に書かない。完了した作業は必ず CHANGELOG.md に記録する。**

CLAUDE.md の肥大化を避けるため、計画と履歴はこれらのファイルに分離する。

## Audio Status (2026-04-16)

- `Database` is now schema version 7 and persists `shaders.audio_shader` end-to-end.
- `EditorFragment` now exposes separate Visual/Audio tabs with separate undo histories.
- `AudioShaderPlayerManager` owns the toolbar audio controls: `run_code` (play from start), `pause_audio`, and `audio_bpm_input`.
- `ShaderRenderer` can accept a `TimeSource`, and `MainActivity` uses that to keep visual `time` synced with audio playback.
- Audio playback uniforms are now `fader0..7`, and the UI labels them `F0..F7`.
- `AudioSamplesActivity` plus `AudioSampleRepository` handle SAF folder selection, lazy per-directory browsing across subfolders, cached waveform previews, and snippet insertion for `sample_*` audio textures.
- The old temporary transport-bar plan is obsolete; do not reintroduce rewind / nudge / BPM +/- controls without a new product decision.
- When an audio shader exists, `MainActivity` keeps the embedded preview path active so toolbar playback still works for users who normally prefer `PreviewActivity`.

## Project Overview

Shader Nerd - GLSLシェーダーをAndroid上で作成・編集・ライブ壁紙として使用できるアプリ。
パッケージ名: `de.markusfisch.android.shadereditor`
現在のバージョン: 2.36.1 (versionCode 92)
UIは単一Activity + 複数Fragment構成。カスタムWidgetでエディタ・プレビュー・テクスチャツールを実現。

## Tech Stack

- **言語**: Java (Java 17)
- **プラットフォーム**: Android (minSdk 23, targetSdk/compileSdk 36)
- **ビルドシステム**: Gradle (Kotlin DSL) + Android Gradle Plugin 8.13.2
- **主要ライブラリ**: AndroidX AppCompat 1.7.1, Material 1.13.0, CameraX 1.5.2, AndroidX Preference 1.2.1
- **依存管理**: `gradle/libs.versions.toml` (Version Catalog)
- **View**: View Binding (Compose/DataBinding不使用)
- **データベース**: SQLite (Room不使用、直接SQLiteOpenHelper)
- **テスト**: なし (自動テスト未整備。手動テスト + lint + 静的解析に依存)

## Build Commands

```bash
# デバッグビルド
./gradlew assembleDebug          # または make debug

# リリースビルド
./gradlew assembleRelease        # または make release (lint含む)
./gradlew bundleRelease          # AAB (make bundle)

# Lint / 静的解析
./gradlew lintDebug              # または make lint
make infer                       # Facebook Infer
make avocado                     # ベクタードロワブルチェック

# adbヘルパー
make install                     # デバッグAPKインストール
make start                       # アプリ起動
make uninstall                   # アンインストール
make meminfo                     # メモリ情報
make glxinfo                     # グラフィクス情報
```

Windows環境では `./gradlew` の代わりに `gradlew.bat` を使用。

### 実機デバッグ (Windows / USB)

adb にパスが通っていないため、フルパスで実行する。

```bash
ADB="C:\Users\Renar\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# デバイス確認
"$ADB" devices -l

# ビルド → インストール → 起動 (一連の流れ)
e:/WorkSpace_E/ShaderNerd/ShaderNerd/gradlew.bat assembleDebug
"$ADB" -s <DEVICE_ID> install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s <DEVICE_ID> shell am start -n de.markusfisch.android.shadereditor.debug/de.markusfisch.android.shadereditor.activity.SplashActivity

# logcat確認 (クラッシュチェック)
"$ADB" -s <DEVICE_ID> logcat -t 30 --pid=$("$ADB" -s <DEVICE_ID> shell pidof de.markusfisch.android.shadereditor.debug)
```

- 複数デバイス接続時は `-s <DEVICE_ID>` でデバイスを指定する
- SDK パスは `local.properties` に `sdk.dir` として設定済み
- Xiaomi端末は開発者オプション > 「USB経由でインストール」を ON にする必要あり

## Project Structure

```
app/src/main/java/de/markusfisch/android/shadereditor/
├── activity/          # Activity群 + managers/ サブパッケージ
│   └── managers/      # ShaderManager, ShaderViewManager, ShaderListManager,
│                      # UIManager, NavigationManager, MainMenuManager, ExtraKeysManager
├── adapter/           # ShaderAdapter, TextureAdapter, ErrorAdapter, CompletionsAdapter 等
├── app/               # ShaderEditorApp (Applicationシングルトン、Preferences初期化、UndoRedo履歴保持)
├── database/          # Database (SQLiteOpenHelperシングルトン), DataSource (DAO束), DatabaseContract
├── dao/               # ShaderDao, TextureDao (CRUD操作)
├── fragment/          # EditorFragment, PreferencesFragment, UniformPages系, TextureView系 等
├── graphics/          # BitmapEditor (画像リサイズ・クロップ)
├── hardware/          # AbstractListener基底 + 各センサーリスナー
│                      # (Accelerometer, Gyroscope, Camera, MicInput, Light, Gravity 等)
├── highlighter/       # GLSL構文ハイライト (Lexer, Token, TokenType, Highlight, TrieNode)
├── io/                # ImportExportAsFiles, DatabaseImporter/Exporter, ExternalFile
├── opengl/            # ShaderRenderer, Program, ShaderError, TextureParameters, BackBufferParameters
├── preference/        # Preferences (SharedPreferencesラッパー), ShaderListPreference
├── receiver/          # BatteryLevelReceiver
├── resource/          # リソースユーティリティ
├── service/           # ShaderWallpaperService, NotificationService
├── view/              # UndoRedo, SystemBarMetrics, SoftKeyboard
└── widget/            # ShaderView, ShaderEditor, LineNumberEditText, CubeMapView,
                       # ErrorListModal, TextureParametersView 等カスタムView
```

## Runtime Architecture

### アプリ起動フロー
1. `ShaderEditorApp` がシングルトン `Preferences` 初期化、`Database` プレウォーム、プロセス全体の `UndoRedo.EditHistory` 保持、デバッグ時StrictMode有効化、`BatteryLevelReceiver` 登録 (API>=24)
2. `SplashActivity` → 即座に `MainActivity` を起動

### メイン画面の構成
`MainActivity` が全体をホストし、以下のManagerで責務分離:

| Manager | 役割 |
|---------|------|
| `ShaderManager` | シェーダーの読込/保存、Intent処理、複製/削除、サムネイル永続化 |
| `ShaderViewManager` | GLSurfaceViewプレビューのライフサイクル管理、品質スピナー |
| `ShaderListManager` | 保存済みシェーダーのListView + バックグラウンドローダー |
| `UIManager` | ツールバー、ドロワー、エディタ表示切替、エクストラキー |
| `MainMenuManager` | ポップアップメニュー (エディタ操作、Uniform追加、サンプル、設定) |
| `NavigationManager` | プレビュー、FAQ、シェア |
| `ExtraKeysManager` | ソフトキーボードヘルパー + 補完チップ |

**状態フロー**: エディタ → `ShaderManager` → `ShaderViewManager` + `ShaderListManager` → `UIManager` (タイトル/エラー表示)

### エディタスタック
- `EditorFragment` → `ShaderEditor` (extends `LineNumberEditText`) → 構文ハイライト (`highlighter/*`)、デバウンスコンパイル、自動ブレース挿入、ShaderToy変換、補完、lintエラーハイライト
- ハイライト/エラーはワーカースレッド (`TokenListUpdater`) で再計算
- `UndoRedo` はプロセス全体で共有 (`ShaderEditorApp.editHistory`)、Fragment再生成間で復元可能

### シェーダープレビュー & レンダリング
- `ShaderView` → `GLSurfaceView` ラッパー。EGLコンテキストファクトリでGLES3優先、GLES2フォールバック
- `ShaderRenderer` (コアエンジン):
  - `sampler2D` / `samplerCube` / `samplerExternalOES` uniform解析
  - バックバッファFBO管理、サムネイル生成、品質乗数による解像度スケーリング
  - 公開uniform: time, resolution, マルチタッチ, センサー各種, battery, day/night, 通知数/時刻, カメラフレーム, マイク振幅, 壁紙オフセット 等
  - センサーリスナーはシェーダーが該当uniformを参照する場合のみ登録
- `PreviewActivity` → 手動実行モード用フルスクリーンプレビュー。`RenderStatus` で結果を `MainActivity` に返送

### OpenGL ES バージョンサポート
- **最小要件**: GLES 2.0 (`AndroidManifest.xml` で `glEsVersion="0x00020000"` を宣言)
- **ランタイムサポート**: GLES 3.0 / 3.1 / 3.2 (デバイスが対応していれば自動使用)
- **コンテキスト生成** (`ShaderView.ContextFactory`): EGLコンテキスト作成時に GLES 3 を試行し、失敗した場合 GLES 2 にフォールバック
- **シェーダーバージョン検出** (`ShaderRenderer`): 正規表現 `^#version 3[0-9]{2} es$` でシェーダーソースの `#version` ディレクティブを解析。GLES 3系と判定された場合、対応する頂点シェーダーと OES 拡張を切り替え
- **コンパイル**: `GLES20` API を使用 (GLES 2/3 両コンテキストで後方互換)
- **関連ファイル**:
  - `widget/ShaderView.java` - EGLコンテキストファクトリ (GLES 3→2 フォールバック)
  - `opengl/ShaderRenderer.java` - バージョン検出ロジック、頂点シェーダー切替
  - `opengl/Program.java` - シェーダーコンパイル・リンク

### ライブ壁紙 & サービス
- `ShaderWallpaperService` → ライブ壁紙エンジン。`ShaderView`/`ShaderRenderer` を再利用。低バッテリー時に `RENDERMODE_WHEN_DIRTY` へ切替
- `NotificationService` → 通知リスナー。`notificationCount` / `lastNotificationTime` uniformを供給
- `BatteryLevelReceiver` → 低電力モード/充電状態フラグ切替

## Persistence & Data Layer

- `Database` → SQLiteOpenHelperシングルトン (スキーマバージョン6)。DB接続はDAO呼び出し間でオープン維持
- `DataSource` → DAO束。取得: `Database.getInstance(context).getDataSource()`
- **生の `SQLiteDatabase` オブジェクトをDAOスコープ外でキャッシュしないこと**

### Database Schema (shaders.db, version 7)

| テーブル | カラム |
|---------|--------|
| **shaders** | `_id`, `shader` (GLSLソース), `audio_shader` (optional audio GLSL), `thumb` (BLOB), `name`, `created`, `modified`, `quality` |
| **textures** | `_id`, `name`, `width`, `height`, `ratio`, `thumb` (BLOB), `matrix` |

- `ShaderDao`: シェーダーCRUD、サムネイル(PNGブロブ)、品質乗数。`res/raw/*.glsl` からのシード挿入。名前は任意 (未設定時は最終更新日時をフォールバック表示)
- `TextureDao`: 2Dテクスチャ / キューブマップ (ratioで区別)。サムネイル + フル解像度PNGマトリクス保存

### Import / Export
- `ImportExportAsFiles` → `Downloads/ShaderNerd` 配下に `.glsl` ファイル読み書き (API < 29のみ、スコープストレージ以降は非表示)
- `DatabaseImporter` / `DatabaseExporter` → SAFベースのSQLiteエクスポート/インポート (MIME: `application/x-sqlite3`, `application/vnd.sqlite3`, `application/octet-stream`)
- テクスチャインポート → SAF `ACTION_GET_CONTENT` + クロップ/リサンプリング (`BitmapEditor.getBitmapFromUri()`)

## Uniforms, Textures, and Samples

- `AddUniformActivity` → `UniformPagesFragment` (ViewPager: プリセット / 2Dテクスチャ / キューブマップ)。画像ピック/クロップ後にGLSL文をresult bundleで返却
- `UniformPresetPageFragment` → `PresetUniformAdapter` から組み込みuniform一覧表示
- テクスチャ作成: `CropImageActivity` + `CropImageFragment` → `Sampler2dPropertiesFragment` / `CubeMapActivity`
- サンプル: `LoadSampleActivity` → `res/raw/sample_*.glsl` を `SamplesAdapter` で表示

## Preferences

キー/定数は `Preferences` クラスに集約。常に `ShaderEditorApp.preferences` 経由で読み書き。

主要設定: 実行モード (auto/manual/manual+preview)、更新遅延、エディタスタイル (フォント, タブ幅, 合字, エクストラキー, 行番号)、自動保存、デフォルトシェーダーテンプレートID、壁紙シェーダー、センサー遅延

## Code Style

- **インデント**: タブ (幅4)
- **最大行長**: 100文字
- **ブレース**: end_of_line (K&R)
- **アライメント**: すべて無効
- **import**: ワイルドカードimport不使用 (on-demand閾値=99)
- **設定ファイル**: `.editorconfig` が権威的ソース
- Android Studio標準フォーマット準拠 (タブ/アライメント以外)

## Git Workflow

- **feature branchワークフロー**: 機能ごとにブランチ作成
- **マージ**: `git merge cool_feature --squash` (1機能1コミット)
- **コミットメッセージ**: 簡潔で意味のあるメッセージ

## Development Rules

### GLスレッドに関する注意
- レンダリングコードはGLスレッドで実行される。**`ShaderRenderer` 内からUIウィジェットに直接触れないこと**
- `queueEvent()` またはリスナーコールバックでメインスレッドに結果を返す

### 非同期パターン
- DAO操作はUIスレッドで実行しない。シングルスレッドExecutor + メインスレッドHandlerパターンに従う (`ShaderListManager`, `UniformSampler2dPageFragment` を参照)

### グローバルシングルトンの追加を避ける
- 新しい機能はスコープ付きManagerクラスまたはFragmentで実装。既存のManager三体 (`ShaderManager` / `ShaderListManager` / `ShaderViewManager`) の連携を理解して追加すること

### リソース管理
- Bitmap/テクスチャは必ずrecycle/release (`TextureViewFragment`, `BitmapEditor` を参照)
- GLSLソースはASCII限定 (`ShaderView.removeNonAscii()` で強制)

## Common Change Scenarios

| 変更内容 | 対応箇所 |
|---------|---------|
| **新しいセンサー/uniform追加** | `ShaderRenderer` (定数、uniform location、値更新) → `hardware/` にリスナー追加 → `PresetUniformAdapter` に追加 → `FAQ.md` / UI文字列更新。APIレベル・設定でゲーティング |
| **シェーダーメタデータ追加** (例: タグ) | `DatabaseContract` → DBバージョンup → `ShaderDao` CRUD → `ShaderManager` → `ShaderAdapter` / レイアウト |
| **サンプルシェーダー追加** | `res/raw/` に `.glsl` + `res/drawable/` にサムネイル → `SamplesAdapter` にエントリ → 翻訳文字列追加 |
| **プレビュー動作変更** | `ShaderViewManager` + `ShaderRenderer` + `MainActivity`。手動実行は `PreviewActivity` 経由 |
| **インポート/エクスポート変更** | `ImportExportAsFiles` + 設定の権限処理。スコープストレージの差異に注意 |
| **設定追加** | `res/xml/preferences.xml` → `Preferences` にキー/デフォルト定義 → `PreferencesFragment` サマリー更新 |
| **リリース準備** | `CHANGELOG.md` + `fastlane/metadata/**/changelogs` 更新 → `app/build.gradle.kts` の `versionCode`/`versionName` バンプ → `make release` or `make bundle` |

## Debugging Tips

- デバッグビルドはStrictMode有効 → ディスク/ネットワークのメインスレッドアクセスを検出
- GLSLコンパイルエラー: ライブプレビューは Snackbar + `ErrorListModal`、手動実行は `PreviewActivity.renderStatus.infoLog`
- `make meminfo` / `make glxinfo` でメモリ/グラフィクス情報確認
- 壁紙テストは `save_battery` ON/OFF + 充電状態切替の両方で確認
- 通知uniformは `NotificationService` のリスナー権限ダイアログから有効化して検証
- マルチウィンドウ注意: `PreviewActivity` は `singleInstance` で `MainActivity` と並行表示される場合あり

## Key Files

| ファイル | 役割 |
|---------|------|
| `app/build.gradle.kts` | モジュールビルド設定 |
| `gradle/libs.versions.toml` | 依存バージョン管理 |
| `app/src/main/AndroidManifest.xml` | アプリ定義 (Activity, Service, Permission) |
| `.editorconfig` | コードスタイル設定 (権威的ソース) |
| `Makefile` | ビルド自動化 (Unix系向け) |
| `ROADMAP.md` | 開発ロードマップ・計画 |
| `CHANGELOG.md` | バージョン別変更履歴 |
| `CONTRIBUTING.md` | コントリビューションルール |
| `FAQ.md` | アプリ内FAQ (設定画面から参照) |
| `PRIVACY.md` | プライバシーポリシー (センサー/カメラ/マイク使用開示) |
| `fastlane/metadata/` | ストアメタデータ / スクリーンショット |
