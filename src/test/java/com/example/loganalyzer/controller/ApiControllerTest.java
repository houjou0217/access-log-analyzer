package com.example.loganalyzer.controller;

import com.example.loganalyzer.model.AnalyzeRequest;
import com.example.loganalyzer.service.LogAggregator;
import com.example.loganalyzer.service.LogParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ApiController のテスト(MockMvc)。
 * 03_detailed_design.md 第5章 / F-API-01・F-API-02 に対応。
 */
@WebMvcTest(ApiController.class)
@Import({LogParser.class, LogAggregator.class})
class ApiControllerTest {

    /** 解析APIのパス */
    private static final String ANALYZE_PATH = "/api/analyze";

    /** 正常なログ行(10時台・200) */
    private static final String VALID_LINE_1 =
            "192.168.0.10 - - [30/Jul/2026:10:15:32 +0900] "
            + "\"GET /index.html HTTP/1.1\" 200 1043 \"https://example.com/\" \"Mozilla/5.0\"";

    /** 正常なログ行(同じ10時台・404。エラー行の抽出確認用) */
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

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 解析APIへ POST する。
     *
     * @param request 送信する解析要求
     * @return 実行結果
     * @throws Exception リクエスト実行に失敗した場合
     */
    private ResultActions postAnalyze(AnalyzeRequest request) throws Exception {
        return mockMvc.perform(post(ANALYZE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    /**
     * 生のJSON文字列を解析APIへ POST する(壊れたJSONの確認用)。
     *
     * @param json 送信するJSON文字列
     * @return 実行結果
     * @throws Exception リクエスト実行に失敗した場合
     */
    private ResultActions postRawJson(String json) throws Exception {
        return mockMvc.perform(post(ANALYZE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Nested
    @DisplayName("F-API-01: 解析結果のJSON返却")
    class AnalyzeTest {

        @Test
        @DisplayName("200で AnalyzeResult のJSONが返る")
        void returnsAnalyzeResultAsJson() throws Exception {
            postAnalyze(new AnalyzeRequest(RAW_LOG, 10, "HOUR"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.totalLines").value(3))
                    .andExpect(jsonPath("$.parsedCount").value(2))
                    .andExpect(jsonPath("$.skippedCount").value(1));
        }

        @Test
        @DisplayName("集計内容(ステータス区分・上位・時間帯・エラー行)が含まれる")
        void includesAllAggregations() throws Exception {
            postAnalyze(new AnalyzeRequest(RAW_LOG, 10, "HOUR"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.statusClassCounts['2xx']").value(1))
                    .andExpect(jsonPath("$.statusClassCounts['4xx']").value(1))
                    .andExpect(jsonPath("$.statusCounts.length()").value(2))
                    .andExpect(jsonPath("$.topPaths.length()").value(2))
                    .andExpect(jsonPath("$.topIps.length()").value(2))
                    // 同じ10時台なので1バケットにまとまる
                    .andExpect(jsonPath("$.timeBuckets.length()").value(1))
                    .andExpect(jsonPath("$.timeBuckets[0].bucketStart").value("2026-07-30 10:00"))
                    .andExpect(jsonPath("$.timeBuckets[0].count").value(2))
                    .andExpect(jsonPath("$.timeBuckets[0].widthPercent").value(100))
                    // 404 の1件だけがエラー行
                    .andExpect(jsonPath("$.errorEntries.length()").value(1))
                    .andExpect(jsonPath("$.errorEntries[0].status").value(404))
                    .andExpect(jsonPath("$.errorEntries[0].path").value("/login"));
        }

        @Test
        @DisplayName("全行が解析不能でも200で返る(03 5.1)")
        void returnsOkWhenAllLinesAreBroken() throws Exception {
            postAnalyze(new AnalyzeRequest(BROKEN_LINE + "\n" + BROKEN_LINE, 10, "HOUR"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalLines").value(2))
                    .andExpect(jsonPath("$.parsedCount").value(0))
                    .andExpect(jsonPath("$.skippedCount").value(2))
                    .andExpect(jsonPath("$.topPaths.length()").value(0))
                    .andExpect(jsonPath("$.timeBuckets.length()").value(0));
        }

        @Test
        @DisplayName("rawLog が空・null でも totalLines=0 の結果を200で返す(03 5.1)")
        void returnsEmptyResultForEmptyInput() throws Exception {
            for (String rawLog : new String[]{null, "", "   "}) {
                postAnalyze(new AnalyzeRequest(rawLog, 10, "HOUR"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.totalLines").value(0))
                        .andExpect(jsonPath("$.parsedCount").value(0))
                        .andExpect(jsonPath("$.skippedCount").value(0));
            }
        }
    }

    @Nested
    @DisplayName("F-API-02: topN / bucketUnit の処理")
    class ParameterTest {

        @Test
        @DisplayName("topN 省略時は既定10として扱う(03 5.1)")
        void usesDefaultTopN() throws Exception {
            postAnalyze(new AnalyzeRequest(RAW_LOG, null, "HOUR"))
                    .andExpect(status().isOk())
                    // 既定10なので、2種類のパスはすべて入る
                    .andExpect(jsonPath("$.topPaths.length()").value(2));
        }

        @Test
        @DisplayName("topN が下限未満なら1件に丸められる(03 9章)")
        void clampsTopNToLowerBound() throws Exception {
            postAnalyze(new AnalyzeRequest(RAW_LOG, 0, "HOUR"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.topPaths.length()").value(1));
        }

        @Test
        @DisplayName("topN が上限超なら100に丸められる(03 9章)")
        void clampsTopNToUpperBound() throws Exception {
            postAnalyze(new AnalyzeRequest(RAW_LOG, 999, "HOUR"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.topPaths.length()").value(2));
        }

        @Test
        @DisplayName("bucketUnit 省略・不正値は HOUR にフォールバックする(03 5.1)")
        void fallsBackToHour() throws Exception {
            for (String unit : new String[]{null, "DAY", "", "hour_x"}) {
                postAnalyze(new AnalyzeRequest(RAW_LOG, 10, unit))
                        .andExpect(status().isOk())
                        // HOUR なら10時台の1バケットにまとまる
                        .andExpect(jsonPath("$.timeBuckets.length()").value(1))
                        .andExpect(jsonPath("$.timeBuckets[0].bucketStart").value("2026-07-30 10:00"));
            }
        }

        @Test
        @DisplayName("bucketUnit に MINUTE を指定すると分単位で分かれる")
        void bucketsByMinute() throws Exception {
            postAnalyze(new AnalyzeRequest(RAW_LOG, 10, "MINUTE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.timeBuckets.length()").value(2))
                    .andExpect(jsonPath("$.timeBuckets[0].bucketStart").value("2026-07-30 10:15"))
                    .andExpect(jsonPath("$.timeBuckets[1].bucketStart").value("2026-07-30 10:45"));
        }
    }

    @Nested
    @DisplayName("常に200を返す(03 5.1)")
    class AlwaysOkTest {

        @Test
        @DisplayName("リクエストボディが無くても200で空の結果を返す")
        void returnsOkWithoutBody() throws Exception {
            mockMvc.perform(post(ANALYZE_PATH).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalLines").value(0));
        }

        @Test
        @DisplayName("JSONが壊れていても200で空の結果を返す")
        void returnsOkForBrokenJson() throws Exception {
            postRawJson("{\"rawLog\": ")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalLines").value(0));
        }

        @Test
        @DisplayName("topN が数値でなくても200で空の結果を返す")
        void returnsOkForNonNumericTopN() throws Exception {
            postRawJson("{\"rawLog\":\"\",\"topN\":\"abc\",\"bucketUnit\":\"HOUR\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalLines").value(0));
        }
    }
}
