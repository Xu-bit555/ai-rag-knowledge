package cn.bugstack.rag.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 测试用例模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCase implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用例ID
     */
    private String id;

    /**
     * 用例标题
     */
    private String title;

    /**
     * 用例类型
     */
    private CaseType caseType;

    /**
     * 前置条件
     */
    private String precondition;

    /**
     * 测试步骤
     */
    private List<TestStep> steps;

    /**
     * 预期结果/断言
     */
    private List<String> assertions;

    /**
     * 目标App包名
     */
    private String appPackage;

    /**
     * 目标页面
     */
    private String page;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 采纳状态：pending-待采纳, adopted-已采纳, rejected-已拒绝
     */
    private AdoptionStatus adoptionStatus;

    /**
     * 采纳时间
     */
    private LocalDateTime adoptedAt;

    /**
     * 拒绝原因（可选）
     */
    private String rejectReason;

    /**
     * 用例类型枚举
     */
    public enum CaseType {
        NORMAL,     // 正常用例
        FLOW,       // 流程用例
        EXCEPTION,  // 异常用例
        BOUNDARY    // 边界用例
    }

    /**
     * 采纳状态枚举
     */
    public enum AdoptionStatus {
        PENDING,    // 待采纳
        ADOPTED,    // 已采纳
        REJECTED    // 已拒绝
    }

}
