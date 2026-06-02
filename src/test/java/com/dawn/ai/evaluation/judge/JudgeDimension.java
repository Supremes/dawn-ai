package com.dawn.ai.evaluation.judge;

public enum JudgeDimension {

    TOOL_SELECTION("tool_selection", "evaluation/judge-prompts/tool_selection.txt", ScoreType.BINARY),
    RAG_RECALL("rag_recall", "evaluation/judge-prompts/rag_recall.txt", ScoreType.LIKERT),
    ANSWER_COMPLETENESS("answer_completeness", "evaluation/judge-prompts/answer_completeness.txt", ScoreType.LIKERT),
    PROMPT_ASSEMBLY("prompt_assembly", "evaluation/judge-prompts/prompt_assembly.txt", ScoreType.BINARY),
    SUBAGENT_ISOLATION("subagent_isolation", "evaluation/judge-prompts/subagent_isolation.txt", ScoreType.BINARY),
    MULTI_TURN_COHERENCE("multi_turn_coherence", "evaluation/judge-prompts/multi_turn_coherence.txt", ScoreType.LIKERT);

    private final String id;
    private final String promptPath;
    private final ScoreType scoreType;

    JudgeDimension(String id, String promptPath, ScoreType scoreType) {
        this.id = id;
        this.promptPath = promptPath;
        this.scoreType = scoreType;
    }

    public String id() { return id; }
    public String promptPath() { return promptPath; }
    public ScoreType scoreType() { return scoreType; }

    public enum ScoreType {
        BINARY,
        LIKERT
    }
}
