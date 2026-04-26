package cn.bugstack.rag.infrastructure.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容Hash计算工具
 * <p>
 * 使用SHA-256算法计算文档内容指纹
 * - 计算速度极快，即使只改一个标点符号hash也会完全不同
 * - 适合检测文档是否发生变化
 */
@Slf4j
public class ContentHashCalculator {

    private static final String SHA256 = "SHA-256";

    /**
     * 计算字节数组的SHA-256哈希
     */
    public static String computeHash(byte[] content) {
        if (content == null) {
            content = new byte[0];
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA256);
            byte[] hashBytes = digest.digest(content);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 计算字符串的SHA-256哈希
     */
    public static String computeHash(String content) {
        if (content == null) {
            content = "";
        }
        return computeHash(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算文件的SHA-256哈希
     */
    public static String computeFileHash(Path path) {
        try {
            byte[] fileBytes = Files.readAllBytes(path);
            return computeHash(fileBytes);
        } catch (IOException e) {
            log.error("读取文件计算Hash失败, path: {}", path, e);
            throw new RuntimeException("文件Hash计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算Resource内容的SHA-256哈希
     */
    public static String computeResourceHash(Resource resource) {
        try {
            byte[] content = resource.getContentAsByteArray();
            return computeHash(content);
        } catch (IOException e) {
            log.error("读取Resource计算Hash失败", e);
            throw new RuntimeException("Resource Hash计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 字节数组转十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
