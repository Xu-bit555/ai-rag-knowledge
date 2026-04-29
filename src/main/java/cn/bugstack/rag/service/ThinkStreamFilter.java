package cn.bugstack.rag.service;

/**
 * 思考内容过滤器 - 用于移除AI思考过程
 */
public interface ThinkStreamFilter {

    String filterThinkContent(String s);

    String stripThink(String input);

    String apply(String input);
}