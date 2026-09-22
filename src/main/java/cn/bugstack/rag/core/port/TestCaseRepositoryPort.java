package cn.bugstack.rag.core.port;

import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;

import java.util.List;
import java.util.Optional;

/**
 * TestCase 仓储端口
 *
 * Phase 1.5/2: rag_test_case 表结构化存储,逐步替换 pgvector raw JSON 方案。
 *
 * Phase 3: 拆异步双写 - adopt() / adoptAll() 内部 @Transactional(REQUIRES_NEW)
 *   独立提交结构表; 向量镜像派发 fire-and-forget, 由 VectorMirrorDispatcher 异步处理.
 */
public interface TestCaseRepositoryPort {

    /**
     * Adopt 一个 Case: 分配稳定 caseId (UUID 后缀) + 写入 rag_test_case + 异步派发向量镜像.
     *
     * Phase 3 语义: 返回 stableId 时, 结构表已 commit; 向量镜像可能仍在派发/处理中.
     *   业务方可通过 AdoptCasesTool 返回的 vectorSyncStatus 字段判断镜像状态.
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