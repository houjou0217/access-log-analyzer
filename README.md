# Web Access Log Analyzer

Apache / Nginx 風の Web アクセスログを解析し、アクセス状況・エラー傾向を集計して表示する Spring Boot アプリケーション。インフラ運用で行う「ログからの状況把握」を題材にした、Java 学習・ポートフォリオ用プロジェクト。

> 開発中のプロジェクトです。設計と進め方は `01_requirements.md`、環境構築は `SETUP.md` を参照してください。

## できること(目標)

- アクセスログ(Combined Log Format)をアップロードして解析
- ステータスコード別の件数(2xx/3xx/4xx/5xx)
- アクセス上位のパス・クライアントIP
- 時間帯別のリクエスト数
- エラー行(4xx/5xx)の抽出
- 集計結果を Web 画面と REST API(JSON)で提供

## 技術スタック

- Java 21 (LTS)
- Spring Boot
- Maven
- JUnit 5(パーサ・集計ロジックのテスト)

## 動かし方(環境構築後)

```powershell
./mvnw spring-boot:run
```

起動後、ブラウザで `http://localhost:8080` を開く。詳しい環境構築は `SETUP.md`。

## サンプルデータ

`sample-logs/access.log` にテスト用のアクセスログを用意。正常なリクエスト、404/403/500/503 などのエラー、解析できない壊れた行を1行含んでおり、集計とエラーハンドリングの動作確認に使える。

## 進め方(段階実装)

`01_requirements.md` 第8章のフェーズ P1〜P6 に沿って、小さく作ってはコミット・push する。git の練習(`../git-practice`)で学んだ GitHub Flow をそのまま実践する場でもある。

| フェーズ | 内容 |
|---|---|
| P1 | 雛型作成・起動確認 |
| P2 | ログ1行のパース + テスト |
| P3 | 複数行の集計 + テスト |
| P4 | REST API(JSON) |
| P5 | Web画面(アップロード + 結果表示) |
| P6 | 仕上げ(グラフ・README整備) |

## ポートフォリオとしての見せどころ

- パーサ・集計ロジックのテストコード(品質意識)
- controller / service / model の分離(設計)
- 壊れた行を落とさず処理するエラーハンドリング
- 起動方法・画面キャプチャを README に整備
