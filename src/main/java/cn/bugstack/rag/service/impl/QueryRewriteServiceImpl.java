package cn.bugstack.rag.service.impl;

import cn.bugstack.rag.service.QueryRewriteService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query Rewrite 服务实现
 *
 * Spring AI 1.1.x: ChatModel (不是 ChatClient)
 */
@Slf4j
@Service
public class QueryRewriteServiceImpl implements QueryRewriteService {

    private static final Pattern PRD_PATTERN = Pattern.compile(
            "作为[\\w\\s,，]+[，。,]|我想要[\\w\\s我想要的内容]+[，。,]|以便于[\\w\\s]+[，。,]?"
    );

    @Resource
    private ChatModel chatModel;

    @Override
    public List<String> rewrite(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return List.of();
        }

        String trimmed = userQuery.trim();
        log.info("Query Rewrite处理: {}", trimmed);

        if (isPRDStyle(trimmed)) {
            List<String> prdQueries = extractFromPRD(trimmed);
            if (!prdQueries.isEmpty()) {
                log.info("PRD格式检测到，扩展为 {} 个查询", prdQueries.size());
                return prdQueries;
            }
        }

        if (trimmed.length() < 10) {
            List<String> expanded = expandShortQuery(trimmed);
            if (expanded.size() > 1) {
                log.info("短查询扩展为 {} 个查询", expanded.size());
                return expanded;
            }
        }

        List<String> multiQueries = multiQueryExpansion(trimmed);
        if (multiQueries.size() > 1) {
            log.info("多角度扩展为 {} 个查询", multiQueries.size());
            return multiQueries;
        }

        return List.of(trimmed);
    }

    @Override
    public String rewriteToSingle(String userQuery) {
        List<String> queries = rewrite(userQuery);
        return queries.stream()
                .min((a, b) -> Integer.compare(a.length(), b.length()))
                .orElse(userQuery);
    }

    @Override
    public boolean isPRDStyle(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        boolean hasAsRole = query.contains("作为") || query.contains("身为") || query.contains("作为管理员") ||
                           query.contains("作为用户") || query.contains("作为系统");
        boolean hasWant = query.contains("我想要") || query.contains("我希望") || query.contains("我想");
        boolean hasPurpose = query.contains("以便于") || query.contains("目的是") || query.contains("为了");

        return hasAsRole && hasWant;
    }

    @Override
    public List<String> extractFromPRD(String prdQuery) {
        List<String> extracted = new ArrayList<>();

        try {
            Matcher matcher = PRD_PATTERN.matcher(prdQuery);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String segment = matcher.group();
                segment = segment.replaceAll("[，,。]", "").trim();
                if (segment.length() > 2) {
                    extracted.add(segment);
                }
            }

            Pattern wantPattern = Pattern.compile("我想要(.{2,20}?)(?:以便于|为了|$)");
            Matcher wantMatcher = wantPattern.matcher(prdQuery);
            while (wantMatcher.find()) {
                String action = wantMatcher.group(1).trim();
                if (action.length() > 1) {
                    extracted.add(action);
                }
            }

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

            String answer = simpleCall(prompt);
            String result = answer != null ? answer.trim() : userQuery;
            log.info("HyDE生成假设答案: {}", result.length() > 50 ? result.substring(0, 50) + "..." : result);
            return result;

        } catch (Exception e) {
            log.warn("HyDE答案生成失败: {}", userQuery, e);
            return userQuery;
        }
    }

    private List<String> expandShortQuery(String shortQuery) {
        List<String> expansions = new ArrayList<>();
        expansions.add(shortQuery);

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

        if (expansions.size() == 1) {
            return expansions;
        }

        return expansions.stream().distinct().toList();
    }

    private List<String> multiQueryExpansion(String query) {
        List<String> queries = new ArrayList<>();
        queries.add(query);

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
            String response = simpleCall(prompt);

            if (response != null) {
                for (String line : response.split("\n")) {
                    line = line.trim().replaceAll("^[0-9a-zA-Z[\\]()。,、]+[.、]?", "");
                    if (!line.isEmpty() && line.length() > 3) {
                        queries.add(line);
                    }
                }
            }

        } catch (Exception e) {
            log.warn("多查询扩展失败: {}", query, e);
        }

        return queries.stream().distinct().limit(5).toList();
    }

    private String extractKeywordsWithLLM(String prdQuery) {
        try {
            String prompt = String.format("""
                    从以下PRD需求描述中，提取3-5个核心功能关键词，用逗号分隔：
                    %s

                    要求：只输出关键词，用逗号分隔，不要解释
                    """, prdQuery);

            String keywords = simpleCall(prompt);
            return keywords != null ? keywords.trim() : null;

        } catch (Exception e) {
            log.warn("LLM关键词提取失败: {}", prdQuery, e);
            return null;
        }
    }

    private String simpleCall(String prompt) {
        ChatResponse response = chatModel.call(new Prompt(prompt));
        AssistantMessage msg = response.getResult().getOutput();
        return msg != null ? msg.getText() : null;
    }
}