package com.dawn.ai.evaluation.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 评估运行成本追踪器。
 * 统计 LLM API 调用次数、token 消耗，估算成本。
 *
 * 使用方式：
 * 1. 在评估开始前调用 reset()
 * 2. 每次 LLM 调用后调用 recordCall()
 * 3. 评估结束后调用 summary() 获取报告
 */
public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    // 默认定价（USD per 1K tokens），可通过构造函数覆盖
    private static final double DEFAULT_INPUT_PRICE_PER_1K = 0.002;
    private static final double DEFAULT_OUTPUT_PRICE_PER_1K = 0.006;

    private final AtomicLong totalCalls = new AtomicLong(0);
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final Map<String, CallRecord> callsByModel = new ConcurrentHashMap<>();
    private final List<CallRecord> allCalls = new ArrayList<>();
    private final double inputPricePer1K;
    private final double outputPricePer1K;

    public CostTracker() {
        this(DEFAULT_INPUT_PRICE_PER_1K, DEFAULT_OUTPUT_PRICE_PER_1K);
    }

    public CostTracker(double inputPricePer1K, double outputPricePer1K) {
        this.inputPricePer1K = inputPricePer1K;
        this.outputPricePer1K = outputPricePer1K;
    }

    public void reset() {
        totalCalls.set(0);
        totalInputTokens.set(0);
        totalOutputTokens.set(0);
        callsByModel.clear();
        synchronized (allCalls) {
            allCalls.clear();
        }
        log.info("[CostTracker] reset");
    }

    /**
     * 记录一次 LLM 调用
     */
    public void recordCall(String model, long inputTokens, long outputTokens) {
        totalCalls.incrementAndGet();
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);

        CallRecord record = new CallRecord(model, inputTokens, outputTokens);
        synchronized (allCalls) {
            allCalls.add(record);
        }

        callsByModel.merge(model, record, (existing, neu) ->
                new CallRecord(model, existing.inputTokens + neu.inputTokens, existing.outputTokens + neu.outputTokens));
    }

    /**
     * 生成成本报告
     */
    public CostReport summary() {
        long calls = totalCalls.get();
        long inputTokens = totalInputTokens.get();
        long outputTokens = totalOutputTokens.get();
        long totalTokens = inputTokens + outputTokens;

        double inputCost = (inputTokens / 1000.0) * inputPricePer1K;
        double outputCost = (outputTokens / 1000.0) * outputPricePer1K;
        double totalCost = inputCost + outputCost;

        Map<String, CallRecord> modelBreakdown = Map.copyOf(callsByModel);
        List<CallRecord> callLog;
        synchronized (allCalls) {
            callLog = List.copyOf(allCalls);
        }

        CostReport report = new CostReport(calls, inputTokens, outputTokens, totalTokens, inputCost, outputCost, totalCost, modelBreakdown, callLog);

        log.info("\n{}", report.summary());
        return report;
    }

    public record CallRecord(String model, long inputTokens, long outputTokens) {}

    public record CostReport(
            long totalCalls,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            double inputCost,
            double outputCost,
            double totalCost,
            Map<String, CallRecord> modelBreakdown,
            List<CallRecord> callLog
    ) {
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== 评估成本报告 ===\n");
            sb.append(String.format("总调用次数: %d\n", totalCalls));
            sb.append(String.format("Input Tokens: %,d\n", inputTokens));
            sb.append(String.format("Output Tokens: %,d\n", outputTokens));
            sb.append(String.format("Total Tokens: %,d\n", totalTokens));
            sb.append(String.format("Input 成本: $%.4f\n", inputCost));
            sb.append(String.format("Output 成本: $%.4f\n", outputCost));
            sb.append(String.format("总成本: $%.4f\n", totalCost));

            if (!modelBreakdown.isEmpty()) {
                sb.append("\n--- 按模型分 ---\n");
                modelBreakdown.forEach((model, record) ->
                        sb.append(String.format("  %s: %d calls, %,d tokens\n", model, record.inputTokens + record.outputTokens, record.inputTokens + record.outputTokens)));
            }

            return sb.toString();
        }
    }
}
