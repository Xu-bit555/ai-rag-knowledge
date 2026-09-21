package cn.bugstack.rag.core.domain.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单步执行结果
 *
 * 每个 DSL step 的实际执行记录,带真实证据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepResult {
    private Integer order;
    private String action;            // NAVIGATE / CLICK / INPUT / ...
    private String targetDescription; // e.g. "ROLE=button,NAME=登录"
    private String resolvedRef;       // Playwright 实际使用的 ref (来自 snapshot)
    private String actualResult;       // 工具返回的文本
    private Boolean passed;            // assertion/执行是否成功
    private String errorMessage;       // 失败时填
    private Long durationMs;
    private String artifactId;         // 如有 screenshot,记录 artifact_id
}