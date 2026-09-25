package com.github.highcumontoa.modelservinggatewayjava.tests.support;

import com.github.highcumontoa.modelservinggatewayjava.model.LocalModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 测试用本地模型桩集合：慢执行 / 抛异常 / 返回不可用结果。 */
public final class TestModels {

    private TestModels() {
    }

    public static LocalModel slow(String version, long sleepMs) {
        return new SlowModel(version, sleepMs);
    }

    public static LocalModel error(String version) {
        return new ErrorModel(version);
    }

    public static LocalModel invalidResult(String version) {
        return new InvalidResultModel(version);
    }

    public static class SlowModel implements LocalModel {
        private final String version;
        private final long sleepMs;

        public SlowModel(String version, long sleepMs) {
            this.version = version;
            this.sleepMs = sleepMs;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public boolean supportsContract(String contractVersion) {
            return "1.0".equals(contractVersion);
        }

        @Override
        public List<Double> scoreBatch(List<Map<String, Object>> inputs) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted", e);
            }
            List<Double> scores = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                scores.add(0.5);
            }
            return scores;
        }
    }

    public static class ErrorModel implements LocalModel {
        private final String version;

        public ErrorModel(String version) {
            this.version = version;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public boolean supportsContract(String contractVersion) {
            return "1.0".equals(contractVersion);
        }

        @Override
        public List<Double> scoreBatch(List<Map<String, Object>> inputs) {
            throw new IllegalStateException("simulated internal model failure");
        }
    }

    public static class InvalidResultModel implements LocalModel {
        private final String version;

        public InvalidResultModel(String version) {
            this.version = version;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public boolean supportsContract(String contractVersion) {
            return "1.0".equals(contractVersion);
        }

        @Override
        public List<Double> scoreBatch(List<Map<String, Object>> inputs) {
            // 故意返回与批次大小不符且含 NaN 的结果
            return List.of(0.1, Double.NaN);
        }
    }
}
