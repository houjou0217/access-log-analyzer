# Webアクセスログ解析ツール 詳細設計書

本書は「01_requirements.md」「02_basic_design.md」を前提とする。実装を担当するAIエージェント(Claude Code等)が本書を入力仕様として実装できるよう、クラス設計・パース仕様・集計ロジック・API仕様・画面仕様・機能ID・実装フェーズを、曖昧さを排して定義する。未確定の点は「未確定事項」と明記する。

## 1. 用語・識別子の対応

| 日本語 | 英語識別子(コード内で使用) |
|---|---|
| ログ行 | `LogEntry` |
| 解析要求 | `AnalyzeRequest` |
| 解析結果 | `AnalyzeResult` |
| 件数エントリ(名称+件数) | `CountItem` |
| 時間バケット | `TimeBucket` |
| パーサ | `LogParser` |
| 集計器 | `LogAggregator` |

パッケージ構成(既定案。P1で最終確認):

```
com.example.loganalyzer
├─ controller   … 画面用・API用のController
├─ service      … LogParser / LogAggregator
├─ model        … LogEntry / AnalyzeRequest / AnalyzeResult / CountItem / TimeBucket
└─ LoganalyzerApplication.java
```

## 2. データモデル

### 2.1 LogEntry(パース済みの1行)

| フィールド | 型 | 説明 |
|---|---|---|
| `ip` | String | クライアントIP |
| `dateTime` | OffsetDateTime | 日時(タイムゾーン付き) |
| `method` | String | HTTPメソッド(GET/POST等) |
| `path` | String | リクエストパス(クエリ含む) |
| `protocol` | String | プロトコル(HTTP/1.1等) |
| `status` | int | ステータスコード |
| `size` | long | レスポンスサイズ(バイト)。`-` の場合は0 |
| `referer` | String | リファラ(`-` は空文字) |
| `userAgent` | String | ユーザーエージェント |

### 2.2 AnalyzeRequest(入力)

| フィールド | 型 | 説明 |
|---|---|---|
| `rawLog` | String | 貼り付けられたログ本文(複数行) |
| `topN` | int | 上位何件を出すか(既定10、範囲1〜100) |
| `bucketUnit` | enum(BucketUnit) | 時間バケット単位(既定 HOUR) |

BucketUnit の値: `HOUR`(1時間)、`TEN_MIN`(10分)、`MINUTE`(1分)。既定は `HOUR`。

### 2.3 CountItem / TimeBucket

- `CountItem`: `key`(String) と `count`(long)。上位パス・上位IP・ステータス別で共通利用。
- `TimeBucket`: `bucketStart`(String, 例 "2026-07-30 10:00")、`count`(long)、`widthPercent`(int, 0〜100)。`widthPercent` は棒グラフ表示用で、集計時に「その集計内の最大件数を100%」として算出する(最大件数が0のときは全て0)。

### 2.4 AnalyzeResult(集計結果)

| フィールド | 型 | 説明 |
|---|---|---|
| `totalLines` | int | 入力の総行数(空行を除く) |
| `parsedCount` | int | 解析成功行数 |
| `skippedCount` | int | 解析不能行数 |
| `statusClassCounts` | Map<String,Long> | 区分別(キー "2xx"/"3xx"/"4xx"/"5xx") |
| `statusCounts` | List<CountItem> | 個別コード別(コード昇順) |
| `topPaths` | List<CountItem> | 上位パス(件数降順、Top N) |
| `topIps` | List<CountItem> | 上位IP(件数降順、Top N) |
| `timeBuckets` | List<TimeBucket> | 時間帯別(時刻昇順) |
| `errorEntries` | List<LogEntry> | 4xx/5xx の行(時刻昇順) |

## 3. パース仕様(LogParser)

### 3.1 対象フォーマット

Combined Log Format。1行の構造:

```
%h %l %u [%t] "%r" %>s %b "%{Referer}i" "%{User-Agent}i"
```

例:
```
192.168.0.10 - - [30/Jul/2026:10:15:32 +0900] "GET /index.html HTTP/1.1" 200 1043 "https://example.com/" "Mozilla/5.0"
```

### 3.2 抽出ルール

- `%h` → ip
- `%l %u`(identとuser)は使わない(読み飛ばす。多くは `-`)
- `[%t]` → dateTime。フォーマット `dd/MMM/yyyy:HH:mm:ss Z`(ロケールは英語=Locale.ENGLISH、月名 Jul 等のため)
- `"%r"` → メソッド・パス・プロトコルに分解(空白区切り3要素)。リクエスト行が異常な場合はその行を解析不能とする
- `%>s` → status(整数)
- `%b` → size。`-` は 0
- Referer → referer(`-` は空文字)
- User-Agent → userAgent

### 3.3 実装方針

- 正規表現1本で1行を分解する(捕捉グループで各項目を取得)。推奨パターンは実装時に用意するが、上記9項目を過不足なく捕捉できること。
- 空行(トリム後に空)は総行数に数えない。
- 正規表現に一致しない行、日時パースに失敗した行、statusが整数でない行は「解析不能行」とし、`skippedCount` を増やして次の行へ進む(例外でアプリを止めない)。

### 3.4 日時の扱い

- `dateTime` は OffsetDateTime としてタイムゾーン込みで保持する。
- 時間バケット集計時の丸めは dateTime を基準に行う(3.5)。

### 3.5 時間バケットの丸め

bucketUnit に応じて dateTime を切り捨てる。

| bucketUnit | 丸め | 表示例 |
|---|---|---|
| HOUR | 分・秒を0に | `2026-07-30 10:00` |
| TEN_MIN | 分を10分単位で切り捨て、秒を0に | `2026-07-30 10:10` |
| MINUTE | 秒を0に | `2026-07-30 10:15` |

## 4. 集計仕様(LogAggregator)

入力: List<LogEntry> と AnalyzeRequest(topN・bucketUnit)。出力: AnalyzeResult。

- `statusClassCounts`: status の百の位で区分(200番台→"2xx" 等)。1xx が来た場合も "1xx" として数える。
- `statusCounts`: 個別コードごとの件数。コード昇順で並べる。
- `topPaths`: path ごとに件数を数え、件数降順に並べて先頭 topN 件。件数同数のときは path の辞書順。
- `topIps`: ip ごとに件数を数え、同様に topN 件。同数は ip の辞書順。
- `timeBuckets`: bucketUnit で丸めた時刻ごとに件数を数え、時刻昇順。リクエストが無いバケットは出さない(存在するバケットのみ)。
- `errorEntries`: status が 400 以上の LogEntry を時刻昇順で抽出。

エッジケース: 解析成功が0件のときは、各集計は空(件数0・空リスト)で返し、エラーにしない。

## 5. REST API 仕様

### 5.1 POST /api/analyze

- リクエスト(JSON): `{ "rawLog": "...", "topN": 10, "bucketUnit": "HOUR" }`
  - `topN` 省略時は10、範囲外(1未満/100超)は既定または境界に丸める。
  - `bucketUnit` 省略時は "HOUR"。不正値は "HOUR" にフォールバック。
- レスポンス(JSON): AnalyzeResult をそのままシリアライズしたもの。
- 常に HTTP 200 を返す(解析不能行があっても、それは result 内の skippedCount で表現)。入力が空の場合も totalLines=0 の結果を返す。

## 6. 画面仕様(Thymeleaf)

### 6.1 入力画面(GET /)

- ログ本文のテキストエリア(name=rawLog)
- Top N の入力(number, 既定10)
- 時間バケットの選択(select, HOUR/TEN_MIN/MINUTE, 既定HOUR)
- 送信ボタン(POST /analyze)

### 6.2 結果表示(POST /analyze)

同一テンプレート内、または結果テンプレートで以下を表示。

- サマリ: totalLines / parsedCount / skippedCount
- ステータス区分の表(2xx/3xx/4xx/5xx と件数)、個別コードの表
- 上位パスの表、上位IPの表
- 時間帯別の表(可能なら棒グラフ。グラフ実現方法は未確定事項=CSSの簡易バー or JSライブラリ)
- エラー行一覧(時刻・IP・ステータス・パス)

画面ControllerはService層を呼び、AnalyzeResult をモデルに詰めてテンプレートへ渡す。

## 7. 機能ID一覧(トレーサビリティ)

機能IDは `F-{分類}-{連番}`。分類: FND=基盤、PRS=パース、AGG=集計、API=REST API、UI=画面。

| 機能ID | 機能名 | 該当設計箇所 |
|---|---|---|
| F-FND-01 | Spring Boot雛型作成・起動確認 | SETUP / 02 2章 |
| F-FND-02 | パッケージ構成(controller/service/model) | 03 1章 |
| F-PRS-01 | ログ1行のパース(LogEntry生成) | 03 3.1-3.3 |
| F-PRS-02 | 解析不能行のスキップ・カウント | 03 3.3 |
| F-PRS-03 | 日時パースとタイムゾーン処理 | 03 3.4 |
| F-AGG-01 | 総数/成功/スキップ集計 | 03 4章 / 2.4 |
| F-AGG-02 | ステータスコード別集計(区分+個別) | 03 4章 |
| F-AGG-03 | 上位パス(Top N) | 03 4章 |
| F-AGG-04 | 上位IP(Top N) | 03 4章 |
| F-AGG-05 | 時間帯別集計(バケット丸め) | 03 3.5 / 4章 |
| F-AGG-06 | エラー行抽出(4xx/5xx) | 03 4章 |
| F-API-01 | POST /api/analyze(JSON返却) | 03 5章 |
| F-API-02 | 集計パラメータ(topN/bucketUnit)処理 | 03 5.1 / 2.2 |
| F-UI-01 | 入力画面(貼り付け+パラメータ) | 03 6.1 / 04 4.2・5・6 |
| F-UI-02 | 結果表示(サマリ・各種表) | 03 6.2 / 04 4.3・5・6 |
| F-UI-03 | 時間帯別グラフ(CSS横棒) | 03 6.2 / 04 4.3・5・6 |
| F-UI-04 | エラー行一覧表示 | 03 6.2 / 04 4.3 |

画面(F-UI-01〜04)の配色・レイアウト・HTML構造・CSSは 04_ui_design.md に確定済み。その通りに実装する。

## 8. 実装フェーズ(段階実装)

一度に全部作らず、以下のフェーズで段階的に進める。各フェーズは前フェーズの完了(動作確認・テスト通過)を開始条件とする。フェーズ内も機能ID単位で計画→実装→検証を繰り返す。

| フェーズ | 名称 | 含む機能ID | 完了条件(DoD) |
|---|---|---|---|
| P1 | 基盤構築 | F-FND-01, F-FND-02 | Spring Bootが起動し、パッケージ構成ができている |
| P2 | パース | F-PRS-01〜03 | Combined Log Format 1行を各項目に分解でき、異常行をスキップできる(テスト通過) |
| P3 | 集計 | F-AGG-01〜06 | 各集計が仕様どおり算出される(テスト通過) |
| P4 | REST API | F-API-01, F-API-02 | ログを渡すと集計結果JSONが返る |
| P5 | 画面 | F-UI-01, F-UI-02 | 画面から貼り付け→解析→結果が表で見える |
| P6 | 仕上げ | F-UI-03, F-UI-04 | グラフ・エラー一覧・README整備でポートフォリオとして見せられる |

### 8.1 テスト必須の機能ID

- F-PRS-01(1行パース): 正常行が各項目に正しく分解されること
- F-PRS-02(スキップ): 壊れた行が skippedCount に数えられ、処理が止まらないこと
- F-PRS-03(日時): 日時とタイムゾーンが正しく解釈されること
- F-AGG-02〜05(集計): ステータス区分・上位・時間バケットが仕様どおりに算出されること

テストデータには `sample-logs/access.log`(正常・各種エラー・壊れた行を含む26行)を利用してよい。

## 9. 確定事項(旧・未確定事項)

以下はすべて確定済み。実装時に改めて確認する必要はない。

- 時間帯別グラフ: CSSの横棒グラフで実装する(JSライブラリ不使用)。詳細は 04_ui_design.md。
- パッケージ名: `com.example.loganalyzer` に確定。
- Top N: 既定10、上限100、下限1。範囲外入力は境界に丸める(1未満→1、100超→100、非数値→10)。
- 結果表示: 入力画面と同一ページ(POST /analyze で同テンプレートを返す)に表示する。
- 画面テンプレート/CSSの配置とデザイン(配色・レイアウト・HTML/CSS): 04_ui_design.md に確定。

本プロジェクトに残る未確定事項は無い。設計書(01/02/03/04)と SETUP.md の範囲で全機能を実装できる。

## 10. 実装フェーズへの引き継ぎ

Claude Code は SETUP.md で環境を整えたうえで、本書 第8章のフェーズ順(P1→P6)に、機能ID単位で PLAN→実装→検証 を繰り返す。テスト必須IDは必ずテストを書く。各機能IDの区切りでGitコミットし、キリよくGitHubへpushする。
