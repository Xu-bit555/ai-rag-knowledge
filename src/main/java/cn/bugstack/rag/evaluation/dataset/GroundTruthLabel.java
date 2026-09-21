package cn.bugstack.rag.evaluation.dataset;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ground truth 单条 label（per plan §四）。
 *
 * 强制 provenance 字段：
 * - annotationSource  : "human" | "human+llm_review"
 * - annotationVersion : 每次数据更新 bump 版本
 * - annotationDate    : ISO date
 *
 * 历史 chunk 使用过 ≠ relevance=3。最终 relevance 必须人工确认。
 */
public record GroundTruthLabel(
        @JsonProperty("candidate_id")        String candidateId,
        @JsonProperty("source_chunk_id")     String sourceChunkId,   // null if unknown
        @JsonProperty("document_id")         String documentId,      // null if unknown
        @JsonProperty("relevance")           int    relevance,       // 0/1/2/3
        @JsonProperty("annotation_source")   String annotationSource,
        @JsonProperty("annotation_version")  String annotationVersion,
        @JsonProperty("annotation_date")     String annotationDate,
        @JsonProperty("annotation_notes")    String annotationNotes  // optional, may be null
) {
    public GroundTruthLabel {
        if (candidateId == null || candidateId.isBlank())
            throw new IllegalArgumentException("candidateId must not be blank");
        if (relevance < 0 || relevance > 3)
            throw new IllegalArgumentException("relevance must be in [0,3], got " + relevance);
        if (annotationSource == null || annotationSource.isBlank())
            throw new IllegalArgumentException("annotationSource must not be blank");
        if (annotationVersion == null || annotationVersion.isBlank())
            throw new IllegalArgumentException("annotationVersion must not be blank");
        if (annotationDate == null || annotationDate.isBlank())
            throw new IllegalArgumentException("annotationDate must not be blank");
    }

    public static final String SRC_HUMAN = "human";
    public static final String SRC_HUMAN_LLM = "human+llm_review";
}