package com.github.highcumontoa.modelservinggatewayjava.model;

import java.util.Map;
import java.util.TreeMap;

/**
 * 确定性线性打分模型：score = sigmoid(bias + Σ w_i * x_i)。
 * 权重在构造时固定，无任何外部依赖与随机性，
 * 因此同一输入在同一版本下的结论完全可复现。
 */
public class LinearScoringModel implements LocalModel {

    private final String version;
    private final int contractVersion;
    private final Map<String, Double> weights;
    private final double bias;

    public LinearScoringModel(String version, int contractVersion,
                              Map<String, Double> weights, double bias) {
        this.version = version;
        this.contractVersion = contractVersion;
        // 排序拷贝，保证迭代顺序确定，与输入 Map 实现无关
        this.weights = new TreeMap<>(weights);
        this.bias = bias;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public int contractVersion() {
        return contractVersion;
    }

    @Override
    public double score(Map<String, Double> features) {
        double sum = bias;
        for (Map.Entry<String, Double> w : weights.entrySet()) {
            Double x = features.get(w.getKey());
            if (x == null || Double.isNaN(x)) {
                // 契约内特征缺失：按 0 处理，保持确定性
                continue;
            }
            sum += w.getValue() * x;
        }
        return sigmoid(sum);
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }
}
