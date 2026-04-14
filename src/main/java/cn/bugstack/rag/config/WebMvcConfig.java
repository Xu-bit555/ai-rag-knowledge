package cn.bugstack.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web静态资源配置
 * 静态文件已放在 src/main/resources/static/ 目录
 * Spring Boot 会自动从该目录提供静态文件
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    // Spring Boot 默认从 classpath:/static/ 提供静态文件，无需额外配置
}
