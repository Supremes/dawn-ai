package com.dawn.ai.agent.aop;

import com.dawn.ai.agent.AgentStep;
import com.dawn.ai.agent.StepCollector;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Intercepts every tool invocation in the agent tools package and records it as an AgentStep.
 *
 * The pointcut covers all current and future tools under com.dawn.ai.agent.tools,
 * so new tools are automatically traced without any modification.
 */
@Slf4j
@Aspect
@Component
public class ToolExecutionAspect {

    @Around("execution(* com.dawn.ai.agent.tools.*.apply(..))")
    public Object captureStep(ProceedingJoinPoint pjp) throws Throwable {
        String toolName = pjp.getTarget().getClass().getSimpleName();
        Object input = pjp.getArgs()[0];
        long start = System.currentTimeMillis();

        Object result = pjp.proceed();

        long durationMs = System.currentTimeMillis() - start;
        int stepNum = StepCollector.nextStepNumber();

        StepCollector.record(new AgentStep(stepNum, toolName, input, result.toString(), durationMs));

        log.debug("[ReAct] Step {} | tool={} | input={} | output={} | {}ms",
                stepNum, toolName, input, result, durationMs);

        return result;
    }
}
