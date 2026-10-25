package com.example.mcpgateway;

import java.util.List;
import java.util.Map;

/** 控制台「部署」向导用的常见 MCP Server 模板。 */
final class ServerTemplates {

    private ServerTemplates() {}

    static List<Map<String, Object>> all() {
        return List.of(
                tpl("filesystem", "Filesystem", "读写指定本地目录。适合文档、代码仓库。",
                        "folder", "STDIO", "npx",
                        List.of("-y", "@modelcontextprotocol/server-filesystem", "{{path}}"),
                        List.of(field("path", "允许访问的目录", "/Users/me/docs", false))),
                tpl("memory", "Memory", "跨会话的知识图谱记忆。",
                        "brain", "STDIO", "npx",
                        List.of("-y", "@modelcontextprotocol/server-memory"),
                        List.of()),
                tpl("everything", "Everything（演示）", "官方参考实现，用来验证网关与工具调用。",
                        "spark", "STDIO", "npx",
                        List.of("-y", "@modelcontextprotocol/server-everything"),
                        List.of()),
                tpl("fetch", "Fetch", "抓取网页并转成 Markdown。需要本机有 uvx。",
                        "globe", "STDIO", "uvx",
                        List.of("mcp-server-fetch"),
                        List.of()),
                tpl("git", "Git", "查看本地仓库状态、diff、log。需要 uvx。",
                        "git", "STDIO", "uvx",
                        List.of("mcp-server-git", "--repository", "{{path}}"),
                        List.of(field("path", "仓库路径", "/Users/me/project", false))),
                tpl("time", "Time", "当前时间和时区转换。",
                        "clock", "STDIO", "uvx",
                        List.of("mcp-server-time"),
                        List.of()),
                tpl("playwright", "Playwright", "用浏览器自动化访问页面。",
                        "window", "STDIO", "npx",
                        List.of("-y", "@playwright/mcp@latest"),
                        List.of()),
                tpl("github", "GitHub（远程）", "GitHub 官方远程 MCP。填写 PAT。",
                        "github", "STREAMABLE_HTTP", null, List.of(),
                        List.of(
                                field("url", "MCP URL", "https://api.githubcopilot.com/mcp/", false),
                                field("token", "Personal Access Token", "ghp_...", true))),
                tpl("custom-stdio", "自定义 STDIO", "任意本地命令，例如 node / python 启动的 MCP。",
                        "terminal", "STDIO", "", List.of(),
                        List.of(
                                field("command", "命令", "npx", false),
                                field("args", "参数（空格分隔）", "-y @scope/server", false))),
                tpl("custom-http", "自定义 HTTP", "已在运行的 Streamable HTTP MCP。",
                        "link", "STREAMABLE_HTTP", null, List.of(),
                        List.of(
                                field("url", "Base URL", "http://localhost:3001", false),
                                field("endpoint", "路径", "/mcp", false))),
                tpl("custom-sse", "自定义 SSE", "旧版 SSE 传输的 MCP。",
                        "broadcast", "SSE", null, List.of(),
                        List.of(
                                field("url", "Base URL", "http://localhost:3002", false),
                                field("endpoint", "SSE 路径", "/sse", false)))
        );
    }

    private static Map<String, Object> field(String key, String label, String placeholder, boolean secret) {
        return Map.of("key", key, "label", label, "placeholder", placeholder, "secret", secret);
    }

    private static Map<String, Object> tpl(
            String id,
            String title,
            String description,
            String icon,
            String transport,
            String command,
            List<String> args,
            List<Map<String, Object>> fields) {
        return Map.of(
                "id", id,
                "title", title,
                "description", description,
                "icon", icon,
                "transport", transport,
                "command", command == null ? "" : command,
                "args", args,
                "fields", fields);
    }
}
