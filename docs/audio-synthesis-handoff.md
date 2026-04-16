# GLSL Audio Synthesis — 引き継ぎドキュメント

このドキュメントは ShaderNerd に追加中の **WaveNerd 風 GLSL オーディオ合成機能** を別の AI/開発者が引き継ぐためのものです。最終更新: 2026-04-16。

ROADMAP.md の "GLSL Audio Synthesis (WaveNerd-inspired)" セクションと併せて読んでください。本書は「実装の現状 + これからやること + 落とし穴」に特化しています。

---

## Status update 2026-04-16

- Steps 1-7 are complete.
- The pre-Apply-Cue sample-browser work is complete.
- The Step 6 transport-bar plan later in this file is obsolete.
- Current implementation:
  - `run_code` is the only play button and always restarts playback from time 0.
  - `pause_audio` sits next to `run_code`.
  - BPM is edited directly in `audio_bpm_input`.
  - `F0..F7` faders live in a dedicated row and feed `fader0..7`, with values persisted in `SharedPreferences`.
  - Rewind, nudge, BPM +/- buttons, and the separate transport bar were removed.
  - `ShaderRenderer.TimeSource` is fed from `AudioShaderPlayer.getPlaybackTimeSeconds()`, so the visual `time` uniform stays locked to audio playback and freezes on pause.
  - When `audio_shader` exists, `MainActivity` keeps the embedded preview path active even if the preferred run mode would normally open `PreviewActivity`.
  - `AudioSamplesActivity` now behaves like a real folder browser: it loads one directory at a time, lets the user open subfolders from the UI, previews audio files over a waveform backdrop with a moving playhead, and inserts `sample_*` declarations into the current audio shader.
  - `AudioSampleRepository` persists a hidden cache file in the selected audio folder when possible so waveform metadata and sample mappings can be reused on later opens.
  - Inserting a sample now opens a small dialog for the sampler name and trim option, then writes a processed cache WAV under `.shadernerd_audio_cache/` so shader bindings can use the short custom name without depending on the original long path-based uniform.
  - `AudioShaderPlayer` now scans for `uniform sampler2D sample_*;` declarations, decodes matching files through `AudioSampleRepository` / `AudioSampleDecoder`, and binds them as RGBA32F textures alongside `sample_*_meta`.
- Next steps:
  - Step 8: analyzer UI and audio readback plumbing
  - Step 9: apply-cue workflow
- Verification:
  - `./gradlew.bat assembleDebug` passes.
  - Physical-device reinstall to `a6d53dfd` completed.
  - Device smoke tests confirmed play/pause, preview freeze-on-pause, and visible `F0..F7` faders with live value updates.

## 1. ゴール

GLSL フラグメントシェーダーで音を作れるようにする。`vec2 mainAudio(vec4 time)` を書くと、それが PCM オーディオとして再生される。リファレンスは [wavenerd-deck](https://github.com/FMS-Cat/wavenerd-deck) (`_refs/wavenerd-deck/` に丸ごと置いてある)。

ただし wavenerd は DJ ツール、ShaderNerd は GLSL エディタ。次は **削った機能**:

- gain / mix / cue fader / xfader / volume
- MIDI 入力
- サンプル / wavetable / 画像読み込み
- デッキ複数枚

逆に **追加した制約**:

- **デフォルト無音**: 既存シェーダーが突然音を出さないよう、`audio_shader` が空ならエンジンを起動しない
- **8 縦フェーダー固定**: wavenerd の knob を縦フェーダー UI で代替 (タッチ前提)
- **下部バーはモード切替式**: Faders / Vectorscope / Oscilloscope / Spectrum / None をサイクル

PoC ターゲットは **440Hz サイン波が鳴ること**。

---

## 2. シェーダー契約

ユーザーは次のような関数を書く:

```glsl
// PRE/POST チャンクが #version, precision, uniform, out, main を全部用意してくれる
// ユーザーはこの関数だけ書けばいい

vec2 mainAudio(vec4 time) {
  // time.x = beat 内秒  (0 .. 60/bpm)
  // time.y = bar 内秒   (4 beat)
  // time.z = 16 bar 内秒
  // time.w = 大ループ秒 (0 .. 1e16)
  float freq = 440.0;
  return vec2(0.3 * sin(2.0 * _PI * freq * time.w));
}
```

自動注入される uniform (`audio/AudioShaderChunks.java` の `PRE` 文字列):

| uniform | 意味 |
|---|---|
| `float bpm` | 現在の BPM |
| `float sampleRate` | 48000 固定 |
| `float _deltaSample` | 1 / sampleRate |
| `float _framesPerRender` | 2048 (= 128 \* 16) |
| `vec4 timeLength` | (beatSec, barSec, 16barSec, 1e16) |
| `vec4 _timeHead` | (beat, bar, 16bar, total) — ブロック先頭の時刻 |
| `vec4 param_knob0..7` | Lagrange 用履歴 (y0, y1, y2, y3) |

ヘルパー関数 `paramFetch(vec4 param)` も PRE に含まれる。これはピクセル X 座標 (= ブロック内サンプル番号) から 4 点の履歴値を Lagrange 3 次補間する。GLSL 内の使い方:

```glsl
float vol = paramFetch(param_knob0); // 0..1 に滑らかに変化
```

POST チャンク (`audio/AudioShaderChunks.java` の `POST`) が `main()` を生やし、`mainAudio()` を呼んで NaN/Inf を 0 に潰し `_fragColor.rg` に書き込む。R = 左、G = 右。

---

## 3. 完了済み (Step 1〜4)

ビルドは `./gradlew assembleDebug` で通る。**実機未検証**。

### Step 1: DB v6 → v7

`shaders.audio_shader TEXT` カラムを追加。

- `database/DatabaseContract.java` に `AUDIO_SHADER` 定数追加
- `database/Database.java` の OpenHelper version を 6 → 7
- `database/dao/ShaderDao.java`:
  - `addAudioShaderColumn()` ALTER 文を追加し `onUpgrade(... < 7)` から呼ぶ
  - `createShadersTable()` に新カラムを含める
  - `getShader()` / `getRandomShader()` の SELECT に AUDIO_SHADER を追加
  - `updateShader(id, shader, audioShader, thumb, quality, updateAudioShader)` オーバーロード追加 (旧 4 引数版は内部的に新版を呼ぶ)
  - 7 引数の public static `insertShader(...)` を 8 引数 (audioShader 追加) に拡張 — 呼び元は `importShaders` のみ
  - `importShaders()` で fromDb に AUDIO_SHADER カラムが存在すれば持ち越す (v6 ソースなら NULL)
- `database/DataRecords.java`: `Shader` レコードに `@Nullable String audioShader` フィールド追加
  - 既存呼び出し側はすべて `shader.fragmentShader()` のみ参照しているので影響なし

**注意**: 既存の `ShaderDao#updateShader(id, shader, thumb, quality)` 4 引数版は audioShader を変更しない。タブ UI 実装時に `ShaderManager.saveShader()` から新オーバーロードに切り替えること。

### Step 2-4: オーディオエンジン

新パッケージ `de.markusfisch.android.shadereditor.audio/` 配下に 3 ファイル。

#### `audio/AudioShaderChunks.java`
- `VERTEX` / `PRE` / `POST` の文字列定数
- `wrap(userCode)` ヘルパー
- 内容は `_refs/wavenerd-deck/src/renderer/shaderchunks.ts` のほぼ逐語移植 (wavetable/sample 系のヘルパーは音源参照しないので除外)

#### `audio/BeatClock.java`
- `setBpm(float)` で BPM 変更時に位相保持 (`sixteenBar *= prev/new`)
- `advance(double seconds)` で進める
- `snapshot(timeHead, lengths)` でレンダー前に uniform 値を取得
- `nudge`, `rewind`, getter 一式
- すべて `synchronized` でスレッドセーフ

#### `audio/AudioShaderPlayer.java`
本体。約 400 行の単一クラス。設計のキモ:

- **専用スレッド** `Thread("AudioShader")` を `start()` で起こし、内部で EGL pbuffer + GL リソース + AudioTrack を全部初期化する
- **EGL pbuffer**: GLES 3.0 コンテキストを 1×1 pbuffer に作る (`EGL14` API)。GLSurfaceView と完全に独立
- **FBO**: `RGBA32F` テクスチャ 2048×1 + framebuffer
- **AudioTrack**: PCM_FLOAT, stereo, 48000Hz, MODE_STREAM。バッファ ~2 ブロック分
- **ループ**: `pauseLock.wait()` で停止可能 → 再開後 `pendingSource` があれば再コンパイル → uniform 設定 → `glDrawArrays` → `glReadPixels(..., GL_RGBA, GL_FLOAT, FloatBuffer)` → R/G を deinterleave して `interleaved[]` に詰める → `audioTrack.write(..., WRITE_BLOCKING)` → `beatClock.advance(2048/48000)`
- **WRITE_BLOCKING がバックプレッシャー**: AudioTrack のリングバッファが満タンなら `write()` はブロックする。これにより wavenerd 側のリングバッファ + latency-blocks pre-render は不要
- **public API** (UI スレッドから呼ぶ):
  - `start()` / `stop()` — エンジン起動/停止
  - `play()` / `pause()` / `rewind()`
  - `setSource(@Nullable String)` — `null`/空で program=0 になり無音
  - `setBpm(float)` / `getBpm()` / `getBeatClock()`
  - `setKnob(int index, float value)` — `0..1` を想定 (clamp は GLSL 側 `paramFetch`)
- **Lagrange 履歴**: ネスト private クラス `WavenerdParam` が `value` (volatile, 任意スレッドから書込) と `y0..y3` (GL スレッドのみ) を持つ。ブロックレンダー直前に `update()` で履歴シフト
- **エラー時**: コンパイル/リンク失敗時は `program = 0` のまま、ループは無音を出し続ける (例外で死なない)

**まだ実装してない**:
- `applyCue()` (Step 9): 現在は再コンパイル即差し替え。次 bar 境界での scissor 2 回描画は未実装
- ビジュアライザー用の波形 readback 公開: 内部 `interleaved[]` 配列を別スレッドに渡す仕組みなし

---

## 4. 今やる予定 (Step 5)

**エディタ Visual/Audio タブ切替**。これは UI 改修なので影響範囲が広い。

### 案 (ユーザー承認済み)

- `EditorFragment` 上部に Material `TabLayout` を追加 (Visual / Audio 2 タブ)
- `ShaderEditor` (extends `LineNumberEditText`) 自体は 1 個のまま、タブ切替時に保持しているソース文字列を入れ替える
- `ShaderEditorApp.editHistory` の `UndoRedo` をタブ単位で持つ → キーは `<shaderId, "visual" | "audio">` の合成キー
- `ShaderManager#saveShader()` で新 `updateShader(..., audioShader, ..., updateAudioShader=true)` を呼んで両方保存
- `ShaderManager#loadShader(id)` で `shader.audioShader()` を取り出し、`EditorFragment` にセット
- Audio タブが空なら `AudioShaderPlayer.stop()`、非空なら `start()` + `setSource()`
- (見送り可) シェーダーリストで音声付きのものに音符アイコン表示

### 触る必要があるファイル (推定)

| ファイル | 変更内容 |
|---|---|
| `fragment/EditorFragment.java` | TabLayout 追加 / アクティブタブ管理 / `ShaderEditor` のテキスト差し替え |
| `widget/ShaderEditor.java` または `LineNumberEditText.java` | 必要なら 2 ソース対応 (まずは EditorFragment 側で文字列保持 + setText だけで十分) |
| `view/UndoRedo.java` | キーを `(long shaderId, String tab)` の `Pair` に拡張 or 別の `EditHistory` を `audio` 用に追加 |
| `app/ShaderEditorApp.java` | `editHistory` 用とは別に `audioEditHistory` を持つのが手っ取り早いかも |
| `activity/managers/ShaderManager.java` | save/load 経路で audioShader を扱う |
| `activity/MainActivity.java` | `AudioShaderPlayer` のライフサイクル (onResume/onPause/onDestroy) |
| `res/layout/fragment_editor.xml` | TabLayout 追加 |
| `res/values/strings.xml` | "Visual" / "Audio" |

### Step 5 着手前にユーザーへ確認したいこと

1. **`UndoRedo` の扱い** — 別キー方式 vs 別インスタンス方式。コードが少なく済むのは後者
2. **Audio タブの空判定** — 空文字 vs `null` どちらをデフォルトにするか (DB は両方 OK)
3. **PoC で動くか実機検証** — Step 5 を入れる前に MainActivity から手動で `AudioShaderPlayer` を叩いて 440Hz が鳴るか確かめてもいいか? (UI 無しで動作確認 → 鳴らなければ Step 5 やる前に修正)

---

## 5. 残作業 (Step 6 以降)

ROADMAP.md 参照。ざっくり:

- **Step 6**: トランスポート UI (▶/⏸/⟲/BPM ±/Nudge ◀▶/beat インジケータ)
- **Step 7**: 8 縦フェーダー UI + `setKnob()` 配線 (Lagrange はエンジン側で実装済み)
- **Step 8**: ビジュアライザー (Vectorscope → Oscilloscope → Spectrum)
  - 波形を UI スレッドに渡す経路を `AudioShaderPlayer` に追加する必要あり (`ConcurrentLinkedQueue<float[]>` か、最新 1 ブロックだけ AtomicReference で公開)
  - Spectrum は Java 側 FFT が必要 (`org.jtransforms` か自前 radix-2)
- **Step 9**: Apply Cue (次 bar 境界での swap, scissor 2 回描画)

---

## 6. 落とし穴・注意点

### GL スレッドルール
- `AudioShaderPlayer` の audio thread は **完全に独立した GL コンテキスト**を持つ
- メインの `ShaderRenderer` (映像) の GL スレッドとは **共有していない**
- → リソースを共有しない限り干渉しない。同時に動かしても安全
- ただし、`AudioShaderPlayer` の `start()` / `stop()` は UI スレッドから呼ぶこと。GL リソースは audio thread 内でのみ触る

### EGL pbuffer の制限
- 1×1 のダミーサーフェスを使っている。実際の描画は FBO に行く
- GLES 3.0 必須 (RGBA32F, FloatBuffer readPixels)。GLES 2.0 デバイスでは動かない → ShaderNerd の minSdk は 23 だがランタイムで GLES バージョン確認するべき (まだ未実装)
- `eglMakeCurrent` 失敗時はエンジンが起動しないだけで、UI には影響しない

### AudioTrack
- `WRITE_BLOCKING` は API 21+。OK
- `AudioTrack.Builder` は API 23+。minSdk 23 でギリギリ
- バッファサイズは `getMinBufferSize()` と `framesPerRender * 2 channels * 4 bytes * 2 blocks` の大きいほう
- `pause()` だけだと既存バッファは消えない → `flush()` も呼ぶ (rewind と stop で実施済み)

### BeatClock の精度
- `double` で持っている。`sixteenBar` がある程度大きくなると `mod` で精度落ちる可能性がある
- 16 bar の最大 = 16 \* 4 \* 60/60bpm = 64 秒。double で問題なし
- `total` は `1e16` で wrap。これも double で十分

### スレッドセーフティ
- `pendingSource` は `volatile String`。null チェックして読み出し、すぐ null に戻す (原子性問題なし、レース時は次のブロックで反映)
- `WavenerdParam.value` は `volatile float`。GL スレッド以外から書く
- `BeatClock` は全メソッド `synchronized`
- `playing` / `running` / `rewindRequested` は `volatile boolean`

### コンパイルエラーの扱い
- `recompile()` 失敗時は `program = 0` のまま無音継続
- エラーログは `Log.e(TAG, ...)` のみ。**UI へのフィードバック未実装**
- Step 5 以降で `compile error → Snackbar` の経路を作る必要あり (映像の `ShaderRenderer` がやっているのと同様)

### `ShaderEditor` の ASCII 制限
- `widget/ShaderView.removeNonAscii()` で映像シェーダーは ASCII 限定にしている
- 音声シェーダーも同じ制約を継承するか? → たぶん継承すべき。Step 5 で同じ呼び出しを通す

### CHANGELOG はまだ更新しない
- プロジェクトルール: 完了したタスクのみ CHANGELOG に書く
- この機能は丸ごとリリースされてから (Step 9 完了後) 1 行でまとめる予定

---

## 7. リファレンス対応表

`_refs/wavenerd-deck/src/` から移植したものの対応:

| 元 (TypeScript) | 移植先 (Java) |
|---|---|
| `renderer/shaderchunks.ts` PRE/POST/VERTEX | `audio/AudioShaderChunks.java` |
| `renderer/RendererImpl.ts` `createFramebuffer` / `render` / `readBuffer` | `AudioShaderPlayer#initGl` / `renderBlock` / `readback` |
| `BeatManager.ts` | `audio/BeatClock.java` |
| `WavenerdDeckParam.ts` | `AudioShaderPlayer.WavenerdParam` (private) |
| `WavenerdDeck.ts` `__collectUniforms` | `AudioShaderPlayer#renderBlock` のセクション |
| `constants.ts` `BLOCK_SIZE` | `AudioShaderPlayer.BLOCK_SIZE` |

未移植 (削除済み):
- `BufferReaderProcessor` (AudioWorklet) — Android では不要 (AudioTrack で代替)
- リングバッファ + latency-blocks 先読み — 同上
- wavetable/sample テクスチャ系のヘルパー
- `__samples` 関連すべて

---

## 8. 動作確認手順 (まだやってない)

PoC 確認の最短経路 (Step 5 を待たずにできる):

```java
// MainActivity.onCreate に一時的に追加
AudioShaderPlayer player = new AudioShaderPlayer();
player.start();
player.setSource(
  "vec2 mainAudio(vec4 time) {\n" +
  "  return vec2(0.3 * sin(2.0 * _PI * 440.0 * time.w));\n" +
  "}\n"
);
player.setBpm(140f);
player.play();
```

期待: 440Hz サイン波が両チャンネルから出る。

うまく行かない場合のチェックリスト:
1. logcat で `AudioShaderPlayer` タグを探す → "compile failed" / "link failed" / "framebuffer incomplete" が出ていないか
2. `eglChooseConfig` が失敗していないか (GLES 3.0 非対応端末)
3. AudioTrack の minBufferSize が 0 を返していないか (オーディオ HAL の問題)
4. `glReadPixels` で `GL_INVALID_OPERATION` (FBO 不完全) が出ていないか — `glGetError()` を入れる
5. `interleaved[]` に値が入っているか dump

---

## 9. 用語集

- **block**: 128 サンプル。AudioWorklet と Android AudioTrack の典型的な処理単位
- **render**: 16 block = 2048 サンプルを 1 回のシェーダー描画で生成
- **sixteenBar**: 16 bar (= 64 beat) のループ。wavenerd ではループバックの単位として使う
- **time head**: ブロック先頭の時刻。ブロック内の各サンプルは `_timeHead + sampleIndex * _deltaSample`
- **knob**: WaveNerd のパラメータ。ShaderNerd では UI が縦フェーダー
- **cue**: WaveNerd の「次の bar 境界で新しいシェーダーに差し替える」機能。ShaderNerd では Step 9 で実装予定 (現状は即時差し替え)

---

## 付録: ファイル一覧

```
app/src/main/java/de/markusfisch/android/shadereditor/
├── audio/                              # ← 新設
│   ├── AudioShaderChunks.java          # PRE/POST/VERTEX 文字列
│   ├── BeatClock.java                  # BPM/beat/bar/16bar 管理
│   └── AudioShaderPlayer.java          # 本体 (EGL + GL + AudioTrack)
└── database/
    ├── DatabaseContract.java           # AUDIO_SHADER 定数追加
    ├── Database.java                   # version 6 → 7
    ├── DataRecords.java                # Shader.audioShader フィールド追加
    └── dao/ShaderDao.java              # マイグレーション + insert/update/import 拡張
```

参照: `_refs/wavenerd-deck/` (オリジナル TypeScript 実装)
