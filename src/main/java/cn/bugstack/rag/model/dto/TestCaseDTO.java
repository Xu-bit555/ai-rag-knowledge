package cn.bugstack.rag.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 测试用例DTO - 用于JSON序列化和API响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseDTO implements Serializable {

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
     * 前置条件
     */
    private String precondition;

    /**
     * 测试步骤
     */
    private List<StepDTO> steps;

    /**
     * 断言/预期结果
     */
    private List<String> assertions;

    /**
     * 目标App包名（可选）
     */
    private String appPackage;

    /**
     * 目标页面（可选）
     */
    private String page;

    /**
     * 用例类型：NORMAL-正常 FLOW-流程 EXCEPTION-异常 BOUNDARY-边界
     */
    private String caseType;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 步骤序号
         */
        private Integer order;

        /**
         * 操作类型：click/input/swipe/screenshot/open_app/back/home
         */
        private String action;

        /**
         * 目标元素描述
         */
        private String target;

        /**
         * 输入值（input时使用）
         */
        private String value;

        /**
         * 预期状态
         */
        private String expectedState;
    }

}
