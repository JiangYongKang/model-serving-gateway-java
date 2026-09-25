package com.github.highcumontoa.modelservinggatewayjava.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 本地模型实现：按批次执行，全部计算在本地完成。 */
public interface LocalModel {

    String version();

    /** 契约版本必须精确匹配，不做隐式升降级。 */
    boolean supportsContract(String contractVersion);

    /** 输入契约校验；返回非空描述表示输入不兼容。 */
    default Optional<String> validateInputs(Map<String, Object> inputs) {
        return Optional.empty();
    }

    List<Double> scoreBatch(List<Map<String, Object>> inputs);
}
