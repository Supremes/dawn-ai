package com.dawn.ai.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Description;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Calculator Tool for the AI Agent.
 *
 * Design Note: In Spring AI, a Tool is a Function<Input, Output>.
 * This is analogous to a JUC Callable submitted to an ExecutorService —
 * the Agent's ReAct loop "schedules" this tool invocation when it decides
 * to compute something, just like a thread pool dispatching tasks.
 */
@Slf4j
@Component
@Description("Performs basic arithmetic calculations. Input: a mathematical expression like '2 + 3 * 4'")
public class CalculatorTool implements Function<CalculatorTool.Request, CalculatorTool.Response> {

    public record Request(String expression) {}
    public record Response(double result, String expression) {}

    @Override
    public Response apply(Request request) {
        log.info("[CalculatorTool] Evaluating expression: {}", request.expression());
        // Simple expression evaluator (production would use a proper parser like exp4j)
        try {
            // Basic safety check — no eval of arbitrary code
            String sanitized = request.expression().replaceAll("[^0-9+\\-*/().\\s]", "");
            double result = evaluateSimple(sanitized);
            return new Response(result, sanitized);
        } catch (Exception e) {
            log.warn("[CalculatorTool] Failed to evaluate: {}", request.expression(), e);
            return new Response(Double.NaN, request.expression());
        }
    }

    /** Simple recursive descent parser for +, -, *, / */
    private double evaluateSimple(String expr) {
        return new Object() {
            int pos = -1, ch;

            void nextChar() {
                ch = (++pos < expr.length()) ? expr.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) { nextChar(); return true; }
                return false;
            }

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expr.length()) throw new RuntimeException("Unexpected: " + (char) ch);
                return x;
            }

            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if      (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if      (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return +parseFactor();
                if (eat('-')) return -parseFactor();
                double x;
                int startPos = this.pos;
                if (eat('(')) {
                    x = parseExpression();
                    if (!eat(')')) throw new RuntimeException("Missing closing parenthesis");
                } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(expr.substring(startPos + 1, this.pos));
                } else {
                    throw new RuntimeException("Unexpected: " + (char) ch);
                }
                return x;
            }
        }.parse();
    }

    /**
     * AgentScope entry point — called by ReActAgent via Toolkit.
     * Delegates to {@link #apply(Request)} so business logic lives in one place.
     */
    @Tool(description = "Performs basic arithmetic calculations. Input: a mathematical expression like '2 + 3 * 4'")
    public String calculate(
            @ToolParam(name = "expression", description = "Mathematical expression to evaluate, e.g. '2 + 3 * 4'", required = true)
            String expression) {
        Response resp = apply(new Request(expression));
        if (Double.isNaN(resp.result())) {
            return "Error: could not evaluate expression '" + expression + "'";
        }
        return String.format("Result of '%s' = %s", resp.expression(), resp.result());
    }
}
