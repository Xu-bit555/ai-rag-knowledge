package cn.bugstack.rag.model.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Response<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String SUCCESS_CODE = "0000";
    public static final String ERROR_CODE = "9999";

    private String code;
    private String info;
    private T data;

    public static <T> Response<T> ok(T data) {
        return Response.<T>builder()
                .code(SUCCESS_CODE)
                .info("调用成功")
                .data(data)
                .build();
    }

    public static <T> Response<T> ok() {
        return ok(null);
    }

    public static <T> Response<T> error(String code, String info) {
        return Response.<T>builder()
                .code(code)
                .info(info)
                .build();
    }

    public static <T> Response<T> error(String info) {
        return error(ERROR_CODE, info);
    }

}
