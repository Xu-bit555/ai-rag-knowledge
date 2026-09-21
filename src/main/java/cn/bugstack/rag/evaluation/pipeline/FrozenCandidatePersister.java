package cn.bugstack.rag.evaluation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Frozen Candidate Set 序列化为 jsonl（per plan §五）。
 *
 * 输入: queryId, query, ragTag, experiment config, candidates
 * 输出: evaluation/retrieval/frozen_candidates_{e1|e2}.jsonl
 */
public final class FrozenCandidatePersister {

    private final ObjectMapper mapper = new ObjectMapper();

    public void appendFrozenSet(Path jsonlPath,
                                String queryId,
                                String query,
                                String ragTag,
                                ExperimentConfig.CandidatePipeline pipeline,
                                Double mmrLambda,
                                int candidateK,
                                String experimentId,
                                List<RetrievalCandidate> candidates) throws IOException {
        Files.createDirectories(jsonlPath.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(jsonlPath, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("query_id", queryId);
            line.put("query", query);
            line.put("rag_tag", ragTag == null ? "" : ragTag);
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
}