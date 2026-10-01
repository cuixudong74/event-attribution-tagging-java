package com.github.cuixudong74.eventattributiontaggingjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 来自 application.properties 的可配置项：
 * <pre>
 * attribution.model=LAST_TOUCH
 * attribution.window=7d
 * attribution.half-life=24h
 * attribution.touch-types=IMPRESSION,CLICK
 * </pre>
 */
@ConfigurationProperties(prefix = "attribution")
public class AttributionProperties {

    private AttributionModel model = AttributionModel.LAST_TOUCH;
    private Duration window = Duration.ofDays(7);
    private Duration halfLife = Duration.ofHours(24);
    private List<String> touchTypes = new ArrayList<>(List.of("IMPRESSION", "CLICK"));

    public AttributionModel getModel() {
        return model;
    }

    public void setModel(AttributionModel model) {
        this.model = model;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public Duration getHalfLife() {
        return halfLife;
    }

    public void setHalfLife(Duration halfLife) {
        this.halfLife = halfLife;
    }

    public List<String> getTouchTypes() {
        return touchTypes;
    }

    public void setTouchTypes(List<String> touchTypes) {
        this.touchTypes = touchTypes;
    }

    public AttributionConfig toConfig() {
        return new AttributionConfig(model, window, halfLife, touchTypes);
    }
}
