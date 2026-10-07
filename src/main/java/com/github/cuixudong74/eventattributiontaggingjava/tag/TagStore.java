package com.github.cuixudong74.eventattributiontaggingjava.tag;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标签存储：按 (用户, 渠道) 维护标签，合并规则确定，重复写入与并发更新不会留下矛盾标签。
 */
@Component
public class TagStore {

    private final Map<TagKey, Tag> tags = new ConcurrentHashMap<>();

    public TagStore() {
    }

    private static final Comparator<Tag> MERGE_ORDER = Comparator
            .comparingInt(Tag::getPriority)
            .thenComparingLong(Tag::getUpdatedAt)
            .thenComparing(tag -> tag.getSource() == null ? "" : tag.getSource(),
                    Comparator.naturalOrder())
            .thenComparing(Tag::getKey, Comparator.nullsFirst(String::compareTo))
            .thenComparing(Tag::getValue, Comparator.nullsFirst(String::compareTo));

    /**
     * 写入标签并按覆盖规则合并，返回生效后的标签。
     * 规则：优先级高者覆盖；同优先级以较新写入为准；写入时间相同则按来源字典序打破平局。
     * 缺少来源按空字符串参与比较；来源仍相同时继续按标签名和值决胜，保证并发结果唯一且可复现。
     */
    public Tag put(TagKey key, Tag tag) {
        return tags.merge(key, tag, (oldTag, newTag) ->
                MERGE_ORDER.compare(newTag, oldTag) > 0 ? newTag : oldTag);
    }

    public Optional<Tag> get(TagKey key) {
        return Optional.ofNullable(tags.get(key));
    }

    /**
     * @return 该用户在所有渠道上的标签
     */
    public Map<String, Tag> forUser(String userId) {
        Map<String, Tag> result = new HashMap<>();
        tags.forEach((key, tag) -> {
            if (userId.equals(key.getUserId())) {
                result.put(key.getChannel(), tag);
            }
        });
        return result;
    }
}
