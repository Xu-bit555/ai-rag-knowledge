package cn.bugstack.rag.evaluation.pipeline;

/**
 * 单次实验的配置（per plan §六 reproducibility 字段）。
 *
 * 每条 result 都附带一份 ExperimentConfig，用于事后复现。
 */
public record ExperimentConfig(
        String experimentId,
        String datasetVersion,
        String retrievalVersion,
        CandidatePipeline candidatePipeline,
        Double mmrLambda,
        int candidateK,
        int topN,
        String reranker,
        String jevModel,
        String jevPromptVersion,
        ScoreStrategy scoreStrategy
) {

    public enum CandidatePipeline {
        DEDUP_ONLY("dedup_only"),
        DEDUP_MMR("dedup+mmr");

        private final String wireValue;
        CandidatePipeline(String wireValue) { this.wireValue = wireValue; }
        public String wireValue() { return wireValue; }
    }

    public enum ScoreStrategy {
        CHOICE_PROBABILITY("choice_probability"),
        EXPECTED_SCORE("expected_score"),
        NORMALIZED_SCORE("normalized_score"),
        NA("n/a");

        private final String wireValue;
        ScoreStrategy(String wireValue) { this.wireValue = wireValue; }
        public String wireValue() { return wireValue; }
    }

    public ExperimentConfig {
        if (experimentId == null || experimentId.isBlank())
            throw new IllegalArgumentException("experimentId must not be blank");
        if (datasetVersion == null || datasetVersion.isBlank())
            throw new IllegalArgumentException("datasetVersion must not be blank");
        if (candidatePipeline == null)
            throw new IllegalArgumentException("candidatePipeline must not be null");
        if (candidateK < 1)
            throw new IllegalArgumentException("candidateK must be >= 1");
        if (topN < 1)
            throw new IllegalArgumentException("topN must be >= 1");
        if (reranker == null || reranker.isBlank())
            throw new IllegalArgumentException("reranker must not be blank");
        if (scoreStrategy == null)
            throw new IllegalArgumentException("scoreStrategy must not be null");
        if (candidatePipeline == CandidatePipeline.DEDUP_MMR && mmrLambda == null)
            throw new IllegalArgumentException("mmrLambda required for dedup+mmr pipeline");
    }
}