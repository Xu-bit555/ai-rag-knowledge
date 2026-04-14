package cn.bugstack.rag.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 测试步骤模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestStep implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 步骤序号
     */
    private Integer order;

    /**
     * 操作类型
     */
    private MobileAction action;

    /**
     * 目标元素描述
     */
    private String target;

    /**
     * 输入值（input操作时使用）
     */
    private String value;

    /**
     * 预期状态
     */
    private String expectedState;

    /**
     * 移动端操作类型枚举
     */
    public enum MobileAction {
        CLICK,
        INPUT,
        SWIPE_UP,
        SWIPE_DOWN,
        SWIPE_LEFT,
        SWIPE_RIGHT,
        SCREENSHOT,
        OPEN_APP,
        BACK,
        HOME,
        RECENTS,
        WAIT,
        LONG_PRESS
    }

    /**
     * 转换为可执行的动作描述
     */
    public String toExecutableDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(order).append(". ");
        sb.append(action.name()).append(" ");
        if (target != null) {
            sb.append("[").append(target).append("]");
        }
        if (value != null) {
            sb.append(" with value: ").append(value);
        }
        return sb.toString();
    }

}
