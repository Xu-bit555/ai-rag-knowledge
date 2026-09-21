package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.dsl.v1.CanonicalTestCase;
import cn.bugstack.rag.core.domain.dsl.v1.DslValidator;
import cn.bugstack.rag.core.domain.dsl.v1.DslValidator.ValidationResult;
import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.core.port.TestCaseRepositoryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * AdoptCasesUseCase - 把生成的 Case 转为 Adopted Test Asset
 *
 * 流程:
 * 1. DslValidator 二次校验 (防止 MCP client 传入非法 DSL)
 * 2. 为每个 Case 分配稳定 caseId (UUID 后缀)
 * 3. 写入 rag_test_case 表
 * 4. 返回 stable caseId 列表
 *
 * 状态: GENERATED → ADOPTED
 *
 * P0-8 修复: 加 @Transactional(rollbackFor = Exception.class) 保证批量 adopt 的 all-or-nothing 语义,
 *   避免前 N 条落库后第 N+1 条失败导致 DB 半污染 (原依赖 repo 层单条 @Transactional).
 *
 * P0-5 修复: catch 块把 inner cause 拼到 message, MCP 客户端能拿到详细失败原因
 *   (原 message 仅 "Failed to validate DSL", inner cause 被丢弃).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdoptCasesUseCase {

    private final DslValidator dslValidator;
    private final TestCaseRepositoryPort testCaseRepository;
    private final ObjectMapper objectMapper;

    /**
     * 采纳 DSL 中的所有 Cases (事务包裹)
     *
     * @param ragTag 知识库标签
     * @param dsl 完整 Canonical DSL (含 summary + cases[])
     * @return 每个 case 的稳定 caseId (按 cases 顺序)
     * @throws IllegalArgumentException DSL 校验失败
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> execute(String ragTag, CanonicalTestCase dsl) {
        if (dsl == null || dsl.getCases() == null || dsl.getCases().isEmpty()) {
            throw new IllegalArgumentException("DSL must contain at least one case");
        }

        // 1. 二次校验 (失败 → 整体回滚)
        try {
            String json = objectMapper.writeValueAsString(dsl);
            ValidationResult vr = dslValidator.validate(json);
            if (!vr.isValid()) {
                throw new IllegalArgumentException(
                        "DSL validation failed: " + String.join("; ", vr.getErrorMessages()));
            }
        } catch (Exception e) {
            // P0-5: 把 inner cause 拼到 message, MCP 客户端拿到详细原因
            throw new IllegalArgumentException(
                    "Failed to validate DSL: " + e.getClass().getSimpleName()
                            + " - " + (e.getMessage() != null ? e.getMessage() : "(no message)"),
                    e);
        }

        // 2. 写入 rag_test_case (任一抛 RuntimeException → 整体 rollback)
        List<String> caseIds = new ArrayList<>();
        for (TestCaseEntity tc : dsl.getCases()) {
            String stableId = testCaseRepository.adopt(ragTag, tc);
            caseIds.add(stableId);
        }

        log.info("Adopted {} cases for ragTag={} (transactional)", caseIds.size(), ragTag);
        return caseIds;
    }

    /**
     * 采纳单个 Case (供单独 MCP Tool 调用)
     */
    @Transactional(rollbackFor = Exception.class)
    public String executeSingle(String ragTag, TestCaseEntity caseEntity) {
        try {
            String json = objectMapper.writeValueAsString(caseEntity);
            ValidationResult vr = dslValidator.validate(
                    "{\"schemaVersion\":\"1.0.0\",\"summary\":{\"app\":\"\",\"page\":\"\",\"totalCases\":1},\"cases\":["
                            + json + "]}");
            if (!vr.isValid()) {
                throw new IllegalArgumentException(
                        "Case validation failed: " + String.join("; ", vr.getErrorMessages()));
            }
        } catch (Exception e) {
            // P0-5: 拼接 inner cause
            throw new IllegalArgumentException(
                    "Failed to validate case: " + e.getClass().getSimpleName()
                            + " - " + (e.getMessage() != null ? e.getMessage() : "(no message)"),
                    e);
        }

        return testCaseRepository.adopt(ragTag, caseEntity);
    }
}