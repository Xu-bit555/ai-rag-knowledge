package cn.bugstack.rag.evaluation.pipeline;

import cn.bugstack.rag.evaluation.dataset.DatasetLoader;
import cn.bugstack.rag.evaluation.dataset.EvalQuery;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 evaluation/datasets/v1.jsonl 出发，生成 frozen_candidates_e1.jsonl / frozen_candidates_e2.jsonl。
 *
 * E1 = dedup + MMR（selectCount = min(candidates.size(), topK)）
 * E2 = dedup only（passthrough，保持 retrieval 原 rank）
 *
 * v1.jsonl 里的 candidates 来自 hand-crafted 的"真实知识库片段"（per plan §二十六 6）。
 * 本生成器不重新跑 retrieval，仅在 hand-crafted candidates 上应用 dedup + MMR 即可。
 */
public final class FrozenCandidateSetGenerator {

    private final ObjectMapper mapper = new ObjectMapper();

    public void generate(Path datasetJsonl,
                         Path e1Jsonl,
                         Path e2Jsonl,
                         int candidateK,
                         String experimentIdE1,
                         String experimentIdE2) throws IOException {
        DatasetLoader loader = new DatasetLoader();
        List<EvalQuery> queries = loader.loadJsonl(datasetJsonl);
        Files.createDirectories(e1Jsonl.getParent());

        try (BufferedWriter w = Files.newBufferedWriter(e1Jsonl, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (EvalQuery q : queries) {
                List<RetrievalCandidate> input = toRetrievalCandidates(q);
                List<RetrievalCandidate> afterMmr = FrozenRetrievalRunner.applyMMR(
                        input,
                        FrozenRetrievalRunner.MMR_LAMBDA,
                        Math.min(candidateK, input.size()));
                writeLine(w, q, afterMmr,
                        ExperimentConfig.CandidatePipeline.DEDUP_MMR,
                        FrozenRetrievalRunner.MMR_LAMBDA,
                        candidateK, experimentIdE1);
            }
        }

        try (BufferedWriter w = Files.newBufferedWriter(e2Jsonl, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (EvalQuery q : queries) {
                List<RetrievalCandidate> passthrough = toRetrievalCandidates(q);
                writeLine(w, q, passthrough,
                        ExperimentConfig.CandidatePipeline.DEDUP_ONLY,
                        null,
                        candidateK, experimentIdE2);
            }
        }
    }

    private static List<RetrievalCandidate> toRetrievalCandidates(EvalQuery q) {
        List<RetrievalCandidate> out = new ArrayList<>(q.candidates().size());
        for (EvalQuery.CandidateFromDataset c : q.candidates()) {
            out.add(c.toRetrievalCandidate());
        }
        return out;
    }

    private void writeLine(BufferedWriter w,
                           EvalQuery q,
                           List<RetrievalCandidate> candidates,
                           ExperimentConfig.CandidatePipeline pipeline,
                           Double mmrLambda,
                           int candidateK,
                           String experimentId) throws IOException {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("query_id", q.queryId());
        line.put("query", q.query());
        line.put("rag_tag", q.ragTag());
        line.put("experiment_id", experimentId);
        line.put("candidate_pipeline", pipeline.wireValue());
        line.put("mmr_lambda", mmrLambda);
        line.put("candidate_k", candidateK);

        List<Map<String, Object>> list = new ArrayList<>(candidates.size());
        for (RetrievalCandidate c : candidates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("candidate_id", c.candidateId());
            m.put("source_chunk_id", c.sourceChunkId());
            m.put("document_id", c.documentId());
            m.put("text", c.text());
            m.put("retrieval_score", c.retrievalScore());
            m.put("rag_rank", c.ragRank());
            list.add(m);
        }
        line.put("candidates", list);
        w.write(mapper.writeValueAsString(line));
        w.newLine();
    }
}