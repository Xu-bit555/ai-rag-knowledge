package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 查询知识库标签列表响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryTagListResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 标签列表
     */
    private List<String> tags;

    /**
     * 标签数量
     */
    private Integer count;

}
