package cn.bugstack.rag.evaluation.pipeline;

import cn.bugstack.rag.evaluation.dataset.DatasetLoader;
import cn.bugstack.rag.evaluation.dataset.EvalQuery;
import cn.bugstack.rag.evaluation.judge.JevCache;
import cn.bugstack.rag.evaluation.judge.JevClient;
import cn.bugstack.rag.evaluation.metrics.ErrorAnalysis;
import cn.bugstack.rag.evaluation.metrics.MetricsAggregator;
import cn.bugstack.rag.evaluation.report.MarkdownReportWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 编排整个 smoke run：
 *   1) load dataset v1.jsonl
 *   2) load frozen candidates (E1/E2)
 *   3) for each (experiment, group): rerank every query, persist results
 *   4) compute metrics + error analysis
 *   5) write jev-ab-report.md
 *
 * 不下结论；只产出事实数据 + markdown 报告。
 */
public final class ExperimentRunner {

    private final Reranker jina;
    private final JevRerankAdapter jev;
    private final NoOpRerankAdapter noop;
    private final MetricsAggregator metricsAggregator = new MetricsAggregator();
    private final MarkdownReportWriter reportWriter = new MarkdownReportWriter();

    public ExperimentRunner(Reranker jina, JevRerankAdapter jev, NoOpRerankAdapter noop) {
        if (jina == null) throw new IllegalArgumentException("jina must not be null");
        if (jev == null) throw new IllegalArgumentException("jev must not be null");
        if (noop == null) throw new IllegalArgumentException("noop must not be null");
        this.jina = jina;
        this.jev = jev;
        this.noop = noop;
    }

    /**
     * 跑完整 smoke。
     *
     * @param datasetJsonl       evaluation/datasets/v1.jsonl
     * @param e1FrozenJsonl      evaluation/retrieval/frozen_candidates_e1.jsonl
     * @param e2FrozenJsonl      evaluation/retrieval/frozen_candidates_e2.jsonl
     * @param outputDir          evaluation/results/
     * @param reportPath         evaluation/reports/jev-ab-report.md
     * @param configE1           E1 reproducibility metadata
     * @param configE2           E2 reproducibility metadata
     * @param topN               5
     * @param parallelism        candidate-level concurrency（Jev 默认并发）
     */
    public void runSmoke(Path datasetJsonl,
                         Path e1FrozenJsonl,
                         Path e2FrozenJsonl,
                         Path outputDir,
                         Path reportPath,
                         ExperimentConfig configE1,
                         ExperimentConfig configE2,
                         int topN,
                         int parallelism) throws IOException {
        Files.createDirectories(outputDir);
        Files.createDirectories(reportPath.getParent());

        DatasetLoader loader = new DatasetLoader();
        Map<String, EvalQuery> queries = DatasetLoader.indexByQueryId(loader.loadJsonl(datasetJsonl));

        FrozenCandidateReader fcr = new FrozenCandidateReader();
        FrozenCandidateReader.FrozenSetBundle bundleE1 = fcr.load(e1FrozenJsonl);
        FrozenCandidateReader.FrozenSetBundle bundleE2 = fcr.load(e2FrozenJsonl);

        Map<String, Map<String, Map<String, Object>>> allMetrics = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Integer>>> allErrorAnalysis = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> allFrozenTopK = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> allGroundTruths = new LinkedHashMap<>();
        Map<String, String> queryTypes = new LinkedHashMap<>();

        for (EvalQuery q : queries.values()) {
            Map<String, Integer> gt = DatasetLoader.groundTruthToMap(q);
            allGroundTruths.put(q.queryId(), gt);
            queryTypes.put(q.queryId(), q.queryType());
        }

        // E1
        runOneExperiment("e1", queries, bundleE1, topN, parallelism,
                new Path[]{outputDir.resolve("baseline_e1_results.jsonl"),
                           outputDir.resolve("jev_e1_results.jsonl"),
                           outputDir.resolve("no_rerank_e1_results.jsonl")},
                configE1, allMetrics, allErrorAnalysis, allFrozenTopK);

        // E2
        runOneExperiment("e2", queries, bundleE2, topN, parallelism,
                new Path[]{outputDir.resolve("baseline_e2_results.jsonl"),
                           outputDir.resolve("jev_e2_results.jsonl"),
                           outputDir.resolve("no_rerank_e2_results.jsonl")},
                configE2, allMetrics, allErrorAnalysis, allFrozenTopK);

        // 报告
        reportWriter.write(reportPath, allMetrics, queryTypes, allErrorAnalysis,
                allFrozenTopK, allGroundTruths);
    }

    private void runOneExperiment(String expName,
                                  Map<String, EvalQuery> queries,
                                  FrozenCandidateReader.FrozenSetBundle bundle,
                                  int topN,
                                  int parallelism,
                                  Path[] resultPaths,
                                  ExperimentConfig config,
                                  Map<String, Map<String, Map<String, Object>>> allMetrics,
                                  Map<String, Map<String, Map<String, Integer>>> allErrorAnalysis,
                                  Map<String, Map<String, List<String>>> allFrozenTopK) throws IOException {
        ExecutorService exec = Executors.newFixedThreadPool(Math.max(1, parallelism));
        try {
            // ---- A 组：Jina
            Map<String, RerankOutput> aOut = runGroupConcurrently(
                    exec, queries, bundle, topN, jina, parallelism);
            persistGroup(resultPaths[0], queries, aOut, withStrategy(config, ExperimentConfig.ScoreStrategy.NA));

            // ---- B 组：Jev（B1/B2/B3 同一次 Jev response）
            // B1: choice_probability
            Map<String, RerankOutput> b1Out = runJevGroup(exec, queries, bundle, topN, jev,
                    ExperimentConfig.ScoreStrategy.CHOICE_PROBABILITY, parallelism);
            persistGroup(resultPaths[1], queries, b1Out, withStrategy(config, ExperimentConfig.ScoreStrategy.CHOICE_PROBABILITY));
            // B2: expected_score
            Map<String, RerankOutput> b2Out = runJevGroup(exec, queries, bundle, topN, jev,
                    ExperimentConfig.ScoreStrategy.EXPECTED_SCORE, parallelism);
            persistGroup(resultPaths[1], queries, b2Out, withStrategy(config, ExperimentConfig.ScoreStrategy.EXPECTED_SCORE));
            // B3: normalized_score
            Map<String, RerankOutput> b3Out = runJevGroup(exec, queries, bundle, topN, jev,
                    ExperimentConfig.ScoreStrategy.NORMALIZED_SCORE, parallelism);
            persistGroup(resultPaths[1], queries, b3Out, withStrategy(config, ExperimentConfig.ScoreStrategy.NORMALIZED_SCORE));

            // ---- C 组：NoOp
            Map<String, RerankOutput> cOut = runGroupConcurrently(
                    exec, queries, bundle, topN, noop, parallelism);
            persistGroup(resultPaths[2], queries, cOut, withStrategy(config, ExperimentConfig.ScoreStrategy.NA));

            // ---- Metrics
            Map<String, Map<String, Object>> groupMetrics = new LinkedHashMap<>();
            groupMetrics.put("A",  metricsAggregator.aggregate("A",  ExperimentConfig.ScoreStrategy.NA,
                    withStrategy(config, ExperimentConfig.ScoreStrategy.NA),
                    queries, aOut, bundle.frozenTopKByQueryId()));
            groupMetrics.put("B1", metricsAggregator.aggregate("B1", ExperimentConfig.ScoreStrategy.CHOICE_PROBABILITY,
                    withStrategy(config, ExperimentConfig.ScoreStrategy.CHOICE_PROBABILITY),
                    queries, b1Out, bundle.frozenTopKByQueryId()));
            groupMetrics.put("B2", metricsAggregator.aggregate("B2", ExperimentConfig.ScoreStrategy.EXPECTED_SCORE,
                    withStrategy(config, ExperimentConfig.ScoreStrategy.EXPECTED_SCORE),
                    queries, b2Out, bundle.frozenTopKByQueryId()));
            groupMetrics.put("B3", metricsAggregator.aggregate("B3", ExperimentConfig.ScoreStrategy.NORMALIZED_SCORE,
                    withStrategy(config, ExperimentConfig.ScoreStrategy.NORMALIZED_SCORE),
                    queries, b3Out, bundle.frozenTopKByQueryId()));
            groupMetrics.put("C",  metricsAggregator.aggregate("C",  ExperimentConfig.ScoreStrategy.NA,
                    withStrategy(config, ExperimentConfig.ScoreStrategy.NA),
                    queries, cOut, bundle.frozenTopKByQueryId()));
            allMetrics.put(expName, groupMetrics);

            // ---- Error analysis（A vs B1, A vs C, B1 vs C）
            Map<String, List<String>> rankingsA = toRankings(aOut);
            Map<String, List<String>> rankingsB1 = toRankings(b1Out);
            Map<String, List<String>> rankingsC = toRankings(cOut);

            Map<String, Map<String, Integer>> ea = new LinkedHashMap<>();
            Map<String, EvalQuery> qMap = queries;
            Map<String, Map<String, Integer>> gts = new LinkedHashMap<>();
            Map<String, List<String>> frozen = bundle.frozenTopKByQueryId();
            for (EvalQuery q : qMap.values()) {
                gts.put(q.queryId(), DatasetLoader.groundTruthToMap(q));
            }
            ea.put("A_vs_B1", ErrorAnalysis.classify(gts, rankingsA, rankingsB1, frozen));
            ea.put("A_vs_C",  ErrorAnalysis.classify(gts, rankingsA, rankingsC,  frozen));
            ea.put("B1_vs_C", ErrorAnalysis.classify(gts, rankingsB1, rankingsC, frozen));
            allErrorAnalysis.put(expName, ea);

            allFrozenTopK.put(expName, frozen);
        } finally {
            exec.shutdown();
            try { exec.awaitTermination(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
    }

    private Map<String, RerankOutput> runGroupConcurrently(ExecutorService exec,
                                                           Map<String, EvalQuery> queries,
                                                           FrozenCandidateReader.FrozenSetBundle bundle,
                                                           int topN,
                                                           Reranker reranker,
                                                           int parallelism) {
        Map<String, RerankOutput> out = new LinkedHashMap<>();
        List<Future<Map.Entry<String, RerankOutput>>> futures = new ArrayList<>();
        for (EvalQuery q : queries.values()) {
            futures.add(exec.submit(queryTask(q, bundle, topN, reranker)));
        }
        for (Future<Map.Entry<String, RerankOutput>> f : futures) {
            try {
                out.put(f.get().getKey(), f.get().getValue());
            } catch (Exception e) {
                throw new RuntimeException("rerank task failed: " + e.getMessage(), e);
            }
        }
        return out;
    }

    private Map<String, RerankOutput> runJevGroup(ExecutorService exec,
                                                  Map<String, EvalQuery> queries,
                                                  FrozenCandidateReader.FrozenSetBundle bundle,
                                                  int topN,
                                                  JevRerankAdapter jev,
                                                  ExperimentConfig.ScoreStrategy strategy,
                                                  int parallelism) {
        Map<String, RerankOutput> out = new LinkedHashMap<>();
        List<Future<Map.Entry<String, RerankOutput>>> futures = new ArrayList<>();
        for (EvalQuery q : queries.values()) {
            futures.add(exec.submit(jevTask(q, bundle, topN, jev, strategy)));
        }
        for (Future<Map.Entry<String, RerankOutput>> f : futures) {
            try {
                out.put(f.get().getKey(), f.get().getValue());
            } catch (Exception e) {
                throw new RuntimeException("jev task failed: " + e.getMessage(), e);
            }
        }
        return out;
    }

    private Callable<Map.Entry<String, RerankOutput>> queryTask(EvalQuery q,
                                                                FrozenCandidateReader.FrozenSetBundle bundle,
                                                                int topN,
                                                                Reranker reranker) {
        return () -> {
            List<RetrievalCandidate> cands = bundle.candidatesByQueryId().getOrDefault(q.queryId(), List.of());
            RerankOutput out = reranker.rerank(q.query(), cands, topN);
            return Map.entry(q.queryId(), out);
        };
    }

    private Callable<Map.Entry<String, RerankOutput>> jevTask(EvalQuery q,
                                                              FrozenCandidateReader.FrozenSetBundle bundle,
                                                              int topN,
                                                              JevRerankAdapter jev,
                                                              ExperimentConfig.ScoreStrategy strategy) {
        return () -> {
            List<RetrievalCandidate> cands = bundle.candidatesByQueryId().getOrDefault(q.queryId(), List.of());
            RerankOutput out = jev.rerank(q.query(), cands, topN, strategy);
            return Map.entry(q.queryId(), out);
        };
    }

    private void persistGroup(Path jsonl,
                              Map<String, EvalQuery> queries,
                              Map<String, RerankOutput> outputs,
                              ExperimentConfig config) throws IOException {
        ResultsPersister persister = new ResultsPersister();
        // 清空原文件，再 append（每个 strategy 重排都要重写）
        Files.deleteIfExists(jsonl);
        Files.createDirectories(jsonl.getParent());
        for (EvalQuery q : queries.values()) {
            RerankOutput ro = outputs.get(q.queryId());
            if (ro == null) continue;
            persister.appendResults(jsonl, q.queryId(), config, ro);
        }
    }

    private Map<String, List<String>> toRankings(Map<String, RerankOutput> outputs) {
        Map<String, List<String>> perQ = new LinkedHashMap<>();
        for (Map.Entry<String, RerankOutput> e : outputs.entrySet()) {
            List<String> ids = new ArrayList<>();
            for (RankedItem it : e.getValue().ranking()) ids.add(it.candidateId());
            perQ.put(e.getKey(), ids);
        }
        return perQ;
    }

    private static ExperimentConfig withStrategy(ExperimentConfig base, ExperimentConfig.ScoreStrategy strategy) {
        return new ExperimentConfig(
                base.experimentId(),
                base.datasetVersion(),
                base.retrievalVersion(),
                base.candidatePipeline(),
                base.mmrLambda(),
                base.candidateK(),
                base.topN(),
                base.reranker(),
                base.jevModel(),
                base.jevPromptVersion(),
                strategy);
    }
}