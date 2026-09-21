package cn.bugstack.rag.adapter.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP Ping Tool - OneCase 2.0 MCP server 健康检查
 *
 * Spring AI 1.1.x 使用 @Tool 注解 + MethodToolCallbackProvider
 *
 * Phase 2 A7 修复: mcp_tools_count 从 MethodToolCallbackProvider.getToolCallbacks().size()
 *   动态获取, 不再写死.
 */
@Slf4j
@Component
public class McpPingTool {

    /**
     * Phase 2 A7: 注入 MethodToolCallbackProvider, 动态统计 tool 数.
     * 用 @Lazy + ObjectProvider 防循环依赖 (provider 依赖 tool, tool 依赖 provider).
     */
    @Autowired
    @Lazy
    private MethodToolCallbackProvider toolCallbackProvider;

    @Tool(name = "onecase_ping",
          description = "OneCase MCP server health check. Returns 'pong' with current server timestamp.")
    public Map<String, Object> ping(
            @ToolParam(description = "Optional message to echo", required = false) String message) {
        log.info("MCP ping called with message: {}", message);
        return Map.of(
                "status", "pong",
                "timestamp", System.currentTimeMillis(),
                "echo", message != null ? message : "",
                "server", "onecase-mcp",
                "version", "2.0.0"
        );
    }

    @Tool(name = "onecase_server_info",
          description = "Return OneCase server version and capabilities. Use to discover server features.")
    public Map<String, Object> serverInfo() {
        List<ToolCallback> callbacks = toolCallbackProvider.getToolCallbacks();
        int toolCount = callbacks != null ? callbacks.size() : 0;
        log.debug("serverInfo called, toolCount={}", toolCount);

        return Map.of(
                "name", "onecase-mcp",
                "version", "2.0.0",
                "spring-ai-version", "1.1.8",
                "spring-boot-version", "3.5.16",
                "capabilities", Map.of(
                        "test_case_generation", true,
                        "rag_search", true,
                        "mcp_tools_count", toolCount,
                        "embedding_model", "BAAI/bge-m3",
                        "llm_model", "MiniMax-M3"
                )
        );
    }
}
