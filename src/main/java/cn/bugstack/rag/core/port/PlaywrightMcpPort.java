package cn.bugstack.rag.core.port;

import java.util.List;
import java.util.Map;

/**
 * PlaywrightMcp Port
 *
 * 不直接控制 Chromium - 通过 MCP 协议调用 Microsoft Playwright MCP 服务
 *
 * 职责:
 * - connect() / initialize()
 * - listTools()
 * - callTool(name, args) -> raw result text
 */
public interface PlaywrightMcpPort {

    /**
     * MCP initialize handshake (返回 serverInfo)
     */
    Map<String, Object> initialize();

    /**
     * 列出 Playwright MCP 暴露的工具
     */
    List<String> listTools();

    /**
     * 调用 MCP 工具
     *
     * @param toolName 工具名 (browser_navigate / browser_click / browser_snapshot / ...)
     * @param arguments JSON-RPC arguments
     * @return MCP 工具原始返回(text 形式)
     */
    String callTool(String toolName, Map<String, Object> arguments);

    /**
     * 取最近一次 snapshot 的 A11y tree (parsed)
     * 供 TargetResolver 使用
     */
    Map<String, Object> getLastSnapshot();

    /**
     * 是否已连接
     */
    boolean isConnected();
}