package cn.bugstack.rag.adapter;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis适配器
 */
@Slf4j
@Component
public class RedisAdapter {

    private static final String RAG_TAG_SET_KEY = "ragTag";

    private final RedissonClient redissonClient;

    public RedisAdapter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 获取所有RAG标签
     * @return 标签列表
     */
    public List<String> getAllRagTags() {
        try {
            RSet<String> elements = redissonClient.getSet(RAG_TAG_SET_KEY);
            return elements.readAll();
        } catch (Exception e) {
            log.warn("获取RAG标签失败，返回空列表: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 添加RAG标签
     * @param tag 标签
     */
    public void addRagTag(String tag) {
        RSet<String> elements = redissonClient.getSet(RAG_TAG_SET_KEY);
        if (!elements.contains(tag)) {
            elements.add(tag);
            log.info("添加RAG标签: {}", tag);
        }
    }

    /**
     * 检查标签是否存在
     * @param tag 标签
     * @return 是否存在
     */
    public boolean tagExists(String tag) {
        RSet<String> elements = redissonClient.getSet(RAG_TAG_SET_KEY);
        return elements.contains(tag);
    }

    /**
     * 删除RAG标签
     * @param tag 标签
     */
    public void removeRagTag(String tag) {
        RSet<String> elements = redissonClient.getSet(RAG_TAG_SET_KEY);
        elements.remove(tag);
        log.info("删除RAG标签: {}", tag);
    }

}
