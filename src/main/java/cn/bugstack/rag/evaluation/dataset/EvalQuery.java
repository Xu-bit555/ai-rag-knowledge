package cn.bugstack.rag.evaluation.dataset;

import cn.bugstack.rag.evaluation.pipeline.RetrievalCandidate;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * EvalQuery（per plan §四）：
 * v1.jsonl 的每行 JSON 反序列化目标。
 *
 * 含 candidates 字段（v1.jsonl 里就有），方便 FrozenCandidateSetGenerator 直接拿 hand-crafted candidates。
 */
public record EvalQuery(
        @JsonProperty("query_id")            String queryId,
        @JsonProperty("query")               String query,
        @JsonProperty("rag_tag")             String ragTag,
        @JsonProperty("query_type")          String queryType,
        @JsonProperty("query_type_tags")     List<String> queryTypeTags,
        @JsonProperty("candidates")          List<CandidateFromDataset> candidates,
        @JsonProperty("ground_truth")        List<GroundTruthLabel> groundTruth
) {
    public EvalQuery {
        if (queryId == null || queryId.isBlank())
            throw new IllegalArgumentException("queryId must not be blank");
        if (query == null || query.isBlank())
            throw new IllegalArgumentException("query must not be blank");
        if (ragTag == null) ragTag = "";
        if (queryType == null) queryType = "unknown";
        if (queryTypeTags == null) queryTypeTags = List.of();
        if (candidates == null) candidates = List.of();
        if (groundTruth == null) groundTruth = List.of();
    }

    /**
     * jsonl 里 candidates 数组的元素形态（candidates 由 DatasetBuilder 写入，
     * 已经带 retrieval_score / rag_rank）。
     */
    public record CandidateFromDataset(
            @JsonProperty("candidate_id")    String candidateId,
            @JsonProperty("source_chunk_id") String sourceChunkId,
            @JsonProperty("document_id")     String documentId,
            @JsonProperty("text")            String text,
            @JsonProperty("retrieval_score") double retrievalScore,
            @JsonProperty("rag_rank")        int ragRank
    ) {
        public RetrievalCandidate toRetrievalCandidate() {
            return new RetrievalCandidate(
                    candidateId, sourceChunkId, documentId, text, retrievalScore, ragRank);
        }
    }
}