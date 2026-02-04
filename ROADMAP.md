# Roadmap

開発の道筋をここで管理する。優先度の高い順に記載。

**注意: 完了したタスクはここに書かない。完了した作業は `CHANGELOG.md` に記録すること。**

---

## Next (未着手)

### 1. バックバッファ高精度化
バックバッファ (`backbuffer`) テクスチャを float 32bit / linear フィルタリングに変更し、mipmap を生成するようにする。現状の低精度フォーマットでは精度不足になるケースに対応。

### 2. Compute Shader サポート
GLES 3.1 の Compute Shader を利用し、`computeTex` / `computeTexBack` バッファに対して `imageLoad` / `imageStore` / `imageAtomicAdd` 等の操作を行える機能を追加する。

**参考プロジェクト**:
- [Bonzomatic-Compute](https://github.com/wrightwriter/Bonzomatic-Compute) - Windows向けGLSL livecodingアプリ。Fragment shader側からcompute bufferへアクセスする仕組みの参考
- [ShaderEditor-Compute](https://github.com/vrcyue/ShaderEditor-Compute) - ShaderEditor上にCompute Shader機能を実装した既存フォーク。実装の直接的な参考

### 3. 完全フルスクリーンモード
現在の「Toggle Code」によるコード非表示に加え、ツールバー・ステータスバー・ナビゲーションバーなどすべてのUIを非表示にし、シェーダー出力のみをスマホ画面全体に表示する完全フルスクリーンモードを実装する。

### 4. テクスチャインポート機能拡充
現在のテクスチャインポートの制限を撤廃し、より柔軟にする。
- **サイズ制限撤廃**: 正方形のみ / 解像度2^nのみ / 最大1024px の制約を取り除き、縦横を自由に入力できるようにする。デフォルトは元画像の解像度。上限は4096px
- **正方形クロップ強制の撤廃**: 「Texture properties」設定前の「Crop image」で正方形クロップを強制する仕様を廃止し、任意アスペクト比のテクスチャをそのままインポート可能にする

### 5. カスタム Vertex Shader
現在はビルトインの頂点シェーダーのみ使用可能だが、ユーザーが独自の Vertex Shader を記述・編集できるようにする。

### 6. マルチパスレンダリング
ShaderToy のように複数パス (Buffer A, B, C, D → Image) でレンダリングを行う機能を追加する。パス間でテクスチャを受け渡し、複雑なエフェクトの構築を可能にする。

### 7. Google Drive 連携
Google Drive と連携し、シェーダーをクラウドに保存・同期する機能を追加する。

## In Progress (進行中)

(現在取り組んでいるタスクをここに記載)
