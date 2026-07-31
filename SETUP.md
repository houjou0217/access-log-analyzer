# 環境構築ガイド(Windows)

Webアクセスログ解析ツールを作るための開発環境を、Windowsでゼロから整える手順。上から順に進める。

## 全体像

必要なものは3つ。

1. JDK 21(Javaの実行・開発キット)
2. IntelliJ IDEA Community(無料のIDE。Maven同梱)
3. プロジェクトの雛型(Spring Initializr で生成)

Maven は IntelliJ に同梱されているので、単体インストールは基本不要。

## 手順1: JDK 21 をインストール

Java 21 は LTS(長期サポート)版で安定している。無料で使える配布元として Eclipse Temurin(Adoptium)を使う。

1. ブラウザで「Adoptium Temurin 21」を検索し、公式サイト(adoptium.net)を開く。
2. OS: Windows、Architecture: x64、Version: 21(LTS)を選び、`.msi` インストーラをダウンロード。
3. インストーラを実行。途中の「Set JAVA_HOME variable」「Add to PATH」のオプションがあれば有効にする(PATHが通り、後が楽になる)。

### 確認

PowerShell を開いて:

```powershell
java -version
```

`openjdk version "21..."` のように表示されればOK。表示されない場合は、PowerShellを開き直す(PATHの反映のため)。

## 手順2: IntelliJ IDEA Community をインストール

1. ブラウザで「IntelliJ IDEA Community download」を検索し、JetBrains公式を開く。
2. 「Community Edition」(無料版)の Windows 版インストーラをダウンロードして実行。
3. 初回起動時はデフォルト設定のままでよい。

IntelliJ には Maven が同梱されているので、別途 Maven を入れなくてよい。

## 手順3: プロジェクトの雛型を作る(Spring Initializr)

Spring Boot プロジェクトは「Spring Initializr」で雛型を自動生成できる。IntelliJ から直接作る方法と、Webサイトで作る方法がある。ここでは分かりやすいWeb版を使う。

1. ブラウザで `https://start.spring.io` を開く。
2. 次のように設定する。
   - Project: **Maven**
   - Language: **Java**
   - Spring Boot: 表示される安定版(3.x系)のデフォルトでよい
   - Project Metadata:
     - Group: `com.example`(任意)
     - Artifact: `loganalyzer`
     - Name: `loganalyzer`
     - Packaging: **Jar**
     - Java: **21**
   - Dependencies(右側の「Add Dependencies」):
     - **Spring Web**(REST API と Web に必須)
     - **Thymeleaf**(簡易画面用。静的HTMLで作るなら後で外してもよい)
3. 「GENERATE」を押すと zip がダウンロードされる。
4. zip を解凍し、中身を **このフォルダ(log-analyzer)の中**に置く(または好きな場所に置く。OneDrive外を選ぶなら移動する)。

### IntelliJ で開く

1. IntelliJ を起動 →「Open」→ 解凍したプロジェクトフォルダ(`pom.xml` がある階層)を選ぶ。
2. 初回は依存ライブラリのダウンロードで少し時間がかかる。右下の進捗が消えるまで待つ。

## 手順4: 起動確認(P1のゴール)

1. IntelliJ で `src/main/java/.../LoganalyzerApplication.java`(名前は生成時のArtifactによる)を開く。
2. `main` メソッド左の緑の再生ボタン、または上部の Run ボタンで起動。
3. コンソールに `Started LoganalyzerApplication ...` と出れば起動成功。
4. ブラウザで `http://localhost:8080` を開く。まだ画面は作っていないのでエラーページ(Whitelabel Error Page)が出るが、これは「サーバーは動いているがそのURLの中身がまだ無い」という意味で、起動確認としてはOK。

コマンドで起動したい場合は、`pom.xml` のあるフォルダで:

```powershell
./mvnw spring-boot:run
```

(`mvnw` は雛型に同梱される Maven ラッパー。Maven未インストールでも動く)

## つまずきポイント

- `java -version` が出ない → PowerShellを開き直す。それでもダメならJDKの再インストール時に「Add to PATH」を有効にする。
- IntelliJ が Java 21 を認識しない → File → Project Structure → Project SDK で 21 を選ぶ。
- ポート8080が使用中 → 既に何か動いている。`application.properties` に `server.port=8081` を追記してポートを変える。
- OneDrive同期下でビルドが重い → 後々 `target/`(ビルド成果物)が作られる。`.gitignore` で除外し、必要ならプロジェクトをOneDrive外に移す。

## 次のステップ

環境ができたら `01_requirements.md` の第8章「段階実装」の P2(ログ1行のパース+テスト)から着手する。まずは1行を正しく分解するロジックとテストを書くところから。
