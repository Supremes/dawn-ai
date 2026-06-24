package com.dawn.ai.evaluation.report;

import com.dawn.ai.evaluation.base.EvaluationReport;
import com.dawn.ai.evaluation.judge.JudgeDimension;
import com.dawn.ai.evaluation.judge.JudgeResult;

import j2html.tags.DomContent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;

import static j2html.TagCreator.*;

/**
 * Generates a self-contained HTML evaluation report (all CSS inline, no external dependencies).
 */
public class HtmlReportWriter {

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String COLOR_PRIMARY = "#2563eb";
    private static final String COLOR_PASS = "#16a34a";
    private static final String COLOR_FAIL = "#dc2626";
    private static final String COLOR_WARN = "#ca8a04";
    private static final String COLOR_BG = "#f5f5f5";

    public static void write(EvaluationReport report, String runId, String dimensionId, Path outputDir)
            throws IOException {
        Files.createDirectories(outputDir);
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String html = buildHtml(report, runId, dimensionId, timestamp);
        Path file = outputDir.resolve(runId + "-" + dimensionId + ".html");
        Files.writeString(file, html);
    }

    private static String buildHtml(EvaluationReport report, String runId, String dimensionId, String timestamp) {
        double passRate = report.passRate() * 100;
        double avgScore = report.averageScore();
        int totalCases = report.results().size();

        return document(
                html(
                        head(
                                meta().withCharset("UTF-8"),
                                meta().withName("viewport").withContent("width=device-width, initial-scale=1.0"),
                                title("Dawn AI Evaluation Report - " + runId),
                                style(css())
                        ),
                        body(
                                // Header
                                div(
                                        h1("Dawn AI Evaluation Report"),
                                        p(text("Run: " + runId + " | Dimension: " + dimensionId + " | " + timestamp))
                                ).withClass("header"),

                                div(
                                        // Summary cards
                                        div(
                                                summaryCard("Pass Rate", String.format("%.1f%%", passRate), passRateColor(passRate)),
                                                summaryCard("Average Score", String.format("%.2f", avgScore), passRateColor(passRate)),
                                                summaryCard("Total Cases", String.valueOf(totalCases), COLOR_PRIMARY)
                                        ).withClass("cards"),

                                        // Dimension breakdown
                                        h2("Dimension Breakdown"),
                                        dimensionTable(report),

                                        // Case details
                                        h2("Case Details"),
                                        caseDetails(report),

                                        // Footer
                                        div(
                                                p("Generated at " + timestamp)
                                        ).withClass("footer")
                                ).withClass("container")
                        )
                )
        );
    }

    private static DomContent summaryCard(String label, String value, String color) {
        return div(
                div(label).withClass("card-label"),
                div(value).withClass("card-value").withStyle("color:" + color)
        ).withClass("card");
    }

    private static DomContent dimensionTable(EvaluationReport report) {
        Map<JudgeDimension, DoubleSummaryStatistics> stats = report.dimensionStats();
        Map<JudgeDimension, List<JudgeResult>> byDim = report.byDimension();

        return table(
                thead(
                        tr(
                                th("Dimension"),
                                th("Cases"),
                                th("Avg Score"),
                                th("Min"),
                                th("Max"),
                                th("Pass Rate")
                        )
                ),
                tbody(
                        each(stats.entrySet(), entry -> {
                            DoubleSummaryStatistics s = entry.getValue();
                            List<JudgeResult> dimResults = byDim.getOrDefault(entry.getKey(), List.of());
                            long passedCount = dimResults.stream().filter(JudgeResult::passed).count();
                            double dimPassRate = dimResults.isEmpty() ? 0 : (double) passedCount / dimResults.size() * 100;
                            String rowColor = dimPassRate >= 80 ? "#f0fdf4" : dimPassRate >= 60 ? "#fefce8" : "#fef2f2";
                            return tr(
                                    td(entry.getKey().id()),
                                    td(String.valueOf(s.getCount())),
                                    td(String.format("%.2f", s.getAverage())),
                                    td(String.format("%.1f", s.getMin())),
                                    td(String.format("%.1f", s.getMax())),
                                    td(String.format("%.1f%%", dimPassRate))
                            ).withStyle("background-color:" + rowColor);
                        })
                )
        );
    }

    private static DomContent caseDetails(EvaluationReport report) {
        List<JudgeResult> results = report.results();
        List<DomContent> items = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            JudgeResult result = results.get(i);
            boolean passed = result.passed();
            String badgeColor = passed ? COLOR_PASS : COLOR_FAIL;
            String badgeText = passed ? "PASS" : "FAIL";
            items.add(
                    details(
                            summary(
                                    span("Case #" + (i + 1)).withStyle("font-weight:600"),
                                    text(" "),
                                    span("[" + result.dimension().id() + "]").withStyle("color:#6b7280;margin-left:8px"),
                                    text(" "),
                                    span(String.format("%.1f", result.score())).withClass("score-badge").withStyle(
                                            "background-color:" + badgeColor),
                                    text(" "),
                                    span(badgeText).withClass("status-badge").withStyle(
                                            "background-color:" + badgeColor)
                            ),
                            div(
                                    div(
                                            span("Score: ").withStyle("font-weight:600"),
                                            text(String.format("%.2f", result.score()))
                                    ).withStyle("margin-bottom:8px"),
                                    div(
                                            span("Status: ").withStyle("font-weight:600"),
                                            span(badgeText).withStyle("color:" + badgeColor + ";font-weight:600")
                                    ).withStyle("margin-bottom:8px"),
                                    div(
                                            span("Reasoning:").withStyle("font-weight:600;display:block;margin-bottom:4px"),
                                            pre(result.reasoning() != null ? result.reasoning() : "(no reasoning)")
                                                    .withClass("reasoning")
                                    )
                            ).withClass("case-body")
                    ).withClass("case-detail")
            );
        }
        return div(items.toArray(new DomContent[0]));
    }

    private static String passRateColor(double passRate) {
        if (passRate >= 80) return COLOR_PASS;
        if (passRate >= 60) return COLOR_WARN;
        return COLOR_FAIL;
    }

    private static String css() {
        return """
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    background-color: %s;
                    color: #1f2937;
                    line-height: 1.6;
                }
                .header {
                    background: linear-gradient(135deg, %s, #1d4ed8);
                    color: white;
                    padding: 32px 40px;
                }
                .header h1 { font-size: 28px; margin-bottom: 4px; }
                .header p { opacity: 0.85; font-size: 14px; }
                .container { max-width: 960px; margin: 0 auto; padding: 32px 24px; }
                .cards { display: flex; gap: 16px; margin-bottom: 32px; }
                .card {
                    flex: 1;
                    background: white;
                    border-radius: 12px;
                    padding: 24px;
                    box-shadow: 0 1px 3px rgba(0,0,0,0.1);
                    text-align: center;
                }
                .card-label { font-size: 13px; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 8px; }
                .card-value { font-size: 32px; font-weight: 700; }
                h2 { font-size: 20px; margin-bottom: 16px; color: #111827; }
                table { width: 100%%; border-collapse: collapse; margin-bottom: 32px; background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                thead { background-color: #f9fafb; }
                th { padding: 12px 16px; text-align: left; font-size: 13px; font-weight: 600; color: #374151; border-bottom: 2px solid #e5e7eb; }
                td { padding: 12px 16px; font-size: 14px; border-bottom: 1px solid #f3f4f6; }
                .case-detail { background: white; border-radius: 8px; margin-bottom: 12px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); overflow: hidden; }
                .case-detail summary { padding: 16px 20px; cursor: pointer; display: flex; align-items: center; gap: 4px; font-size: 14px; }
                .case-detail summary:hover { background-color: #f9fafb; }
                .score-badge, .status-badge {
                    display: inline-block;
                    color: white;
                    font-size: 12px;
                    font-weight: 600;
                    padding: 2px 8px;
                    border-radius: 9999px;
                }
                .case-body { padding: 16px 20px; border-top: 1px solid #f3f4f6; font-size: 14px; }
                .reasoning {
                    background-color: #f9fafb;
                    border: 1px solid #e5e7eb;
                    border-radius: 6px;
                    padding: 12px 16px;
                    font-size: 13px;
                    white-space: pre-wrap;
                    word-wrap: break-word;
                    font-family: 'SF Mono', 'Fira Code', monospace;
                    max-height: 300px;
                    overflow-y: auto;
                }
                .footer { margin-top: 40px; padding-top: 16px; border-top: 1px solid #e5e7eb; text-align: center; color: #9ca3af; font-size: 13px; }
                """.formatted(COLOR_BG, COLOR_PRIMARY);
    }
}
