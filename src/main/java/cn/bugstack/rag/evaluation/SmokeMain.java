package cn.bugstack.rag.evaluation;

import cn.bugstack.rag.evaluation.dataset.SmokeDatasetBuilder;
import cn.bugstack.rag.evaluation.judge.JevCache;
import cn.bugstack.rag.evaluation.judge.JevClient;
import cn.bugstack.rag.evaluation.pipeline.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Smoke main：从命令行跑一遍：
 *   1) prepare_dataset       → evaluation/datasets/v1.jsonl
 *   2) freeze_retrieval      → frozen_candidates_e1.jsonl + frozen_candidates_e2.jsonl
 *   3) run_smoke             → evaluation/results/ + evaluation/reports/jev-ab-report.md
 *
 * 用法：
 *   mvn exec:java -Dexec.mainClass=cn.bugstack.rag.evaluation.SmokeMain
 *
 * 或者直接：
 *   java -cp ... cn.bugstack.rag.evaluation.SmokeMain
 *
 * 环境变量：
 *   AI_GATEWAY_API_KEY    必填（Jev 调用）
 *   JINA_API_KEY          必填（Jina 调用；缺则 Jina adapter degrade 走 retrieval_score）
 *   EVAL_DIR              可选，evaluation 输出根目录（默认 ./evaluation）
 */
public final class SmokeMain {

    public static void main(String[] args) throws Exception {
        Path evalDir = Paths.get(System.getenv().getOrDefault("EVAL_DIR", "evaluation"));
        Path retrievalDir = evalDir.resolve("retrieval");
        Path datasetPath = evalDir.resolve("datasets/v1.jsonl");
        Path resultsDir = evalDir.resolve("results");
        Path reportPath = evalDir.resolve("reports/jev-ab-report.md");
        Path cacheDir = evalDir.resolve("cache/jev");

        String datasetVersion = "v1";
        String retrievalVersion = "smoke_20260921";
        String experimentIdE1 = "e1_" + datasetVersion + "_" + ts();
        String experimentIdE2 = "e2_" + datasetVersion + "_" + ts();

        // ---- 1) prepare_dataset
        SmokeDatasetBuilder.write(datasetPath);
        System.out.println("[1/3] dataset written → " + datasetPath);

        // ---- 2) freeze_retrieval
        new FrozenCandidateSetGenerator().generate(
                datasetPath,
                retrievalDir.resolve("frozen_candidates_e1.jsonl"),
                retrievalDir.resolve("frozen_candidates_e2.jsonl"),
                /*candidateK*/ 30,
                experimentIdE1,
                experimentIdE2);
        System.out.println("[2/3] frozen candidates written → " + retrievalDir);

        // ---- 3) run_smoke
        int topN = 5;
        // Jev 上游限流严格，smoke 阶段先用 sequential（80 candidates ~ 16-30s），
        // Phase 2 再上并发（per plan §十八）。
        int parallelism = 1;

        // Rerankers
        JinaRerankAdapter jina = buildJina();
        JevCache jevCache = new JevCache(cacheDir);
        JevClient jevClient = buildJev();
        JevRerankAdapter jev = new JevRerankAdapter(jevClient, jevCache);
        NoOpRerankAdapter noop = new NoOpRerankAdapter();

        ExperimentConfig configE1 = new ExperimentConfig(
                experimentIdE1, datasetVersion, retrievalVersion,
                ExperimentConfig.CandidatePipeline.DEDUP_MMR,
                FrozenRetrievalRunner.MMR_LAMBDA,
                /*candidateK*/ 30, topN,
                "varies",      // 每个 group 不同（reranker 字段）
                JevClient.DEFAULT_MODEL,
                cn.bugstack.rag.evaluation.judge.JevPrompt.VERSION,
                ExperimentConfig.ScoreStrategy.NA);

        ExperimentConfig configE2 = new ExperimentConfig(
                experimentIdE2, datasetVersion, retrievalVersion,
                ExperimentConfig.CandidatePipeline.DEDUP_ONLY,
                /*mmrLambda*/ null,
                /*candidateK*/ 30, topN,
                "varies",
                JevClient.DEFAULT_MODEL,
                cn.bugstack.rag.evaluation.judge.JevPrompt.VERSION,
                ExperimentConfig.ScoreStrategy.NA);

        new ExperimentRunner(jina, jev, noop).runSmoke(
                datasetPath,
                retrievalDir.resolve("frozen_candidates_e1.jsonl"),
                retrievalDir.resolve("frozen_candidates_e2.jsonl"),
                resultsDir,
                reportPath,
                configE1, configE2,
                topN, parallelism);

        System.out.println("[3/3] results + report written → " + reportPath);
        System.out.println("Jev cache: " + jevCache.hits() + " hits / " + jevCache.misses() + " misses");
    }

    private static JinaRerankAdapter buildJina() {
        try {
            return new JinaRerankAdapter();
        } catch (IllegalStateException e) {
            System.err.println("[warn] JINA_API_KEY missing — Jina adapter will run in degrade mode (falls back to retrieval_score DESC)");
            return new JinaRerankAdapter(
                    JinaRerankAdapter.DEFAULT_MODEL,
                    JinaRerankAdapter.DEFAULT_URL,
                    "degraded-key-not-real");  // 触发每次调用走 degrade
        }
    }

    private static JevClient buildJev() {
        try {
            return new JevClient();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("AI_GATEWAY_API_KEY env var is required for Jev. " +
                    "Set it via: export AI_GATEWAY_API_KEY=...", e);
        }
    }

    private static String ts() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }
}