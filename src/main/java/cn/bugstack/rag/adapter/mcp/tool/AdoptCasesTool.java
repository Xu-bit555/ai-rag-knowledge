package cn.bugstack.rag.adapter.mcp.tool;

import cn.bugstack.rag.core.domain.dsl.v1.CanonicalTestCase;
import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.core.usecase.AdoptCasesUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * onecase_adopt_cases MCP Tool
 *
 * Phase 2: Canonical DSL → rag_test_case 持久化
 * Phase 3: 返回值增加 vectorSyncStatus 字段 (PENDING/PARTIAL/FAILED),
 *   告知 Agent 向量镜像异步处理状态, DLQ 可通过 onecase_get_dlq_stats 查询.
 *
 * 调用时机: validate_case 之后、create_test_run 之前
 *
 * 返回: 稳定 caseId 列表 (UUID 后缀格式, 如 TC_LOGIN_001_a3b9c2d1)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdoptCasesTool {

    private final AdoptCasesUseCase adoptCasesUseCase;

    @Tool(name = "onecase_adopt_cases",
            description = "Persist generated Canonical DSL test cases as 'Adopted Test Assets' in the "
                    + "rag_test_case table. Accepts a complete CanonicalTestCaseDSL object (must contain "
                    + "schemaVersion=1.0.0, summary, and cases[]). Each case is assigned a stable caseId "
                    + "(UUID suffix appended to original LLM caseId). Returns {adoptedCaseIds: string[], "
                    + "count, ragTag, vectorSyncStatus, vectorSyncMessage}. vectorSyncStatus is PENDING "
                    + "when all cases are dispatched to the vector mirror stream (Phase 3 fire-and-forget); "
                    + "use onecase_get_dlq_stats to monitor background processing.")
    public Map<String, Object> adopt(
            @ToolParam(description = "Knowledge base tag", required = true) String ragTag,
            @ToolParam(description = "Complete CanonicalTestCaseDSL object", required = true) CanonicalTestCase dsl) {

        log.info("MCP onecase_adopt_cases: ragTag={}, caseCount={}", ragTag,
                dsl == null || dsl.getCases() == null ? 0 : dsl.getCases().size());

        if (dsl == null || dsl.getCases() == null || dsl.getCases().isEmpty()) {
            throw new IllegalArgumentException("DSL must contain at least one case");
        }

        List<String> caseIds = adoptCasesUseCase.execute(ragTag, dsl);

        // Phase 3: 向量镜像状态 (adopt 返回时向量镜像已派发, 状态为 PENDING)
        long nonNull = caseIds.stream().filter(Objects::nonNull).count();
        String status = (nonNull == caseIds.size()) ? "PENDING"
                : (nonNull == 0) ? "FAILED"
                : "PARTIAL";

        return Map.of(
                "adoptedCaseIds", caseIds,
                "count", nonNull,
                "ragTag", ragTag,
                "vectorSyncStatus", status,
                "vectorSyncMessage", status + ": " + nonNull + "/" + caseIds.size() +
                        " cases committed to rag_test_case; vector mirror dispatched asynchronously. " +
                        "Use onecase_get_dlq_stats to monitor DLQ."
        );
    }

    /**
     * 采纳单个 Case (供特殊场景使用，如 Agent 动态生成单个 edge case)
     */
    public Map<String, Object> adoptSingle(
            @ToolParam(description = "Knowledge base tag", required = true) String ragTag,
            @ToolParam(description = "Single TestCaseEntity", required = true) TestCaseEntity caseEntity) {

        String stableId = adoptCasesUseCase.executeSingle(ragTag, caseEntity);
        return Map.of(
                "adoptedCaseId", stableId,
                "ragTag", ragTag,
                "vectorSyncStatus", "PENDING",
                "vectorSyncMessage", "Case committed to rag_test_case; vector mirror dispatched asynchronously."
        );
    }
}
