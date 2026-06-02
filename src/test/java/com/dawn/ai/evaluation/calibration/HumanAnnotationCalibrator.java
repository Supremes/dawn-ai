package com.dawn.ai.evaluation.calibration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 人工标注校准工具。
 *
 * 使用流程：
 * 1. 运行评估测试，生成 judge-results.json
 * 2. 运行 exportForAnnotation() 导出待标注文件
 * 3. 人工在 annotation-template.json 中填写 humanScore
 * 4. 运行 calibrate() 计算一致性指标
 *
 * 输出指标：
 * - Accuracy: 人机评分一致的比例
 * - Cohen's Kappa: 考虑偶然一致的校正系数
 * - Mean Absolute Error: 评分差异的平均绝对值
 */
public class HumanAnnotationCalibrator {

    private static final Logger log = LoggerFactory.getLogger(HumanAnnotationCalibrator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 导出待标注模板
     */
    public static void exportForAnnotation(List<AnnotationEntry> entries, String outputPath) throws IOException {
        File file = new File(outputPath);
        file.getParentFile().mkdirs();
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, entries);
        log.info("[Calibration] exported {} entries to {}", entries.size(), outputPath);
    }

    /**
     * 计算人机一致性
     */
    public static CalibrationResult calibrate(List<AnnotationEntry> entries) {
        List<AnnotationEntry> annotated = entries.stream()
                .filter(e -> e.humanScore != null)
                .toList();

        if (annotated.isEmpty()) {
            log.warn("[Calibration] no human annotations found");
            return new CalibrationResult(0, 0, 0, 0, List.of());
        }

        int total = annotated.size();
        int agreements = 0;
        double totalAbsError = 0;

        // For Cohen's Kappa: build confusion matrix for binary pass/fail
        int bothPass = 0, bothFail = 0, judgePassHumanFail = 0, judgeFailHumanPass = 0;

        List<Discrepancy> discrepancies = new ArrayList<>();

        for (AnnotationEntry entry : annotated) {
            boolean judgePass = entry.judgeScore >= 3.0;
            boolean humanPass = entry.humanScore >= 3.0;

            if (judgePass == humanPass) agreements++;
            totalAbsError += Math.abs(entry.judgeScore - entry.humanScore);

            if (judgePass && humanPass) bothPass++;
            else if (!judgePass && !humanPass) bothFail++;
            else if (judgePass && !humanPass) judgePassHumanFail++;
            else judgeFailHumanPass++;

            if (Math.abs(entry.judgeScore - entry.humanScore) >= 1.5) {
                discrepancies.add(new Discrepancy(entry.caseId, entry.dimension, entry.judgeScore, entry.humanScore, entry.query));
            }
        }

        double accuracy = (double) agreements / total;
        double mae = totalAbsError / total;

        // Cohen's Kappa
        double po = accuracy; // observed agreement
        double pJudgePass = (double) (bothPass + judgePassHumanFail) / total;
        double pHumanPass = (double) (bothPass + judgeFailHumanPass) / total;
        double pe = pJudgePass * pHumanPass + (1 - pJudgePass) * (1 - pHumanPass); // expected agreement
        double kappa = pe == 1.0 ? 1.0 : (po - pe) / (1 - pe);

        log.info("[Calibration] results: accuracy={:.3f}, kappa={:.3f}, mae={:.3f}, discrepancies={}",
                accuracy, kappa, mae, discrepancies.size());

        return new CalibrationResult(accuracy, kappa, mae, total, discrepancies);
    }

    public record AnnotationEntry(
            String caseId,
            String dimension,
            String query,
            double judgeScore,
            String judgeReasoning,
            Double humanScore,      // null = not yet annotated
            String humanComment     // optional
    ) {}

    public record Discrepancy(String caseId, String dimension, double judgeScore, double humanScore, String query) {}

    public record CalibrationResult(double accuracy, double kappa, double mae, int sampleSize, List<Discrepancy> discrepancies) {
        public String summary() {
            return String.format(
                    "=== Judge 校准报告 ===\n样本数: %d\n准确率: %.1f%%\nCohen's Kappa: %.3f\nMAE: %.3f\n显著偏差: %d 条",
                    sampleSize, accuracy * 100, kappa, mae, discrepancies.size());
        }
    }
}
