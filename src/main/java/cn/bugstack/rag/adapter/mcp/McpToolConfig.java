package cn.bugstack.rag.adapter.mcp;

import cn.bugstack.rag.adapter.mcp.tool.AdoptCasesTool;
import cn.bugstack.rag.adapter.mcp.tool.CreateTestRunTool;
import cn.bugstack.rag.adapter.mcp.tool.DiagnoseFailureTool;
import cn.bugstack.rag.adapter.mcp.tool.GenerateCasesTool;
import cn.bugstack.rag.adapter.mcp.tool.GetTestRunTool;
import cn.bugstack.rag.adapter.mcp.tool.RecordCaseAttemptTool;
import cn.bugstack.rag.adapter.mcp.tool.SearchKnowledgeTool;
import cn.bugstack.rag.adapter.mcp.tool.SearchTestCasesTool;
import cn.bugstack.rag.adapter.mcp.tool.ValidateCaseTool;
import cn.bugstack.rag.adapter.mcp.tool.VectorMirrorTool;   // Phase 3
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Tool Wiring Configuration
 *
 * Spring AI 1.1.x: 使用 MethodToolCallbackProvider 将 @Tool 方法注册为 ToolCallback
 * spring-ai-starter-mcp-server-webmvc 自动将 ToolCallback bean 暴露为 MCP 工具
 *
 * Phase 2 完成: 9 个 MCP Tools
 *
 * 业务链:
 *   search_knowledge / search_test_cases  (前置 RAG)
 *     ↓
 *   generate_cases → validate_case → adopt_cases
 *     ↓
 *   create_test_run
 *     ↓
 *   record_case_attempt  (× N 次 retry)
 *     ↓
 *   diagnose_failure (可选)
 *     ↓
 *   get_test_run  (查询报告)
 */
@Slf4j
@Configuration
public class McpToolConfig {

    /**
     * Phase 2 A5: 删除 11 个 TOOL_* 常量.
     *   单一权威源 = 每个 Tool 类的 @Tool(name = "onecase_xxx").
     *   McpPingTool.serverInfo() 动态从 MethodToolCallbackProvider.getToolCallbacks() 数.
     */

    /**
     * Phase 2 完成: 9 个核心 Tools + 2 个基础 Tools = 11 个
     *
     * 最终 OneCase MCP 应暴露:
     * 1. onecase_ping
     * 2. onecase_server_info
     * 3. onecase_search_knowledge
     * 4. onecase_search_test_cases
     * 5. onecase_generate_cases
     * 6. onecase_validate_case
     * 7. onecase_adopt_cases
     * 8. onecase_create_test_run
     * 9. onecase_record_case_attempt
     * 10. onecase_diagnose_failure
     * 11. onecase_get_test_run
     */
    @Bean
    public MethodToolCallbackProvider mcpToolCallbackProvider(
            McpPingTool mcpPingTool,
            SearchKnowledgeTool searchKnowledgeTool,
            SearchTestCasesTool searchTestCasesTool,
            GenerateCasesTool generateCasesTool,
            ValidateCaseTool validateCaseTool,
            AdoptCasesTool adoptCasesTool,
            CreateTestRunTool createTestRunTool,
            RecordCaseAttemptTool recordCaseAttemptTool,
            DiagnoseFailureTool diagnoseFailureTool,
            GetTestRunTool getTestRunTool,
            VectorMirrorTool vectorMirrorTool) {       // Phase 3

        log.info("注册 MCP 工具: 13 个 (2 基础 + 9 业务 + 2 镜像管理)");
        return MethodToolCallbackProvider.builder()
                .toolObjects(
                        mcpPingTool,
                        searchKnowledgeTool,
                        searchTestCasesTool,
                        generateCasesTool,
                        validateCaseTool,
                        adoptCasesTool,
                        createTestRunTool,
                        recordCaseAttemptTool,
                        diagnoseFailureTool,
                        getTestRunTool,
                        vectorMirrorTool                // Phase 3
                )
                .build();
    }
}