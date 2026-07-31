# Webアクセスログ解析ツール 画面設計書

本書は「03_detailed_design.md」の画面仕様(第6章)を、実装(Thymeleaf + CSS)に落とせる具体レベルまで定義する。AIが本書だけで迷わず画面を作れるよう、配色・寸法・HTML構造・CSSを明示する。JavaScriptライブラリは使わず、ThymeleafのサーバーサイドレンダリングとプレーンCSSのみで実装する。

## 1. 全体方針

- 1ページ完結。上から「ヘッダー → 入力エリア → (送信後)結果セクション群」の縦積み。
- 結果は入力と同じページの下に表示する(POST /analyze で同じテンプレートを返し、結果があれば結果部分を描画)。
- レスポンシブは最小限。中央寄せ・最大幅960pxのコンテナに収める。
- グラフはCSSの横棒(div幅%)で表現し、外部ライブラリを使わない。

## 2. 配色(確定)

CSS変数として定義し、全体で使い回す。ダークモード対応は不要(ライトのみ)。

| 用途 | 変数 | 値 |
|---|---|---|
| ページ背景 | `--bg` | `#f5f6f8` |
| カード背景 | `--card` | `#ffffff` |
| 枠線 | `--border` | `#e2e5ea` |
| 主テキスト | `--text` | `#1f2430` |
| 副テキスト | `--muted` | `#6b7280` |
| アクセント(ボタン/バー) | `--accent` | `#2563eb` |
| 2xx 成功 背景/文字 | `--ok-bg` / `--ok-fg` | `#e7f6ec` / `#1a7f37` |
| 3xx 情報 背景/文字 | `--info-bg` / `--info-fg` | `#e6f0fb` / `#1d4ed8` |
| 4xx 警告 背景/文字 | `--warn-bg` / `--warn-fg` | `#fdf1e3` / `#b45309` |
| 5xx 危険 背景/文字 | `--err-bg` / `--err-fg` | `#fdecec` / `#c02626` |

## 3. タイポグラフィ・寸法

- 基本フォント: システムサンセリフ(`system-ui, -apple-system, "Segoe UI", sans-serif`)。
- 等幅フォント(IP・パス・日時・ログ): `"SFMono-Regular", Consolas, "Courier New", monospace`。
- 見出し: セクションタイトル 16px/太字、ページタイトル 22px/太字。本文 13px。数値(サマリ)22px/太字。
- 角丸: カード 12px、小要素(入力・ボタン・バー)6〜8px。
- 余白: セクション間 16px、カード内パディング 14px、コンテナ左右パディング 16px。

## 4. 画面構造(セクション順)

### 4.1 ヘッダー

- ページタイトル「Access Log Analyzer」+ サブテキスト「Apache / Nginx アクセスログを解析して集計します」。
- 下線(2px, `--border`)で区切る。

### 4.2 入力エリア(常時表示)

- カード(`--card`)内に配置。
- ログ本文の `<textarea name="rawLog">`(等幅フォント、行数8以上、幅100%、リサイズ縦のみ)。
- 集計パラメータ:
  - Top N: `<input type="number" name="topN" min="1" max="100" value="10">`(既定10)
  - 時間バケット: `<select name="bucketUnit">` 選択肢「1時間(HOUR)」「10分(TEN_MIN)」「1分(MINUTE)」、既定 HOUR。
- 送信ボタン「解析する」(`--accent` 背景・白文字)。フォームは `method="post" action="/analyze"`。

### 4.3 結果セクション群(送信後のみ表示)

Thymeleaf で `result != null` のときだけ描画する。順序は以下。

1. サマリ(3カード): 総行数 / 解析成功 / 解析不能。成功は緑(`--ok-fg`)、不能は赤(`--err-fg`)の数値色。
2. ステータスコード別: 上段に区分4カード(2xx=緑, 3xx=青, 4xx=橙, 5xx=赤の背景/文字)。下段に個別コードの表(コード昇順、コードと件数)。
3. 上位パス・上位IP: 2カラム(横並び、狭幅時は縦積み)。各カードに表(名称=等幅フォント、件数右寄せ)。
4. 時間帯別リクエスト数: CSSの横棒グラフ。各行「時刻ラベル(等幅・幅44px) + バー(`--accent`, 幅は最大件数を100%とした割合%) + 件数」。時刻昇順。
5. エラー行(4xx/5xx): 表(時刻・IP・状態・パス、すべて等幅フォント)。状態は4xxを橙、5xxを赤の文字色。時刻昇順。

## 5. HTML骨格(Thymeleaf, 参考実装)

`src/main/resources/templates/index.html` に相当。属性名・構造はこの通りに実装する。

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="ja">
<head>
  <meta charset="UTF-8">
  <title>Access Log Analyzer</title>
  <link rel="stylesheet" th:href="@{/css/style.css}">
</head>
<body>
  <div class="container">
    <header class="header">
      <h1>Access Log Analyzer</h1>
      <p class="sub">Apache / Nginx アクセスログを解析して集計します</p>
    </header>

    <form class="card input-area" method="post" action="/analyze">
      <label class="field-label">ログ本文を貼り付け</label>
      <textarea name="rawLog" class="log-input" rows="8" th:text="${rawLog}"></textarea>
      <div class="params">
        <div class="param">
          <label>上位件数 (Top N)</label>
          <input type="number" name="topN" min="1" max="100" th:value="${topN} ?: 10">
        </div>
        <div class="param">
          <label>時間バケット</label>
          <select name="bucketUnit">
            <option value="HOUR">1時間</option>
            <option value="TEN_MIN">10分</option>
            <option value="MINUTE">1分</option>
          </select>
        </div>
        <button type="submit" class="btn-primary">解析する</button>
      </div>
    </form>

    <div th:if="${result != null}" class="results">
      <!-- サマリ -->
      <div class="metric-row">
        <div class="metric"><span class="metric-label">総行数</span><span class="metric-value" th:text="${result.totalLines}">0</span></div>
        <div class="metric"><span class="metric-label">解析成功</span><span class="metric-value ok" th:text="${result.parsedCount}">0</span></div>
        <div class="metric"><span class="metric-label">解析不能</span><span class="metric-value err" th:text="${result.skippedCount}">0</span></div>
      </div>

      <!-- ステータス区分 -->
      <div class="card">
        <h2>ステータスコード別</h2>
        <div class="status-row">
          <div class="status-card ok"><span>2xx 成功</span><b th:text="${result.statusClassCounts['2xx']} ?: 0">0</b></div>
          <div class="status-card info"><span>3xx リダイレクト</span><b th:text="${result.statusClassCounts['3xx']} ?: 0">0</b></div>
          <div class="status-card warn"><span>4xx クライアント</span><b th:text="${result.statusClassCounts['4xx']} ?: 0">0</b></div>
          <div class="status-card err"><span>5xx サーバー</span><b th:text="${result.statusClassCounts['5xx']} ?: 0">0</b></div>
        </div>
        <table class="data-table">
          <tr><th>コード</th><th class="num">件数</th></tr>
          <tr th:each="item : ${result.statusCounts}"><td th:text="${item.key}"></td><td class="num" th:text="${item.count}"></td></tr>
        </table>
      </div>

      <!-- 上位パス / IP -->
      <div class="two-col">
        <div class="card">
          <h2>上位パス</h2>
          <table class="data-table">
            <tr><th>パス</th><th class="num">件数</th></tr>
            <tr th:each="item : ${result.topPaths}"><td class="mono" th:text="${item.key}"></td><td class="num" th:text="${item.count}"></td></tr>
          </table>
        </div>
        <div class="card">
          <h2>上位IP</h2>
          <table class="data-table">
            <tr><th>IP</th><th class="num">件数</th></tr>
            <tr th:each="item : ${result.topIps}"><td class="mono" th:text="${item.key}"></td><td class="num" th:text="${item.count}"></td></tr>
          </table>
        </div>
      </div>

      <!-- 時間帯別 -->
      <div class="card">
        <h2>時間帯別リクエスト数</h2>
        <div class="bar-chart">
          <div class="bar-row" th:each="b : ${result.timeBuckets}">
            <span class="bar-label mono" th:text="${b.bucketStart}"></span>
            <div class="bar-track"><div class="bar-fill" th:style="'width:' + ${b.widthPercent} + '%'"></div></div>
            <span class="bar-count" th:text="${b.count}"></span>
          </div>
        </div>
      </div>

      <!-- エラー行 -->
      <div class="card">
        <h2 class="err-title">エラー行(4xx / 5xx)</h2>
        <table class="data-table mono">
          <tr><th>時刻</th><th>IP</th><th>状態</th><th>パス</th></tr>
          <tr th:each="e : ${result.errorEntries}">
            <td th:text="${#temporals.format(e.dateTime,'HH:mm:ss')}"></td>
            <td th:text="${e.ip}"></td>
            <td th:text="${e.status}"></td>
            <td th:text="${e.path}"></td>
          </tr>
        </table>
      </div>
    </div>
  </div>
</body>
</html>
```

補足: バーの幅は「最大件数を100%」とした割合。実装をシンプルにするため、`TimeBucket` に表示用の `widthPercent`(int, 0〜100)を持たせて集計時に算出してよい(03のモデルに `widthPercent` を追加する)。これは本書で確定する。

## 6. CSS仕様(参考実装)

`src/main/resources/static/css/style.css` に相当。主要ルールを示す。色は第2章、寸法は第3章に従う。

```css
:root{
  --bg:#f5f6f8; --card:#fff; --border:#e2e5ea; --text:#1f2430; --muted:#6b7280; --accent:#2563eb;
  --ok-bg:#e7f6ec; --ok-fg:#1a7f37; --info-bg:#e6f0fb; --info-fg:#1d4ed8;
  --warn-bg:#fdf1e3; --warn-fg:#b45309; --err-bg:#fdecec; --err-fg:#c02626;
}
*{box-sizing:border-box}
body{margin:0; background:var(--bg); color:var(--text);
  font-family:system-ui,-apple-system,"Segoe UI",sans-serif; font-size:13px;}
.container{max-width:960px; margin:0 auto; padding:24px 16px;}
.header{border-bottom:2px solid var(--border); padding-bottom:10px; margin-bottom:16px;}
.header h1{font-size:22px; margin:0;}
.header .sub{color:var(--muted); font-size:12px; margin:4px 0 0;}
.card{background:var(--card); border:1px solid var(--border); border-radius:12px; padding:14px; margin-bottom:16px;}
.card h2{font-size:16px; margin:0 0 10px;}
.mono{font-family:"SFMono-Regular",Consolas,"Courier New",monospace;}
.log-input{width:100%; font-family:"SFMono-Regular",Consolas,monospace; font-size:12px;
  border:1px solid var(--border); border-radius:8px; padding:10px; resize:vertical;}
.params{display:flex; gap:12px; align-items:end; flex-wrap:wrap; margin-top:10px;}
.params label{display:block; font-size:11px; color:var(--muted); margin-bottom:3px;}
.params input,.params select{border:1px solid var(--border); border-radius:6px; padding:6px 10px; font-size:13px;}
.btn-primary{background:var(--accent); color:#fff; border:none; border-radius:6px; padding:8px 20px; font-weight:600; cursor:pointer;}
.metric-row{display:grid; grid-template-columns:repeat(3,1fr); gap:10px; margin-bottom:16px;}
.metric{background:var(--card); border:1px solid var(--border); border-radius:8px; padding:12px;}
.metric-label{display:block; font-size:11px; color:var(--muted);}
.metric-value{font-size:22px; font-weight:700;}
.metric-value.ok{color:var(--ok-fg);} .metric-value.err{color:var(--err-fg);}
.status-row{display:grid; grid-template-columns:repeat(4,1fr); gap:8px; margin-bottom:12px;}
.status-card{border-radius:8px; padding:10px; display:flex; flex-direction:column;}
.status-card span{font-size:11px;} .status-card b{font-size:18px;}
.status-card.ok{background:var(--ok-bg); color:var(--ok-fg);}
.status-card.info{background:var(--info-bg); color:var(--info-fg);}
.status-card.warn{background:var(--warn-bg); color:var(--warn-fg);}
.status-card.err{background:var(--err-bg); color:var(--err-fg);}
.data-table{width:100%; border-collapse:collapse; font-size:12px;}
.data-table th{text-align:left; color:var(--muted); font-weight:500; border-bottom:1px solid var(--border); padding:4px 0;}
.data-table td{padding:4px 0; border-bottom:1px solid var(--border);}
.data-table .num{text-align:right;}
.two-col{display:grid; grid-template-columns:1fr 1fr; gap:12px;}
.bar-chart{display:flex; flex-direction:column; gap:6px;}
.bar-row{display:flex; align-items:center; gap:8px;}
.bar-label{font-size:11px; width:120px; color:var(--muted);}
.bar-track{flex:1; background:var(--bg); border-radius:3px; height:16px;}
.bar-fill{height:16px; background:var(--accent); border-radius:3px;}
.bar-count{font-size:11px; width:36px; text-align:right;}
.err-title{color:var(--err-fg);}
@media(max-width:640px){ .two-col{grid-template-columns:1fr;} .status-row{grid-template-columns:repeat(2,1fr);} }
```

## 7. この設計書で追加確定した点(03へ反映)

- グラフはCSS横棒で実装(JSライブラリ不使用)。
- `TimeBucket` に表示用フィールド `widthPercent`(int, 0〜100)を追加する。集計時に「最大件数を100%」として算出する。
- 結果は入力と同一ページ(POST /analyze)に表示する。
- 画面テンプレートは `templates/index.html`、CSSは `static/css/style.css`。
