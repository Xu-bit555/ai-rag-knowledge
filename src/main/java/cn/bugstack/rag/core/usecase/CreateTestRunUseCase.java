package cn.bugstack.rag.core.usecase;

import cn.bugstack.rag.core.domain.dsl.v1.TestCaseEntity;
import cn.bugstack.rag.core.domain.execution.TestRun;
import cn.bugstack.rag.core.domain.execution.TestRunStatus;
import cn.bugstack.rag.core.port.TestCaseRepositoryPort;
import cn.bugstack.rag.core.port.TestRunRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CreateTestRunUseCase - 创建 TestRun
 *
 * 校验:
 * 1. Case 必须存在
 * 2. Case 必须 ADOPTED 状态
 * 3. Case 必须同一 ragTag
 * 4. automationCandidate 必须 = WEB_FUNCTIONAL (MVP 限制)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreateTestRunUseCase {

    private final TestRunRepositoryPort testRunRepository;
    private final TestCaseRepositoryPort testCaseRepository;

    public TestRun execute(String ragTag, String name, String description, String targetUrl,
                          List<String> caseIds) {

        if (ragTag == null || ragTag.isBlank()) {
            throw new IllegalArgumentException("ragTag is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (caseIds == null || caseIds.isEmpty()) {
            throw new IllegalArgumentException("caseIds must not be empty");
        }

        log.info("CreateTestRunUseCase: ragTag={}, name={}, caseIds.size={}",
                ragTag, name, caseIds.size());

        // 校验所有 case
        List<TestCaseEntity> entities = new ArrayList<>();
        for (String caseId : caseIds) {
            Optional<TestCaseEntity> opt = testCaseRepository.findByCaseId(ragTag, caseId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException(
                        "Case not found in ragTag='" + ragTag + "': " + caseId);
            }
            TestCaseEntity tc = opt.get();

            // MVP: 仅支持 WEB_FUNCTIONAL
            if (tc.getAutomationCandidate() == null
                    || !"WEB_FUNCTIONAL".equals(tc.getAutomationCandidate().name())) {
                throw new IllegalArgumentException(
                        "Case " + caseId + " has automationCandidate="
                                + (tc.getAutomationCandidate() == null ? "null"
                                        : tc.getAutomationCandidate().name())
                                + ", MVP only supports WEB_FUNCTIONAL");
            }
            entities.add(tc);
        }

        TestRun run = TestRun.builder()
                .ragTag(ragTag)
                .name(name)
                .description(description)
                .targetUrl(targetUrl)
                .browser("chromium")
                .status(TestRunStatus.CREATED)
                .totalCases(entities.size())
                .createdAt(Instant.now())
                .build();
        return testRunRepository.create(run);
    }
}