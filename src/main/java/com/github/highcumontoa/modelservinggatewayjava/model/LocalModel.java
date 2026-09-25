package com.github.highcumontoa.modelservinggatewayjava.model;

import java.util.Map;

/** 本地模型实现：全部判定在本地完成。 */
public interface LocalModel {

    /** 模型版本标识。 */
    String version();

    /** 输入契约版本，请求契约不匹配时不得执行。 */
    int contractVersion();

    /** 对单条特征打分，同一输入在同一版本下必须可复现。 */
    double score(Map<String, Double> features);
}
