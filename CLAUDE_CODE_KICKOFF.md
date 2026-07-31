# Claude Code 進行用プロンプト集(フェーズ分割)

このプロジェクトは Cowork 側で「型(model)」と「画面デザイン(Thymeleaf + CSS)」まで用意済み。残りのロジックを Claude Code に **1フェーズずつ** 実装させる。一度に全部投げず、各フェーズごとに「実装 → `mvn test` → コミット → push」で区切る(整合性維持のため)。

前提: JDK 21 / Maven 導入済み(`java -version` / `mvn -version`)。VS Code でこの `log-analyzer` フォルダを開く。

## 既にあるもの(再作成しない)

- 型: `model/{LogEntry, CountItem, TimeBucket, AnalyzeResult, AnalyzeRequest, BucketUnit}`
- 画面: `templates/index.html`, `static/css/style.css`
- 雛型: `LoganalyzerApplication.java`, `pom.xml`, `application.properties`

## これから実装(フェーズ順)

| フェーズ | 実装対象 | 機能ID |
|---|---|---|
| P2 | `service/LogParser.java` + テスト | F-PRS-01〜03 |
| P3 | `service/LogAggregator.java` + テスト | F-AGG-01〜06 |
| P4 | `controller/ApiController.java` | F-API-01/02 |
| P5 | `controller/PageController.java`(画面と結線) | F-UI-01/02 |
| P6 | 仕上げ(画面の最終確認・README整備) | F-UI-03/04 |

---

## 0. 最初に一度だけ: Git初期設定

GitHubで版管理する。ブラウザで空のリポジトリを作る(README・.gitignore・Licenseは付けない=空で作る)。

- リポジトリ名: `access-log-analyzer`
- Description: `A web access log analyzer built with Java & Spring Boot. Parses Apache/Nginx logs and visualizes status codes, top paths/IPs, hourly traffic, and errors.`
- 公開設定: 開発中は Private でよい(ポートフォリオとして見せる段階で Public に変更)

リポジトリは作成済み。以降のGit初期設定は Claude Code に実行させる。使うコマンド(リモートURLはこのブロックの通り。変更しない):

```powershell
git init
git add .
git commit -m "初期コミット: 設計書・型・画面デザイン一式"
git branch -M main
git remote add origin https://github.com/houjou0217/access-log-analyzer.git
git push -u origin main
```

#### これを Claude Code に貼る(Git初期設定を自動実行)

```
このフォルダで Git の初期設定を行ってください。CLAUDE_CODE_KICKOFF.md の「0. 最初に一度だけ: Git初期設定」に記載されたコマンドブロックを、上から順にそのまま実行してください。リモート origin のURLは、そのブロックに書かれている GitHub URL をそのまま使い、変更しないでください。

手順:
1. git init
2. git add . でステージし、「初期コミット: 設計書・型・画面デザイン一式」でコミット
3. git branch -M main
4. git remote add origin <ブロック記載のURL>
5. git push -u origin main

注意:
- コミット時に user.name / user.email 未設定のエラーが出たら、実行を止めて私に知らせてください(勝手に別の値を設定しない)。
- push でブラウザ認証やログインを求められたら、そこで止めて私に知らせてください(認証は私が行います)。
- 完了後、git log --oneline と git remote -v の結果を示して、成功したか報告してください。
```

これで「型と画面まで」の状態が最初のコミットとしてGitHubに残る。以降はフェーズごとにコミット・pushしていく。

---

## 1. 各フェーズのプロンプト(1つずつ貼る)

各プロンプトは、そのフェーズが終わったら Claude Code が報告して止まる作り。次のフェーズは次のプロンプトを貼って進める。

### フェーズ P2(パーサ)

```
CLAUDE.md と設計書 01〜04 を読み込んでください。今回は「フェーズP2」だけを実装します。次フェーズは先取りしないでください。

対象: service/LogParser.java(機能ID F-PRS-01/02/03、設計 03 第3章)。
- 既存の model 型(LogEntry など)に合わせて実装する。型・画面は変更しない。
- 解析不能行はスキップして数える。例外でアプリを止めない(理由をコメントに残す)。
- CLAUDE.md 8.1 の可読性ルール(日本語Javadoc・定数化・機能ID対応コメント等)を守る。
- テスト(src/test/.../service/)を書き、sample-logs/access.log を活用する。
- `mvn test` を通す。エラーは分析して修正し、通るまで繰り返す。試行内容は BUILD_LOG.md「6. エラー記録」に追記する。
- テストが通ったら、機能IDごとに日本語のコミットメッセージ(機能ID・設計書箇所入り)でコミットし、GitHubへ push する。
- 完了したら、実装した機能ID・テスト結果を日本語で報告して、いったん止まってください。
```

### フェーズ P3(集計)

```
CLAUDE.md と設計書を再確認し、「フェーズP3」だけを実装します。P2(LogParser)は完了済みの前提。

対象: service/LogAggregator.java(F-AGG-01〜06、設計 03 第4章)。
- 出力は既存の AnalyzeResult / CountItem / TimeBucket 型に合わせる。時間バケットは既存 BucketUnit を使う。TimeBucket.widthPercent は「集計内の最大件数を100%」として算出。
- 並び順・エッジケース(解析0件など)は 03 第4章の通り。
- 可読性ルール(8.1)を守る。テストを書き、`mvn test` を通す。エラーは BUILD_LOG.md に記録。
- 機能IDごとにコミット、フェーズ完了で push。
- 完了したら報告して止まってください。
```

### フェーズ P4(REST API)

```
「フェーズP4」だけを実装します。P2/P3 完了済みの前提。

対象: controller/ApiController.java(F-API-01/02、設計 03 第5章)。
- POST /api/analyze。AnalyzeRequest を受け、LogParser→LogAggregator を呼んで AnalyzeResult をJSONで返す。常に200。topN既定10・範囲外は丸め、bucketUnit不正はHOUR。
- 可読性ルールを守る。可能なら簡単なテスト(MockMvc等)も。`mvn test` を通す。BUILD_LOG.md に記録。
- コミット→push。完了したら報告して止まってください。
```

### フェーズ P5(画面と結線)

```
「フェーズP5」だけを実装します。P2〜P4 完了済みの前提。

対象: controller/PageController.java(F-UI-01/02、設計 03 第6章 / 04)。
- GET / で入力画面(既存 templates/index.html)を返す。
- POST /analyze でログを解析し、既存テンプレートが参照するモデル属性(result, rawLog, topN, bucketUnit)を渡して同一画面に結果を表示する。
- 既存テンプレート・CSSは変更しない(モデル属性名が食い違う場合のみ、テンプレート側を設計に沿って軽微修正可)。
- `mvn spring-boot:run` で起動し、http://localhost:8080 に画面が出て、sample-logs/access.log を貼ると結果が表示されることを確認(手動確認手順は私に指示)。
- 可読性ルールを守る。コミット→push。BUILD_LOG.md に記録。完了したら報告して止まってください。
```

### フェーズ P6(仕上げ)

```
「フェーズP6」だけを実施します。P2〜P5 完了済みの前提。

- 画面の時間帯別グラフ(CSS横棒)・エラー行一覧(F-UI-03/04)が設計 04 通りに表示されるか確認し、ずれがあれば設計に沿って直す。
- README.md を、動かし方・画面キャプチャの入れ方・工夫した点(テスト・設計)を含めて整える。
- 全体で `mvn test` が通り、起動して一通り動くことを最終確認する。
- コミット→push。BUILD_LOG.md に最終結果を記録。完了したら総括を報告してください。
```

---

## 2. 補足

- 各フェーズが終わるたびに、GitHub のリポジトリでコミット履歴とファイルが増えているのを確認する(版管理の実感)。
- エラーが解決できない・設計解釈に迷うときは、そのエラー全文を Cowork(こちら)に貼れば、設計者視点で手伝える。
- `target/` はビルド成果物。`.gitignore` で除外済み。
