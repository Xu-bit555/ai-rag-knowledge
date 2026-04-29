package cn.bugstack.rag.controller;

import cn.bugstack.rag.model.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Response.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        return Response.error("400", message);
    }

    /**
     * 处理文件上传大小超限
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Response<Void> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("文件大小超限: {}", e.getMessage());
        return Response.error("400", "文件大小超过限制");
    }

    /**
     * 处理LLM调用异常
     */
    @ExceptionHandler(LLMException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Response<Void> handleLLMException(LLMException e) {
        log.error("LLM调用异常: {}", e.getMessage(), e);
        return Response.error("503", "AI服务暂时不可用: " + e.getMessage());
    }

    /**
     * 处理静态资源不存在（404）
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Response<Void> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return Response.error("404", "资源不存在");
    }

    /**
     * 处理通用异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Response<Void> handleException(Exception e) {
        log.error("系统异常: {}", e.getMessage(), e);
        // 临时返回详细错误信息以便调试
        String detailedMsg = e.getMessage();
        if (detailedMsg != null && detailedMsg.length() > 200) {
            detailedMsg = detailedMsg.substring(0, 200) + "...";
        }
        return Response.error("500", "系统内部错误: " + detailedMsg);
    }

    /**
     * 业务异常
     */
    public static class BusinessException extends RuntimeException {
        private final String code;

        public BusinessException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    /**
     * LLM异常
     */
    public static class LLMException extends RuntimeException {
        public LLMException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
