package com.github.cuixudong74.eventattributiontaggingjava.tests;

import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionConfigLoader;
import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionModel;
import com.github.cuixudong74.eventattributiontaggingjava.config.AttributionProperties;
import com.github.cuixudong74.eventattributiontaggingjava.event.Event;
import com.github.cuixudong74.eventattributiontaggingjava.event.EventType;
import com.github.cuixudong74.eventattributiontaggingjava.facade.AttributionService;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.AuthorizationService;
import com.github.cuixudong74.eventattributiontaggingjava.privacy.ConsentService;
import com.github.cuixudong74.eventattributiontaggingjava.snapshot.SnapshotHolder;
import com.github.cuixudong74.eventattributiontaggingjava.tag.TagStore;

import java.time.Duration;
import java.util.Map;

/**
 * 测试支撑：构造独立的归因服务实例与事件。
 */
public final class AttributionTestSupport {

    private AttributionTestSupport() {
    }

    public static Event event(String id, EventType type, String userId, String deviceId,
                              String channel, long timestamp) {
        return new Event(id, type, userId, deviceId, channel, timestamp,
                System.currentTimeMillis(), Map.of());
    }

    public static AttributionService service() {
        return service(AttributionModel.LAST_TOUCH, Duration.ofDays(7), Duration.ofHours(24));
    }

    public static AttributionService service(AttributionModel model, Duration window) {
        return service(model, window, Duration.ofHours(24));
    }

    public static AttributionService service(AttributionModel model, Duration window, Duration halfLife) {
        AttributionProperties properties = new AttributionProperties();
        properties.setModel(model);
        properties.setWindow(window);
        properties.setHalfLife(halfLife);
        AttributionConfigLoader loader = new AttributionConfigLoader(properties);
        return new AttributionService(new SnapshotHolder(), loader, new ConsentService(),
                new AuthorizationService(), new TagStore());
    }
}
