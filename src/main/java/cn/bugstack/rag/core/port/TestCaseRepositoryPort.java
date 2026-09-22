package cn.bugstack.rag.core.port;

import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;

import java.util.List;
import java.util.Optional;

/**
 * TestCase 仓储端口
 *
 * Phase 1.5/2: rag_test_case 表结构化存储,逐步替换 pgvector raw JSON 方案。
 */
public interface TestCaseRepositoryPort {

    /**
     * Adopt 一个 Case: 分配稳定 caseId (UUID 后缀) + 写入 rag_test_case
     *
     * @param ragTag 知识库标签
     * @param caseEntity LLM 生成的 case(可能缺 caseId)
     * @return OneCase 分配的稳定 caseId
     */
    String adopt(String ragTag, TestCaseEntity caseEntity);

    /**
     * 批量 adopt
     */
    List<String> adoptAll(String ragTag, List<TestCaseEntity> cases);

    /**
     * 查 caseId
     */
    Optional<TestCaseEntity> findByCaseId(String ragTag, String caseId);

    /**
     * 列出某 ragTag 下已采纳的 cases
     */
    List<TestCaseEntity> findAdoptedByRagTag(String ragTag);

    // Phase 2 D1: 删除 saveRawDsl 接口方法 (impl 是 no-op, 现已删除)
}