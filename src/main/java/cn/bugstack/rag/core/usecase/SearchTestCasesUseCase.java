package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.core.port.TestCaseRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SearchTestCasesUseCase - 检索历史已采纳用例
 *
 * MVP: PostgreSQL 简单 LIKE 匹配 title/caseId
 * Phase 4+: 可加 pgvector 向量检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchTestCasesUseCase {

    private final TestCaseRepositoryPort testCaseRepository;

    /**
     * 检索
     *
     * @param ragTag 知识库标签
     * @param query 查询关键词
     * @param topK 返回条数
     * @return List of {caseId, title, automationCandidate, steps, assertions, similarity}
     */
    public List<Map<String, Object>> execute(String ragTag, String query, Integer topK) {
        int k = (topK != null && topK > 0) ? topK : 10;
        log.info("SearchTestCasesUseCase: ragTag={}, query.length={}, topK={}",
                ragTag, query == null ? 0 : query.length(), k);

        List<TestCaseEntity> all = testCaseRepository.findAdoptedByRagTag(ragTag);

        if (query == null || query.isBlank()) {
            return toMaps(all.subList(0, Math.min(k, all.size())));
        }

        String lower = query.toLowerCase();
        List<Map<String, Object>> matched = new ArrayList<>();
        for (TestCaseEntity tc : all) {
            String title = tc.getTitle() != null ? tc.getTitle().toLowerCase() : "";
            String caseId = tc.getCaseId() != null ? tc.getCaseId().toLowerCase() : "";
            if (title.contains(lower) || caseId.contains(lower)) {
                matched.add(toMap(tc));
                if (matched.size() >= k) break;
            }
        }
        log.info("SearchTestCasesUseCase: matched={} from total={}", matched.size(), all.size());
        return matched;
    }

    private List<Map<String, Object>> toMaps(List<TestCaseEntity> entities) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TestCaseEntity tc : entities) result.add(toMap(tc));
        return result;
    }

    private Map<String, Object> toMap(TestCaseEntity tc) {
        Map<String, Object> m = new HashMap<>();
        m.put("caseId", tc.getCaseId());
        m.put("title", tc.getTitle());
        m.put("automationCandidate",
                tc.getAutomationCandidate() != null ? tc.getAutomationCandidate().name() : null);
        m.put("priority", tc.getPriority() != null ? tc.getPriority().name() : null);
        m.put("steps", tc.getSteps());
        m.put("assertions", tc.getAssertions());
        m.put("expectedOutcome", tc.getExpectedOutcome());
        return m;
    }
}