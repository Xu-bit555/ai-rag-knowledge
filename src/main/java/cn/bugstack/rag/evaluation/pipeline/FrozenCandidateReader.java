package cn.bugstack.rag.evaluation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读 evaluation/retrieval/frozen_candidates_*.jsonl
 *
 * 输出：
 * - Map<queryId, List<RetrievalCandidate>>
 * - Map<queryId, List<candidateId>>（frozen top-K，仅 candidateId，给 metrics / error analysis 用）
 */
public final class FrozenCandidateReader {

    private final ObjectMapper mapper = new ObjectMapper();

    public FrozenSetBundle load(Path jsonlPath) throws IOException {
        Map<String, List<RetrievalCandidate>> byQid = new LinkedHashMap<>();
        Map<String, List<String>> frozenTopK = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(jsonlPath)) {
            String line;
            int lineNum = 0;
            while ((line = r.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> obj = mapper.readValue(line, Map.class);
                    String qid = (String) obj.get("query_id");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> candidates = (List<Map<String, Object>>) obj.get("candidates");
                    if (qid == null || candidates == null) continue;

                    List<RetrievalCandidate> list = new ArrayList<>(candidates.size());
                    List<String> ids = new ArrayList<>(candidates.size());
                    for (Map<String, Object> cm : candidates) {
                        String cid = (String) cm.get("candidate_id");
                        String sourceChunkId = (String) cm.get("source_chunk_id");
                        String documentId = (String) cm.get("document_id");
                        String text = (String) cm.get("text");
                        double score = ((Number) cm.getOrDefault("retrieval_score", 0.0)).doubleValue();
                        int rank = ((Number) cm.getOrDefault("rag_rank", 1)).intValue();
                        list.add(new RetrievalCandidate(cid, sourceChunkId, documentId, text, score, rank));
                        ids.add(cid);
                    }
                    byQid.put(qid, list);
                    frozenTopK.put(qid, ids);
                } catch (Exception e) {
                    throw new IOException("Failed to parse line " + lineNum + ": " + e.getMessage(), e);
                }
            }
        }
        return new FrozenSetBundle(byQid, frozenTopK);
    }

    public record FrozenSetBundle(
            Map<String, List<RetrievalCandidate>> candidatesByQueryId,
            Map<String, List<String>> frozenTopKByQueryId
    ) {}
}