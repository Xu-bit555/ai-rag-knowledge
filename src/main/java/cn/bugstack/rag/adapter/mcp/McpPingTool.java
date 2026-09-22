package cn.bugstack.rag.adapter.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
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
 *
 * Phase 2 hotfix #5: 必须用 ObjectProvider + 构造注入 (不能 @Autowired @Lazy 字段注入),
 *   因为 MethodToolCallbackProvider 是 final class, @Lazy 字段注入会让 Spring 生成 CGLIB
 *   代理 → Cannot subclass final class 报错.
 */
@Slf4j
@Component
public class McpPingTool {

    /**
     * Phase 2 A7: 用 ObjectProvider 动态取 callbacks, 避免循环依赖 + final class 代理问题
     */
    private final ObjectProvider<MethodToolCallbackProvider> toolCallbackProviderProvider;

    public McpPingTool(ObjectProvider<MethodToolCallbackProvider> toolCallbackProviderProvider) {
        this.toolCallbackProviderProvider = toolCallbackProviderProvider;
    }

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
        MethodToolCallbackProvider provider = toolCallbackProviderProvider.getIfAvailable();
        List<ToolCallback> callbacks = provider != null ? provider.getToolCallbacks() : null;
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
