package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Rerank响应 - 返回重排序后的历史用例
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RerankResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 重排序后的历史用例
     */
    private List<String> rerankedCases;

    /**
     * 提炼出的需求场景
     */
    private String extractedScenarios;

}
