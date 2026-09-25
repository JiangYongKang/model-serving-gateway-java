package com.github.highcumontoa.modelservinggatewayjava.batch;

/** 批量执行成功的结果：分数 + 所属批次号。 */
public record BatchOutcome(double score, String batchId) {
}
