package cn.bugstack.rag.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 需求模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Requirement implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原始需求内容
     */
    private String rawContent;

    /**
     * 提炼后的场景描述列表
     */
    private List<String> extractedScenarios;

    /**
     * 关联的知识库标签
     */
    private String ragTag;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

}
