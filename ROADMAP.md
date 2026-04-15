# Roadmap

## Update 2026-04-16

- Step 6 is complete.
- The old transport-bar plan later in this file is obsolete.
- Current audio UX:
  - The existing toolbar play button (`run_code`) is the audio play button and always restarts from time 0.
  - A new pause button (`pause_audio`) sits next to it.
  - BPM is edited directly through `audio_bpm_input`.
  - The temporary transport bar, rewind button, nudge controls, and BPM +/- buttons were removed.
  - Visual `time` is synced from `AudioShaderPlayer` through `ShaderRenderer.TimeSource`.
  - When an audio shader exists, `MainActivity` keeps the embedded preview active so toolbar play/pause keeps working even if the normal run mode would open `PreviewActivity`.
- Next implementation target:
  - Step 7: 8 faders wired to `param_knob0..7`
  - Step 8: analyzer UI (vectorscope, oscilloscope, spectrum)
  - Step 9: apply-cue workflow
- Verification snapshot:
  - `./gradlew.bat assembleDebug` passes.
  - Earlier device smoke tests confirmed play/pause and visual freeze-on-pause.
  - The latest APK still needs one more physical-device reinstall because no handset is currently attached to `adb` on this machine.

開発の道筋をここで管理する。優先度の高い順に記載。

**注意: 完了したタスクはここに書かない。完了した作業は `CHANGELOG.md` に記録すること。**

---

## Next (未着手)

### 1. GLSL Audio Synthesis (WaveNerd-inspired)

GLSL フラグメントシェーダーで音声を合成する機能。`vec2 mainAudio(vec4 time)` を書くと音が鳴る。[wavenerd-deck](https://github.com/FMS-Cat/wavenerd-deck) の設計を下敷きにしつつ、DJ 用途の機能 (gain / mix / cue fader / xfader / MIDI / sample / wavetable) は削ぎ落としたシングルデッキ仕様。

#### 設計決定 (ユーザー確認済み)
- **Visual / Audio のタブ分割**: 1 シェーダーに映像用コードと音声用コードが共存。エディタ上部にタブを設け、DB では `shaders.audio_shader` カラムに別ソースとして保存
- **8 縦フェーダー固定 + Lagrange 補間**: wavenerd の knob と Lagrange 補間の仕組みをそのまま踏襲するが、UI はタッチ操作しやすい縦フェーダー (`param_knob0 .. param_knob7`)
- **下部バーはモード切替式**: Knobs / Vectorscope / Oscilloscope / Spectrum / None を一つのバーでサイクル切替
- **デフォルト無音**: 既存シェーダーが突然音を出すのを避けるため、`audio_shader` が空ならオーディオエンジンを起動しない
- **PoC ターゲット**: 440Hz サイン波が鳴れば成功

#### シェーダー契約
```glsl
#version 300 es // PRE チャンクが自動付与
// uniforms are auto-injected (bpm, time, knobs, ...)

vec2 mainAudio(vec4 time) {
  // time.x = beat 内秒 (0..60/bpm)
  // time.y = bar 内秒 (beat*4)
  // time.z = 16bar 内秒
  // time.w = 大ループ (1E16 秒)
  return vec2(sin(2.0*_PI*440.0*time.w));
}
```

Auto-inject される uniform:
- `uniform float bpm;`
- `uniform float sampleRate;`
- `uniform float _deltaSample;` (= 1/sampleRate)
- `uniform float _framesPerRender;` (= 128 * blocksPerRender, 既定 2048)
- `uniform vec4 timeLength;` (beatSec, barSec, 16barSec, 1E16)
- `uniform vec4 _timeHead;` (現在の beat, bar, 16bar, total)
- `uniform vec4 param_knob0 .. param_knob7;` (y0..y3 履歴)
- ヘルパー `float paramFetch(vec4 param)` — Lagrange 3 次補間で blockSize 境界をまたぐパラメータ変化を滑らかにする

#### アーキテクチャ
```
MainActivity
 └─ AudioShaderPlayer (シングルトン / Manager 扱い)
     ├─ audio thread (HandlerThread)
     │   ├─ EGL pbuffer context (GLSurfaceView とは独立)
     │   ├─ ShaderAudioRenderer
     │   │    ├─ RGBA32F FBO 2048×1
     │   │    ├─ program compile / cue swap
     │   │    └─ render() → readPixels → float[L,R]
     │   ├─ BeatClock (bpm, 位相保持)
     │   └─ AudioTrack (PCM_FLOAT, stereo, MODE_STREAM, blocking write)
     └─ UI thread API: play() / pause() / rewind() / setBpm() / setKnob(i, v) / applyCue(source)
```

AudioTrack の blocking `write()` で自然にバックプレッシャーがかかるため、wavenerd のようなリングバッファ + latency-blocks 先読みは不要。

#### DB マイグレーション v6 → v7
- `shaders` テーブルに `audio_shader TEXT` カラムを追加 (既存行は NULL or 空文字)
- `DatabaseContract.Shaders` / `ShaderDao` の `insert` / `update` / `query` を拡張
- 既存インポート DB の互換性を保つため `onUpgrade` でカラムを `ALTER TABLE ... ADD COLUMN`

#### BeatClock (Java 版 BeatManager)
```
__sixteenBar = (__sixteenBar + delta) mod (16 * 4 * 60/bpm)
__bar        = __sixteenBar mod (4 * 60/bpm)
__beat       = __bar mod (60/bpm)
setBpm(new): __sixteenBar *= prev/new  // 位相保持
```
`nudge(+/-)` は `__sixteenBar += nudgeSec` の一時オフセット。

#### UI 変更
- **エディタ画面**
  - 上部: Visual / Audio タブ (タブ押下で EditorFragment 内のテキストを切替、カーソル位置を保持)
  - Audio タブが非空の行はシェーダーリストで小さな音符アイコン表示
- **トランスポートバー (エディタ/プレビュー上部)**
  - ⟲ Rewind / ▶ Play / ⏸ Pause / BPM ± / Nudge ◀▶ / beat インジケータ (1..4 点灯)
  - `audio_shader` が空のときはバー非表示
- **下部モード切替バー**
  - 8 Vertical Faders ↔ Vectorscope ↔ Oscilloscope ↔ Spectrum ↔ Hidden をタップでサイクル
  - フェーダー値は `SharedPreferences` に保存 (シェーダー毎ではなくグローバル、あとで検討)

#### 実装手順
1. ~~**DB v6 → v7**: `audio_shader` カラム追加 + マイグレーション + DAO/Contract 更新~~ ✅ 完了
2. ~~**AudioShaderPlayer 骨格**: EGL pbuffer + AudioTrack + 1 フレーム描画~~ ✅ 完了 (UI 未配線)
3. ~~**シェーダー wrapping**: PRE/POST チャンクをリテラル文字列として移植~~ ✅ 完了 (実機検証はまだ)
4. ~~**BeatClock + time uniforms**: `_timeHead` が正しく進み `time.x` が beat ループする~~ ✅ 完了
5. ~~**エディタタブ**: Visual / Audio 切替 UI + `ShaderEditor` の多重バッファ~~ ✅ 完了
6. **トランスポート UI**: play / pause / rewind / bpm / nudge / beat インジケータ ← **次はここ**
7. **縦フェーダー × 8 + `param_knob0..7` + Lagrange**: スライダー移動時に `value` を更新、render 毎に `update()` で履歴シフト → `uniform4f`
8. **ビジュアライザー**: Vectorscope → Oscilloscope → Spectrum の順で実装、下部バーでモード切替
9. **Apply Cue**: 次 bar 境界で新しいプログラムに差し替え (wavenerd の scissor 2 回描画戦略を移植)

#### 既存アーキテクチャへの影響
- `ShaderRenderer` (映像) には触らない。音声は独立した `ShaderAudioRenderer` を新設
- `ShaderManager` がシェーダーを保存するとき `audio_shader` も一緒に保存
- `ShaderViewManager` と並列に `AudioShaderPlayerManager` を追加、`MainActivity` が束ねる
- `ShaderEditor` に音声コード用のサブエディタ状態を追加 (タブ切替時に `UndoRedo` を切替)


## In Progress (進行中)

### GLSL Audio Synthesis — Step 6: トランスポート UI
Visual / Audio タブは完了。次は `AudioShaderPlayer` を `MainActivity` に配線し、再生・停止・巻き戻し・BPM・nudge・beat インジケータを持つトランスポート UI を editor 画面へ追加する。引き継ぎ詳細は [`docs/audio-synthesis-handoff.md`](docs/audio-synthesis-handoff.md) を参照。
