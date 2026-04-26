package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.service.QueryRewriteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Rewrite 服务实现
 *
 * 支持的策略：
 * 1. Direct Rewrite：直接重写为清晰、完整的查询
 * 2. PRD格式解析：提取"作为...我想要...以便于..."格式中的功能需求
 * 3. Multi-Query Expansion：扩展为多个不同角度的查询
 * 4. HyDE：生成假设性答案，用答案的向量辅助检索
 */
@Slf4j
@Service
public class QueryRewriteServiceImpl implements QueryRewriteService {

    private static final Pattern PRD_PATTERN = Pattern.compile(
            "作为[\\w\\s,，]+[，。,]|我想要[\\w\\s我想要的内容]+[，。,]|以便于[\\w\\s]+[，。,]?"
    );

    @Resource
    private OpenAiChatClient chatClient;

    @Value("${spring.ai.minimax.model:MiniMax-M2.7}")
    private String defaultModel;

    @Override
    public List<String> rewrite(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return List.of();
        }

        String trimmed = userQuery.trim();
        log.info("Query Rewrite处理: {}", trimmed);

        // 策略1：如果是PRD风格，先提取功能需求
        if (isPRDStyle(trimmed)) {
            List<String> prdQueries = extractFromPRD(trimmed);
            if (!prdQueries.isEmpty()) {
                log.info("PRD格式检测到，扩展为 {} 个查询", prdQueries.size());
                return prdQueries;
            }
        }

        // 策略2：短查询扩展（用户输入很简短时）
        if (trimmed.length() < 10) {
            List<String> expanded = expandShortQuery(trimmed);
            if (expanded.size() > 1) {
                log.info("短查询扩展为 {} 个查询", expanded.size());
                return expanded;
            }
        }

        // 策略3：多角度扩展
        List<String> multiQueries = multiQueryExpansion(trimmed);
        if (multiQueries.size() > 1) {
            log.info("多角度扩展为 {} 个查询", multiQueries.size());
            return multiQueries;
        }

        // 默认返回原始查询（可能已重写）
        return List.of(trimmed);
    }

    @Override
    public String rewriteToSingle(String userQuery) {
        List<String> queries = rewrite(userQuery);
        // 返回最短且最具体的一个
        return queries.stream()
                .min((a, b) -> Integer.compare(a.length(), b.length()))
                .orElse(userQuery);
    }

    @Override
    public boolean isPRDStyle(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        // 检测PRD特征模式
        boolean hasAsRole = query.contains("作为") || query.contains("身为") || query.contains("作为管理员") ||
                           query.contains("作为用户") || query.contains("作为系统");
        boolean hasWant = query.contains("我想要") || query.contains("我希望") || query.contains("我想");
        boolean hasPurpose = query.contains("以便于") || query.contains("目的是") || query.contains("为了");

        // PRD风格特征：同时包含"作为"和"想要"
        return hasAsRole && hasWant;
    }

    @Override
    public List<String> extractFromPRD(String prdQuery) {
        List<String> extracted = new ArrayList<>();

        try {
            // 方法1：正则提取关键功能描述
            Matcher matcher = PRD_PATTERN.matcher(prdQuery);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String segment = matcher.group();
                // 清理标点
                segment = segment.replaceAll("[，,。]", "").trim();
                if (segment.length() > 2) {
                    extracted.add(segment);
                }
            }

            // 方法2：提取"我想要"后面的核心动作
            Pattern wantPattern = Pattern.compile("我想要(.{2,20}?)(?:以便于|为了|$)");
            Matcher wantMatcher = wantPattern.matcher(prdQuery);
            while (wantMatcher.find()) {
                String action = wantMatcher.group(1).trim();
                if (action.length() > 1) {
                    extracted.add(action);
                }
            }

            // 方法3：使用LLM提取功能关键词（简单提示）
            if (extracted.isEmpty()) {
                String keywords = extractKeywordsWithLLM(prdQuery);
                if (keywords != null && !keywords.isBlank()) {
                    for (String kw : keywords.split("[,，]")) {
                        kw = kw.trim();
                        if (!kw.isEmpty()) {
                            extracted.add(kw);
                        }
                    }
                }
            }

            // 去重并清理
            List<String> result = extracted.stream().distinct().toList();
            log.debug("从PRD提取的功能点: {}", result);

        } catch (Exception e) {
            log.warn("PRD解析失败: {}", prdQuery, e);
        }

        return extracted;
    }

    @Override
    public String generateHypotheticalAnswer(String userQuery) {
        try {
            String prompt = String.format("""
                    基于以下用户需求，生成一个简短但准确的答案片段（50字以内）：
                    需求：%s

                    要求：
                    1. 假设这是一个常见的技术/业务问题，给出标准答案
                    2. 只输出答案内容，不要解释
                    3. 答案应该包含具体的操作步骤或技术细节
                    """, userQuery);

            StringBuilder answer = new StringBuilder();
            chatClient.stream(new Prompt(prompt, OpenAiChatOptions.builder()
                    .withModel(defaultModel)
                    .withMaxTokens(100)
                    .build()))
                    .subscribe(chunk -> {
                        if (chunk != null && chunk.getResult() != null) {
                            String content = chunk.getResult().getOutput().getContent();
                            if (content != null) {
                                answer.append(content);
                            }
                        }
                    })
                    .dispose();

            // 等待生成完成
            Thread.sleep(1500);

            String result = answer.toString().trim();
            log.info("HyDE生成假设答案: {}", result.length() > 50 ? result.substring(0, 50) + "..." : result);
            return result;

        } catch (Exception e) {
            log.warn("HyDE答案生成失败: {}", userQuery, e);
            return userQuery; // 降级为原始查询
        }
    }

    /**
     * 短查询扩展策略
     */
    private List<String> expandShortQuery(String shortQuery) {
        List<String> expansions = new ArrayList<>();
        expansions.add(shortQuery);

        // 根据短查询的特点进行扩展
        String lower = shortQuery.toLowerCase();

        if (lower.contains("退") || lower.contains("退款") || lower.contains("退货")) {
            expansions.add("申请退款的完整操作流程");
            expansions.add("退款审核处理流程");
            expansions.add("订单退款步骤");
        } else if (lower.contains("增") || lower.contains("添加") || lower.contains("新建")) {
            expansions.add("新增数据的操作方法");
            expansions.add("添加记录流程");
        } else if (lower.contains("删") || lower.contains("删除")) {
            expansions.add("删除数据的操作流程");
            expansions.add("数据删除注意事项");
        } else if (lower.contains("改") || lower.contains("修改") || lower.contains("编辑")) {
            expansions.add("修改信息的操作方法");
            expansions.add("数据编辑更新流程");
        } else if (lower.contains("查") || lower.contains("查询") || lower.contains("搜索")) {
            expansions.add("查询数据的方法和步骤");
            expansions.add("搜索筛选操作流程");
        }

        // 如果扩展后仍然只有1个，说明无法扩展
        if (expansions.size() == 1) {
            return expansions;
        }

        return expansions.stream().distinct().toList();
    }

    /**
     * 多角度查询扩展
     */
    private List<String> multiQueryExpansion(String query) {
        List<String> queries = new ArrayList<>();
        queries.add(query);

        // 添加不同角度的变体
        String prompt = String.format("""
                为以下查询生成3个不同角度的检索变体，每个变体不超过20字：
                原始查询：%s

                要求：
                1. 生成同义词替换变体
                2. 生成口语化表达变体
                3. 生成技术术语变体
                4. 只输出变体，用换行分隔，不要编号
                """, query);

        try {
            StringBuilder response = new StringBuilder();
            chatClient.stream(new Prompt(prompt, OpenAiChatOptions.builder()
                    .withModel(defaultModel)
                    .withMaxTokens(200)
                    .build()))
                    .subscribe(chunk -> {
                        if (chunk != null && chunk.getResult() != null) {
                            String content = chunk.getResult().getOutput().getContent();
                            if (content != null) {
                                response.append(content);
                            }
                        }
                    })
                    .dispose();

            Thread.sleep(1000);

            String result = response.toString();
            for (String line : result.split("\n")) {
                line = line.trim().replaceAll("^[0-9a-zA-Z[\\]()。,、]+[.、]?", "");
                if (!line.isEmpty() && line.length() > 3) {
                    queries.add(line);
                }
            }

        } catch (Exception e) {
            log.warn("多查询扩展失败: {}", query, e);
        }

        return queries.stream().distinct().limit(5).toList();
    }

    /**
     * 使用LLM从PRD中提取关键词
     */
    private String extractKeywordsWithLLM(String prdQuery) {
        try {
            String prompt = String.format("""
                    从以下PRD需求描述中，提取3-5个核心功能关键词，用逗号分隔：
                    %s

                    要求：只输出关键词，用逗号分隔，不要解释
                    """, prdQuery);

            StringBuilder keywords = new StringBuilder();
            chatClient.stream(new Prompt(prompt, OpenAiChatOptions.builder()
                    .withModel(defaultModel)
                    .withMaxTokens(50)
                    .build()))
                    .subscribe(chunk -> {
                        if (chunk != null && chunk.getResult() != null) {
                            String content = chunk.getResult().getOutput().getContent();
                            if (content != null) {
                                keywords.append(content);
                            }
                        }
                    })
                    .dispose();

            Thread.sleep(1000);

            return keywords.toString().trim();

        } catch (Exception e) {
            log.warn("LLM关键词提取失败: {}", prdQuery, e);
            return null;
        }
    }
}