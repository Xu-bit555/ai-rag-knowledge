package cn.bugstack.rag.adapter.web;

import cn.bugstack.rag.core.usecase.GetTestRunUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * TestRun Report REST API (人类 UI / CLI 用)
 *
 * 与 MCP 的 onecase_get_test_run 区别:
 * - MCP 供 Agent 调用,包含 Tool 错误语义
 * - REST 供人类/UI 调用,纯 JSON 输出
 *
 * 不重复业务,直接 delegate 到 GetTestRunUseCase
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/test-runs")
@RequiredArgsConstructor
public class RunReportController {

    private final GetTestRunUseCase getTestRunUseCase;

    @GetMapping("/{runId}")
    public Map<String, Object> getRun(@PathVariable String runId) {
        log.info("REST getRun: runId={}", runId);
        return getTestRunUseCase.execute(runId);
    }
}