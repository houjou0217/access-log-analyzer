package com.example.loganalyzer.controller;

import com.example.loganalyzer.model.AnalyzeRequest;
import com.example.loganalyzer.model.AnalyzeResult;
import com.example.loganalyzer.model.BucketUnit;
import com.example.loganalyzer.service.LogAggregator;
import com.example.loganalyzer.service.LogParser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 画面用のController。
 * 03_detailed_design.md 第6章 / 04_ui_design.md に対応(機能ID: F-UI-01 / F-UI-02)。
 *
 * <p>入力画面と結果表示は同一テンプレート({@code templates/index.html})で行う(03 9章の確定事項)。
 * 結果セクションはテンプレート側で {@code result != null} のときだけ描画される(04 4.3)。</p>
 */
@Controller
public class PageController {

    /** 入力・結果表示に使うテンプレート名 */
    private static final String VIEW_INDEX = "index";

    /** 入力画面のパス(03 6.1) */
    private static final String PATH_INDEX = "/";

    /** 解析実行のパス(03 6.2 / 04 4.2 のフォーム action) */
    private static final String PATH_ANALYZE = "/analyze";

    /** テンプレートが参照するモデル属性名(04 5章の参考実装に合わせる) */
    private static final String ATTR_RESULT = "result";
    private static final String ATTR_RAW_LOG = "rawLog";
    private static final String ATTR_TOP_N = "topN";
    private static final String ATTR_BUCKET_UNIT = "bucketUnit";

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
    public PageController(LogParser logParser, LogAggregator logAggregator) {
        this.logParser = logParser;
        this.logAggregator = logAggregator;
    }

    /**
     * 入力画面を表示する(F-UI-01、03 6.1 / 04 4.2)。
     *
     * <p>モデル属性は渡さない。テンプレート側が topN を {@code ?: 10}、bucketUnit を
     * null のとき HOUR 選択として描画するため、既定値はテンプレートの記述で満たされる。</p>
     *
     * @return テンプレート名
     */
    @GetMapping(PATH_INDEX)
    public String showInputForm() {
        return VIEW_INDEX;
    }

    /**
     * ログを解析し、同一画面に結果を表示する(F-UI-02、03 6.2 / 04 4.3)。
     *
     * <p>入力値はフォームから文字列として届くため、topN は数値として読めない場合に
     * 既定値へフォールバックさせる(03 9章「非数値→10」)。1〜100への丸めは
     * {@link LogAggregator#resolveTopN(Integer)} に委譲し、画面とAPIで挙動を揃える。</p>
     *
     * @param rawLog     貼り付けられたログ本文。未入力の場合は null
     * @param topN       上位件数の入力値(文字列)。未入力・非数値の場合は既定10として扱う
     * @param bucketUnit 時間バケット単位の入力値。不正値は HOUR として扱う
     * @param model      画面へ渡すモデル
     * @return テンプレート名
     */
    @PostMapping(PATH_ANALYZE)
    public String analyze(@RequestParam(required = false) String rawLog,
                          @RequestParam(required = false) String topN,
                          @RequestParam(required = false) String bucketUnit,
                          Model model) {
        Integer requestedTopN = parseTopN(topN);
        AnalyzeRequest request = new AnalyzeRequest(rawLog, requestedTopN, bucketUnit);
        AnalyzeResult result = logAggregator.aggregate(logParser.parse(rawLog), request);

        model.addAttribute(ATTR_RESULT, result);
        // 入力内容をフォームに残す。topN と bucketUnit は実際に使われた値を返して表示と結果を一致させる
        model.addAttribute(ATTR_RAW_LOG, rawLog);
        model.addAttribute(ATTR_TOP_N, logAggregator.resolveTopN(requestedTopN));
        model.addAttribute(ATTR_BUCKET_UNIT, BucketUnit.fromString(bucketUnit).name());
        return VIEW_INDEX;
    }

    /**
     * フォームの topN 入力値を数値に変換する(03 9章)。
     *
     * <p>未入力・数値でない場合は null を返し、既定値(10)の適用を集計器に任せる。
     * 画面側で例外にしないことで、入力ミスでもエラー画面を出さずに解析結果を表示できる。</p>
     *
     * @param topN 入力値(文字列)
     * @return 数値として読めた場合はその値、読めない場合は null
     */
    private Integer parseTopN(String topN) {
        if (topN == null || topN.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(topN.trim());
        } catch (NumberFormatException e) {
            // 数値でない入力は既定値扱いにする(03 9章「非数値→10」)
            return null;
        }
    }
}
