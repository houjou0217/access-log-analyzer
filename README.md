# Web Access Log Analyzer

Apache / Nginx の Web アクセスログ(Combined Log Format)を解析し、アクセス状況とエラー傾向を集計して表示する Spring Boot アプリケーション。インフラ運用で行う「ログからの状況把握」を題材にした、Java 学習・ポートフォリオ用プロジェクト。

ログを貼り付けて「解析する」を押すと、同じページに集計結果が表示される。JavaScript ライブラリは使わず、Thymeleaf のサーバーサイドレンダリングと CSS だけで画面を作っている。

## できること

| 機能 | 内容 |
|---|---|
| ログのパース | Combined Log Format の1行を9項目に分解。解析できない行はスキップして件数を数える |
| サマリ | 総行数 / 解析成功数 / 解析不能数 |
| ステータス別集計 | 区分(2xx / 3xx / 4xx / 5xx)と個別コード別の件数 |
| 上位パス・上位IP | アクセス件数の多い順に Top N(既定10、1〜100) |
| 時間帯別リクエスト数 | 1時間 / 10分 / 1分単位で集計し、CSS の横棒グラフで表示 |
| エラー行の抽出 | ステータス 400 以上の行を時刻順に一覧表示(4xx は橙、5xx は赤) |
| REST API | `POST /api/analyze` で集計結果を JSON で取得 |

## 技術スタック

- Java 21 (LTS) / Spring Boot 3.3.5 / Maven
- Thymeleaf(画面)+ プレーン CSS(グラフも CSS の横棒で実装)
- JUnit 5 + Spring MockMvc(テスト72件)

## 動かし方

### 前提

- JDK 21(`java -version` で `openjdk version "21..."` が出ること)
- Maven(`mvn -version` が通ること)

環境構築の手順は [SETUP.md](SETUP.md) を参照。

### 起動

```powershell
mvn package
java -jar target/loganalyzer-0.0.1-SNAPSHOT.jar
```

起動後、ブラウザで `http://localhost:8080` を開く。テキストエリアに [sample-logs/access.log](sample-logs/access.log) の中身を貼り付けて「解析する」を押すと結果が表示される。停止は `Ctrl + C`。

> **補足: `mvn spring-boot:run` について**
> このプロジェクトのフォルダパスに日本語(`デスクトップ`)が含まれている場合、`mvn spring-boot:run` は
> `ClassNotFoundException` で起動に失敗する。`spring-boot:run` が起動する別プロセスへ渡すクラスパスが
> 文字化けするためで、コード側の問題ではない(`mvn test` や `mvn package` は正常に動く)。
> 上記の `java -jar` を使うか、プロジェクトを日本語を含まないパス(例 `C:\dev\log-analyzer`)へ移すと
> `mvn spring-boot:run` も使えるようになる。IntelliJ から実行する場合は IDE が直接 JVM を起動するため問題は起きない。

### テストの実行

```powershell
mvn test
```

## 画面キャプチャ

キャプチャは `docs/screenshots/` に置き、以下のように README から参照する。

1. アプリを起動して `http://localhost:8080` を開く。
2. `sample-logs/access.log` の中身を貼り付けて「解析する」を押す。
3. 入力画面と結果画面をそれぞれスクリーンショットし、`docs/screenshots/input.png` / `docs/screenshots/result.png` として保存する(Windows は `Win + Shift + S` で範囲キャプチャ)。
4. 下のコメントを外して画像を表示する。

<!--
### 入力画面

![入力画面](docs/screenshots/input.png)

### 解析結果

![解析結果](docs/screenshots/result.png)
-->

## REST API

`POST /api/analyze` に JSON を送ると、集計結果が JSON で返る。

```powershell
curl -X POST http://localhost:8080/api/analyze `
  -H "Content-Type: application/json" `
  -d '{"rawLog":"192.168.0.10 - - [30/Jul/2026:10:15:32 +0900] \"GET /index.html HTTP/1.1\" 200 1043 \"-\" \"Mozilla/5.0\"","topN":10,"bucketUnit":"HOUR"}'
```

| パラメータ | 既定値 | 説明 |
|---|---|---|
| `rawLog` | (必須) | ログ本文(複数行)。空でもエラーにならず `totalLines=0` を返す |
| `topN` | 10 | 上位件数。1未満は1、100超は100に丸める |
| `bucketUnit` | `HOUR` | `HOUR` / `TEN_MIN` / `MINUTE`。不正値は `HOUR` |

このAPIは**常に HTTP 200 を返す**。解析できない行があってもエラーにはせず、結果の `skippedCount` で表現する。

## サンプルデータ

[sample-logs/access.log](sample-logs/access.log) に26行のテスト用ログを用意している。正常なリクエスト、403 / 404 / 500 / 503 などのエラー、そして**解析できない壊れた行を1行**含んでおり、集計とエラーハンドリングの両方を確認できる。解析すると総行数26・成功25・スキップ1になる。

## 設計・実装で工夫した点

### 1. 壊れた行でアプリを止めない

実運用のログには欠損行や別形式の行が混ざる。パーサは正規表現に一致しない行・日時が不正な行・ステータスが整数でない行を**例外にせずスキップし、件数だけを数える**設計にした。`parseLine` は `Optional<LogEntry>` を返し、呼び出し側が「解析できなかった」を型で受け取れるようにしている。26行中1行が壊れていても残り25行は正しく集計される。

### 2. ロジックを1箇所に集約して画面とAPIで挙動を揃える

Top N の丸め(1〜100)と時間バケットの不正値フォールバックは、画面用 Controller と API 用 Controller の両方で必要になる。これを両方に書くと将来ずれるため、`LogAggregator.resolveTopN` と `BucketUnit.fromString` に集約し、Controller からは呼ぶだけにした。結果として、画面で999を入力しても API に999を渡しても同じく100として扱われる。

### 3. 層を分離してテストしやすくする

`controller`(入出力)/ `service`(パース・集計)/ `model`(データ) に分け、service は Spring に依存しない純粋なロジックにした。そのため集計ロジックのテストは `new LogAggregator()` するだけで書け、Spring のコンテキスト起動が不要で高速に回る。

### 4. テスト72件で仕様を固定

設計書の記述を期待値としてテストに落とし込んでいる。

| テストクラス | 件数 | 主な確認内容 |
|---|---|---|
| `LogParserTest` | 18 | 9項目の分解、日時のタイムゾーン、壊れた行のスキップ、空行の除外 |
| `LogAggregatorTest` | 27 | ステータス区分、上位の並び順(同数時は辞書順)、バケット丸め、`widthPercent`、エラー抽出 |
| `ApiControllerTest` | 12 | JSON返却、topN / bucketUnit の処理、常に200を返すこと |
| `PageControllerTest` | 15 | 入力画面、結果表示、グラフ、エラー行の色分け、パラメータの丸め |

画面のテストは**実際に Thymeleaf テンプレートを描画して検証**している。そのためモデル属性名の食い違いやテンプレート記述の実行時エラーも、ブラウザで開く前にテストで検出できる。

### 5. 表示用の値をサーバー側で計算する

横棒グラフの棒の長さは、集計時に「その集計内の最大件数を100%」とした `widthPercent`(0〜100)として算出し、`TimeBucket` に持たせている。テンプレート側は `width:○○%` を出力するだけでよく、JavaScript も計算式も不要になる。

## 実装の流れ(段階実装)

設計を確定させてから、フェーズごとに「実装 → `mvn test` → コミット → push」で区切って進めた。

| フェーズ | 内容 | 機能ID | 状態 |
|---|---|---|---|
| P1 | 雛型作成・パッケージ構成 | F-FND-01/02 | 完了 |
| P2 | ログ1行のパース + テスト | F-PRS-01〜03 | 完了 |
| P3 | 集計ロジック + テスト | F-AGG-01〜06 | 完了 |
| P4 | REST API | F-API-01/02 | 完了 |
| P5 | 画面と結線 | F-UI-01/02 | 完了 |
| P6 | 仕上げ(グラフ・エラー一覧・README) | F-UI-03/04 | 完了 |

## ドキュメント

| ファイル | 内容 |
|---|---|
| [01_requirements.md](01_requirements.md) | 要件定義(MVP範囲・段階実装) |
| [02_basic_design.md](02_basic_design.md) | 基本設計(技術スタック・構成・機能一覧) |
| [03_detailed_design.md](03_detailed_design.md) | 詳細設計(パース仕様・集計仕様・API・機能ID) |
| [04_ui_design.md](04_ui_design.md) | 画面設計(配色・レイアウト・HTML/CSS) |
| [SETUP.md](SETUP.md) | 環境構築手順 |
| [BUILD_LOG.md](BUILD_LOG.md) | 実装ログ・ビルドの試行記録・発生した問題と対処 |

## プロジェクト構成

```
log-analyzer
├─ src/main/java/com/example/loganalyzer
│  ├─ controller  … PageController(画面) / ApiController(REST API)
│  ├─ service     … LogParser(パース) / LogAggregator(集計)
│  ├─ model       … LogEntry / AnalyzeRequest / AnalyzeResult / CountItem / TimeBucket / BucketUnit
│  └─ LoganalyzerApplication.java
├─ src/main/resources
│  ├─ templates/index.html      … 入力・結果の画面(Thymeleaf)
│  └─ static/css/style.css      … スタイル(グラフの横棒もCSS)
├─ src/test/java/...            … テスト72件
└─ sample-logs/access.log       … テスト用ログ(26行)
```
