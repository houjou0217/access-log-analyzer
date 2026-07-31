package com.example.loganalyzer.controller;

import com.example.loganalyzer.model.AnalyzeResult;
import com.example.loganalyzer.service.LogAggregator;
import com.example.loganalyzer.service.LogParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * PageController のテスト(MockMvc)。
 * 03_detailed_design.md 第6章 / 04_ui_design.md / F-UI-01・F-UI-02 に対応。
 *
 * <p>実際に Thymeleaf テンプレートを描画して検証するため、テンプレート側の記述
 * (例: {@code #temporals.format})が実行時に動作することもここで確認できる。</p>
 */
@WebMvcTest(PageController.class)
@Import({LogParser.class, LogAggregator.class})
class PageControllerTest {

    /** 正常なログ行(10時台・200) */
    private static final String VALID_LINE_1 =
            "192.168.0.10 - - [30/Jul/2026:10:15:32 +0900] "
            + "\"GET /index.html HTTP/1.1\" 200 1043 \"https://example.com/\" \"Mozilla/5.0\"";

    /** 正常なログ行(同じ10時台・404。エラー行一覧の確認用) */
    private static final String VALID_LINE_2 =
            "203.0.113.5 - - [30/Jul/2026:10:45:00 +0900] "
            + "\"GET /login HTTP/1.1\" 404 209 \"-\" \"curl/8.4.0\"";

    /** 解析できない行 */
    private static final String BROKEN_LINE = "this-is-a-broken-line";

    /** 正常2行 + 壊れた1行 */
    private static final String RAW_LOG =
            String.join("\n", VALID_LINE_1, VALID_LINE_2, BROKEN_LINE);

    @Autowired
    private MockMvc mockMvc;

    /**
     * 解析フォームを POST する。
     *
     * @param topN       Top N の入力値(文字列。null なら送信しない)
     * @param bucketUnit 時間バケットの入力値(null なら送信しない)
     * @return 実行結果
     * @throws Exception リクエスト実行に失敗した場合
     */
    private ResultActions postAnalyze(String topN, String bucketUnit) throws Exception {
        var builder = post("/analyze").param("rawLog", RAW_LOG);
        if (topN != null) {
            builder = builder.param("topN", topN);
        }
        if (bucketUnit != null) {
            builder = builder.param("bucketUnit", bucketUnit);
        }
        return mockMvc.perform(builder);
    }

    /**
     * モデルに入った topN の値を取り出す。
     *
     * @param result MockMvc の実行結果
     * @return topN の値
     */
    private static Object modelTopN(MvcResult result) {
        return result.getModelAndView().getModel().get("topN");
    }

    @Nested
    @DisplayName("F-UI-01: 入力画面(GET /)")
    class InputFormTest {

        @Test
        @DisplayName("200で index テンプレートが返り、結果セクションは描画されない")
        void showsInputForm() throws Exception {
            mockMvc.perform(get("/"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("index"))
                    .andExpect(model().attributeDoesNotExist("result"))
                    // 入力フォームの要素は表示される(04 4.2)
                    .andExpect(content().string(containsString("name=\"rawLog\"")))
                    .andExpect(content().string(containsString("name=\"topN\"")))
                    .andExpect(content().string(containsString("name=\"bucketUnit\"")))
                    .andExpect(content().string(containsString("解析する")))
                    // 結果セクションは出さない
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            containsString("時間帯別リクエスト数"))));
        }

        @Test
        @DisplayName("Top N の初期値は10、時間バケットの初期選択は HOUR(04 4.2)")
        void showsDefaultParameters() throws Exception {
            mockMvc.perform(get("/"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("value=\"10\"")))
                    .andExpect(content().string(containsString(
                            "<option value=\"HOUR\" selected=\"selected\">1時間</option>")));
        }
    }

    @Nested
    @DisplayName("F-UI-02: 結果表示(POST /analyze)")
    class AnalyzeTest {

        @Test
        @DisplayName("同一テンプレートに結果を渡す(result / rawLog / topN / bucketUnit)")
        void passesModelAttributes() throws Exception {
            postAnalyze("10", "HOUR")
                    .andExpect(status().isOk())
                    .andExpect(view().name("index"))
                    .andExpect(model().attributeExists("result", "rawLog", "topN", "bucketUnit"))
                    .andExpect(model().attribute("rawLog", RAW_LOG))
                    .andExpect(model().attribute("topN", 10))
                    .andExpect(model().attribute("bucketUnit", "HOUR"));
        }

        @Test
        @DisplayName("サマリ(総行数・解析成功・解析不能)が描画される")
        void rendersSummary() throws Exception {
            MvcResult result = postAnalyze("10", "HOUR")
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("総行数")))
                    .andExpect(content().string(containsString("解析成功")))
                    .andExpect(content().string(containsString("解析不能")))
                    .andReturn();

            AnalyzeResult analyzeResult =
                    (AnalyzeResult) result.getModelAndView().getModel().get("result");
            assertEquals(3, analyzeResult.totalLines());
            assertEquals(2, analyzeResult.parsedCount());
            assertEquals(1, analyzeResult.skippedCount());
        }

        @Test
        @DisplayName("ステータス区分・個別コード・上位パス・上位IPが描画される")
        void rendersTables() throws Exception {
            postAnalyze("10", "HOUR")
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("ステータスコード別")))
                    .andExpect(content().string(containsString("2xx 成功")))
                    .andExpect(content().string(containsString("上位パス")))
                    .andExpect(content().string(containsString("上位IP")))
                    .andExpect(content().string(containsString("/index.html")))
                    .andExpect(content().string(containsString("192.168.0.10")));
        }

        @Test
        @DisplayName("入力したログ本文がテキストエリアに残る")
        void keepsRawLogInTextArea() throws Exception {
            postAnalyze("10", "HOUR")
                    .andExpect(status().isOk())
                    // ログ行の一部がテキストエリアに描画されている
                    .andExpect(content().string(containsString("/index.html HTTP/1.1")));
        }

        @Test
        @DisplayName("ログが空でも例外にならず、結果セクションを描画する")
        void handlesEmptyRawLog() throws Exception {
            mockMvc.perform(post("/analyze").param("rawLog", ""))
                    .andExpect(status().isOk())
                    .andExpect(view().name("index"))
                    .andExpect(model().attributeExists("result"));
        }

        @Test
        @DisplayName("パラメータが全く送られなくても例外にならない")
        void handlesMissingParameters() throws Exception {
            mockMvc.perform(post("/analyze"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("topN", 10))
                    .andExpect(model().attribute("bucketUnit", "HOUR"));
        }
    }

    @Nested
    @DisplayName("F-UI-03: 時間帯別グラフ(CSS横棒)")
    class BarChartTest {

        @Test
        @DisplayName("バーの幅が widthPercent で描画される(最大件数=100%)")
        void rendersBarWidth() throws Exception {
            postAnalyze("10", "HOUR")
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("時間帯別リクエスト数")))
                    .andExpect(content().string(containsString("2026-07-30 10:00")))
                    // 2件が同じ10時台にまとまり、最大件数なので100%
                    .andExpect(content().string(containsString("width:100%")))
                    .andExpect(content().string(containsString("class=\"bar-fill\"")));
        }

        @Test
        @DisplayName("bucketUnit に MINUTE を選ぶと分単位のラベルになる")
        void rendersMinuteBuckets() throws Exception {
            postAnalyze("10", "MINUTE")
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("bucketUnit", "MINUTE"))
                    .andExpect(content().string(containsString("2026-07-30 10:15")))
                    .andExpect(content().string(containsString("2026-07-30 10:45")));
        }
    }

    @Nested
    @DisplayName("F-UI-04: エラー行一覧")
    class ErrorEntriesTest {

        @Test
        @DisplayName("4xx/5xx の行が時刻・IP・状態・パスで描画される")
        void rendersErrorRows() throws Exception {
            postAnalyze("10", "HOUR")
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("エラー行(4xx / 5xx)")))
                    // #temporals.format で時刻が HH:mm:ss に整形される(04 5章)
                    .andExpect(content().string(containsString("10:45:00")))
                    .andExpect(content().string(containsString("203.0.113.5")))
                    .andExpect(content().string(containsString("404")))
                    .andExpect(content().string(containsString("/login")));
        }
    }

    @Nested
    @DisplayName("入力パラメータの丸め(03 9章)")
    class ParameterTest {

        @Test
        @DisplayName("Top N が範囲外なら境界に丸めた値をフォームに返す")
        void clampsTopN() throws Exception {
            assertEquals(100, modelTopN(postAnalyze("999", "HOUR").andReturn()));
            assertEquals(1, modelTopN(postAnalyze("0", "HOUR").andReturn()));
            assertEquals(1, modelTopN(postAnalyze("-5", "HOUR").andReturn()));
        }

        @Test
        @DisplayName("Top N が非数値・空なら既定10として扱う")
        void fallsBackToDefaultTopN() throws Exception {
            assertEquals(10, modelTopN(postAnalyze("abc", "HOUR").andReturn()));
            assertEquals(10, modelTopN(postAnalyze("", "HOUR").andReturn()));
            assertEquals(10, modelTopN(postAnalyze("  ", "HOUR").andReturn()));
        }

        @Test
        @DisplayName("時間バケットが不正なら HOUR として扱う")
        void fallsBackToHour() throws Exception {
            postAnalyze("10", "DAY")
                    .andExpect(model().attribute("bucketUnit", "HOUR"));
            postAnalyze("10", "")
                    .andExpect(model().attribute("bucketUnit", "HOUR"));
        }
    }
}
