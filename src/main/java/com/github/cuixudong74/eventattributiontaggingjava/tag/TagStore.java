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

    /**
     * 生效标签的全序比较器（大者生效）：
     * 优先级高者优先；同优先级以较新写入为准；写入时间相同按来源字典序（缺失来源视为最小，合法参与比较）；
     * 仍相同再依次按键、值字典序，保证任意并发到达顺序下合并结果唯一且可复现。
     */
    private static final Comparator<Tag> EFFECTIVE_ORDER = Comparator
            .comparingInt(Tag::getPriority)
            .thenComparingLong(Tag::getUpdatedAt)
            .thenComparing(Tag::getSource, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Tag::getKey, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Tag::getValue, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final Map<TagKey, Tag> tags = new ConcurrentHashMap<>();

    public TagStore() {
    }

    /**
     * 写入标签并按覆盖规则合并，返回生效后的标签。
     * 规则：优先级高者覆盖；同优先级以较新写入为准；写入时间相同则按来源字典序打破平局
     * （来源缺失按最小处理，不会报错）；仍平局时按键、值字典序定胜负。
     * 合并是交换、可结合的全序取大，且生效者始终是某一次写入的完整标签，
     * 因此任意并发交错下结果唯一、可复现，不会出现字段拼接或矛盾取值。
     */
    public Tag put(TagKey key, Tag tag) {
        return tags.merge(key, tag,
                (oldTag, newTag) -> EFFECTIVE_ORDER.compare(newTag, oldTag) >= 0 ? newTag : oldTag);
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
