# Claude Code × Android Studio × GitHub 効率的開発環境構築ガイド

## 概要

この3つのツールを連携させることで、以下のワークフローが実現できます：

```
GitHub (コード管理)
    ↕ push/pull
Claude Code (AI補助 + 自動化)
    ↕ ファイル編集・ビルド実行
Android Studio (IDE + エミュレータ)
```

---

## 1. 前提条件のインストール

### 必要なツール
```bash
# Android Studio
# https://developer.android.com/studio からダウンロード

# Android SDK コマンドラインツール（Android Studio経由でインストール）
# SDK Manager → SDK Tools → Android SDK Command-line Tools にチェック

# Java (JDK 17以上推奨)
sudo apt install openjdk-17-jdk   # Ubuntu/Debian
brew install openjdk@17            # macOS

# Git
sudo apt install git               # Ubuntu
brew install git                   # macOS

# GitHub CLI
sudo apt install gh                # Ubuntu
brew install gh                    # macOS

# Node.js (Claude Code の依存)
# https://nodejs.org/ からLTS版をインストール

# Claude Code
npm install -g @anthropic-ai/claude-code
```

### 環境変数の設定（~/.bashrc または ~/.zshrc）
```bash
# Android SDK パス（パスは環境に合わせて変更）
export ANDROID_HOME=$HOME/Android/Sdk          # Linux
export ANDROID_HOME=$HOME/Library/Android/sdk  # macOS

export PATH=$PATH:$ANDROID_HOME/emulator
export PATH=$PATH:$ANDROID_HOME/platform-tools
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

# Java ホーム
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64  # Linux
```

---

## 2. GitHub 連携の設定

### SSHキーの設定
```bash
# SSHキー生成
ssh-keygen -t ed25519 -C "your_email@example.com"

# 公開鍵をGitHubに追加
cat ~/.ssh/id_ed25519.pub
# → GitHub Settings → SSH and GPG keys → New SSH key に貼り付け

# 接続確認
ssh -T git@github.com
```

### GitHub CLI 認証
```bash
gh auth login
# → GitHub.com → SSH → ブラウザで認証
```

### リポジトリのクローン
```bash
# SSHでクローン（推奨）
git clone git@github.com:YOUR_USERNAME/YOUR_REPO.git
cd YOUR_REPO
```

---

## 3. Android Studio の設定

### プロジェクトを開く
1. Android Studio を起動
2. **File → Open** → クローンしたリポジトリのAndroidプロジェクトフォルダを選択
3. Gradle sync が自動実行される

### 推奨プラグイン
- **GitHub Copilot**（任意）: AIコード補完
- **ADB Idea**: ADB操作をIDEから実行
- **Markdown**: README等の編集

### Gradle の設定確認（build.gradle.kts）
```kotlin
android {
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        targetSdk = 34
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
```

---

## 4. Claude Code JetBrains プラグインの活用

### プラグインの概要

**Claude Code [Beta]** という公式プラグインが JetBrains Marketplace に存在し、Android Studio にインストール可能です。
ただし、**プラグインは CLI の補助機能**であり、CLI のインストールが前提です。

### インストール手順
1. Android Studio → **Settings → Plugins → Marketplace**
2. `Claude Code` で検索 → `Claude Code [Beta]` をインストール
3. IDE を完全に再起動
4. Settings → Tools → Claude Code [Beta] でパスを確認

### プラグインでできること（CLIとの違い）

| 機能 | CLIのみ | プラグイン追加後 |
|------|---------|---------------|
| コード生成・修正 | ターミナルで操作 | IDE内から `Ctrl+Esc` で即起動 |
| 差分確認 | ターミナル表示 | **IDEネイティブのdiffビューア**で確認 |
| 現在のファイル共有 | 手動でパスを伝える | **選択範囲・開いているファイルを自動共有** |
| Lintエラーの共有 | 手動でコピペ | **IDE診断情報を自動送信** |
| ファイル参照挿入 | 手動入力 | `Alt+Ctrl+K` ショートカット |

### 結論：プラグインは「あると便利」だが必須ではない

- **CLIだけでも全機能は使える**。プラグインはUXを向上させる補助ツール
- 最大のメリットは「IDEとターミナルの切り替え不要」と「コンテキスト自動共有」
- Android開発では特に、Lint警告の自動共有が便利

---

## 5. Claude Code の設定

### プロジェクトルートで初期化
```bash
cd YOUR_REPO
claude
```

### CLAUDE.md の作成（Claude への指示ファイル）
```markdown
# プロジェクト概要
Androidアプリ: [アプリ名]
言語: Kotlin
最小SDK: API 24

# 開発ルール
- Kotlin のコーディング規約に従う
- コミット前に ./gradlew test を実行
- ブランチ名: feature/xxx, fix/xxx, claude/xxx

# よく使うコマンド
- ビルド: cd breakout-android && ./gradlew assembleDebug
- テスト: ./gradlew test
- Lint:   ./gradlew lint
```

### 便利な Claude Code コマンド例
```bash
# バグ修正を依頼
claude "GameView.ktの衝突判定バグを修正して"

# 機能追加
claude "スコア保存機能をSharedPreferencesで実装して"

# コードレビュー
claude "app/src/main/java/以下のコードをレビューして問題点を教えて"

# テスト生成
claude "GameLogic.ktのユニットテストを作成して"
```

---

## 5. 効率的な開発ワークフロー

### 標準フロー
```
1. GitHub からブランチを作成
   git checkout -b feature/new-feature

2. Claude Code で実装
   claude "○○機能を実装して"

3. Android Studio でデバッグ
   - エミュレータで動作確認
   - Logcat でログ確認

4. テスト実行
   cd breakout-android && ./gradlew test lint

5. GitHub にプッシュ
   git add -A && git commit -m "feat: ○○機能を追加"
   git push -u origin feature/new-feature

6. Pull Request を作成
   gh pr create --title "feat: ○○機能" --body "説明..."
```

### Claude Code × Gradle の自動化
```bash
# ビルドエラーを Claude に自動修正させる
./gradlew assembleDebug 2>&1 | claude "このビルドエラーを修正して"

# テスト失敗を修正
./gradlew test 2>&1 | claude "テスト失敗を修正して"
```

---

## 6. Git フック設定（品質自動チェック）

### .git/hooks/pre-commit
```bash
#!/bin/sh
# コミット前に自動でLintとテストを実行

cd breakout-android

echo "Running lint..."
./gradlew lint --quiet
if [ $? -ne 0 ]; then
  echo "Lint failed. Fix errors before committing."
  exit 1
fi

echo "Running tests..."
./gradlew test --quiet
if [ $? -ne 0 ]; then
  echo "Tests failed. Fix tests before committing."
  exit 1
fi

echo "All checks passed!"
```

```bash
chmod +x .git/hooks/pre-commit
```

---

## 7. Android エミュレータとClaude Code の連携

### ADB を使った自動テスト
```bash
# エミュレータの一覧確認
emulator -list-avds

# エミュレータ起動
emulator -avd Pixel_6_API_34 &

# APK インストール
adb install breakout-android/app/build/outputs/apk/debug/app-debug.apk

# ログ監視（Claude にエラー解析させる）
adb logcat | grep -E "ERROR|FATAL|Exception" | claude "このエラーの原因と修正方法を教えて"
```

---

## 8. よくある問題と解決策

| 問題 | 原因 | 解決策 |
|------|------|--------|
| Gradle sync 失敗 | SDK バージョン不一致 | `local.properties` の `sdk.dir` を確認 |
| ADB デバイス未認識 | USBデバッグ無効 | 開発者オプション → USBデバッグを有効化 |
| Claude Code が遅い | ファイル数が多すぎる | `.claudeignore` で除外設定 |
| Git push 失敗 | 認証エラー | `gh auth login` で再認証 |

### .claudeignore の設定例
```
# ビルド成果物
breakout-android/build/
breakout-android/app/build/
.gradle/

# IDE 設定
.idea/
*.iml

# 大きなバイナリ
*.apk
*.aab
```

---

## 9. おすすめの作業分担

| タスク | 使うツール |
|--------|-----------|
| 新機能の設計・実装 | Claude Code |
| UIレイアウトの調整 | Android Studio (Layout Editor) |
| デバッグ・ブレークポイント | Android Studio Debugger |
| リファクタリング | Claude Code |
| パフォーマンス分析 | Android Studio Profiler |
| コードレビュー・PR | GitHub + Claude Code |
| テスト自動化 | Claude Code + Gradle |

---

## まとめ

- **Claude Code**: コード生成・修正・リファクタリングをAIに任せる
- **Android Studio**: ビジュアルデバッグ・UIプレビュー・プロファイリング
- **GitHub**: バージョン管理・PR・CI/CD

この3つを組み合わせることで、実装速度を大幅に向上させながら、コード品質も維持できます。
