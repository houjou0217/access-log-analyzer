package com.example.loganalyzer.controller;

import com.example.loganalyzer.model.AnalyzeRequest;
import com.example.loganalyzer.model.AnalyzeResult;
import com.example.loganalyzer.service.LogAggregator;
import com.example.loganalyzer.service.LogParser;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ログ解析の REST API。
 * 03_detailed_design.md 第5章 に対応(機能ID: F-API-01 / F-API-02)。
 *
 * <p>解析の流れは LogParser → LogAggregator。結果の AnalyzeResult をそのまま JSON で返す。</p>
 *
 * <p>設計 5.1 のとおり常に HTTP 200 を返す。解析できない行があってもエラーにはせず、
 * 結果の skippedCount で表現する。</p>
 */
@RestController
public class ApiController {

    /** 解析APIのパス(03 5.1) */
    private static final String ANALYZE_PATH = "/api/analyze";

    /** ログのパーサ */
    private final LogParser logParser;

    /** 集計器 */
    private final LogAggregator logAggregator;

    /**
     * コンストラクタインジェクション。
     *
     * @param logParser     ログのパーサ
     * @param logAggregator 集計器
     */
    public ApiController(LogParser logParser, LogAggregator logAggregator) {
        this.logParser = logParser;
        this.logAggregator = logAggregator;
    }

    /**
     * ログ本文を解析して集計結果を返す(F-API-01)。
     *
     * <p>topN の既定値10・範囲(1〜100)への丸めと、bucketUnit 不正値の HOUR への
     * フォールバックは LogAggregator 側で行う(F-API-02、03 5.1 / 9章)。</p>
     *
     * @param request 解析要求(rawLog / topN / bucketUnit)。ボディ無しの場合は null
     * @return 集計結果。入力が空でも totalLines=0 の結果を返す(03 5.1)
     */
    @PostMapping(ANALYZE_PATH)
    public AnalyzeResult analyze(@RequestBody(required = false) AnalyzeRequest request) {
        String rawLog = request == null ? null : request.rawLog();
        LogParser.ParseResult parseResult = logParser.parse(rawLog);
        return logAggregator.aggregate(parseResult, request);
    }

    /**
     * リクエストボディが JSON として読み取れない場合の処理(03 5.1「常に200を返す」)。
     *
     * <p>例: topN に数値でない値が入っている、JSON が壊れている。
     * 設計上このAPIはエラーステータスを返さないため、空の解析結果(totalLines=0)を200で返す。
     * 黙って握りつぶすのではなく「入力が無かった場合と同じ結果」に寄せる方針。</p>
     *
     * @param exception 発生した例外(内容はレスポンスには含めない)
     * @return 空の集計結果
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AnalyzeResult handleUnreadableRequestBody(HttpMessageNotReadableException exception) {
        return logAggregator.aggregate(logParser.parse(null), null);
    }
}
