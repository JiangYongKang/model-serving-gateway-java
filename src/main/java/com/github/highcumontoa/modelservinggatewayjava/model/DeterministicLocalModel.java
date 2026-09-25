package com.github.highcumontoa.modelservinggatewayjava.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 确定性本地模型。
 *
 * <p>分数由 (版本盐值 + 输入的规范化序列化) 的 SHA-256 哈希导出，落在 [0,1)：
 * <ul>
 *   <li>同版本 + 同输入永远得到同一分数（可复现）；</li>
 *   <li>不同版本盐值不同，相同输入在不同版本下分数可不同；</li>
 *   <li>Map 的 key 排序后序列化，输入字段顺序不影响结果；</li>
 *   <li>全部计算在本地完成，不依赖任何外部服务。</li>
 * </ul>
 */
public class DeterministicLocalModel implements LocalModel {

    private static final Logger log = LoggerFactory.getLogger(DeterministicLocalModel.class);

    private final String version;
    private final String supportedContract;
    private final String salt;
    private final Set<String> requiredFields;

    public DeterministicLocalModel(String version, String supportedContract, String salt) {
        this(version, supportedContract, salt, Set.of());
    }

    public DeterministicLocalModel(String version, String supportedContract, String salt,
                                   Set<String> requiredFields) {
        this.version = version;
        this.supportedContract = supportedContract;
        this.salt = salt;
        this.requiredFields = Set.copyOf(requiredFields);
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public boolean supportsContract(String contractVersion) {
        return supportedContract.equals(contractVersion);
    }

    @Override
    public Optional<String> validateInputs(Map<String, Object> inputs) {
        if (inputs == null) {
            return Optional.of("inputs must not be null");
        }
        for (String field : requiredFields) {
            Object value = inputs.get(field);
            if (value == null) {
                return Optional.of("required field '" + field + "' is missing for contract "
                        + supportedContract + " of model " + version);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Double> scoreBatch(List<Map<String, Object>> inputs) {
        List<Double> scores = new ArrayList<>(inputs.size());
        for (Map<String, Object> input : inputs) {
            scores.add(scoreOne(input));
        }
        log.info("MODEL_BATCH version={} batchSize={} scores={}", version, inputs.size(), scores);
        return scores;
    }

    private double scoreOne(Map<String, Object> input) {
        String canonical = salt + "|" + canonicalize(input);
        byte[] digest = sha256(canonical.getBytes(StandardCharsets.UTF_8));
        long h = 0;
        for (int i = 0; i < 8; i++) {
            h = (h << 8) | (digest[i] & 0xffL);
        }
        long magnitude = h & Long.MAX_VALUE;
        return (magnitude % 10_000L) / 10_000.0;
    }

    public static String canonicalize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(e.getKey()).append('=').append(canonicalize(e.getValue()));
                first = false;
            }
            return sb.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(canonicalize(item));
                first = false;
            }
            return sb.append(']').toString();
        }
        return value.getClass().getSimpleName() + "(" + value + ")";
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
