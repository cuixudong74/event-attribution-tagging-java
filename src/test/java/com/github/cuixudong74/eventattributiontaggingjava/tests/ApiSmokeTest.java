package com.github.cuixudong74.eventattributiontaggingjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** REST 端到端冒烟：摄入 -> 归因查询 -> 对账 -> 越权拒绝 -> 非法配置拒绝 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeTest {
    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;

    private String url(String path) { return "http://localhost:" + port + path; }

    @Test
    void endToEndFlow() {
        // 摄入点击与转化
        postEvent("smk-t1", "CLICK", "uSmoke", "dSmoke", "chA",
                "2025-01-01T00:00:00Z", true);
        postEvent("smk-cv1", "CONVERSION", "uSmoke", "dSmoke", null,
                "2025-01-02T00:00:00Z", true);
        // 未授权事件：不进归因但计入总量
        postEvent("smk-t2", "CLICK", "uSmoke", "dSmoke", "chB",
                "2025-01-01T12:00:00Z", false);

        ResponseEntity<Map> attr = rest.getForEntity(
                url("/api/attributions/smk-cv1"), Map.class);
        assertEquals(HttpStatus.OK, attr.getStatusCode());
        assertTrue(attr.getBody().toString().contains("smk-t1"));

        Map recon = rest.getForObject(url("/api/reconciliation"), Map.class);
        assertTrue(((Number) recon.get("nonConsentedEvents")).longValue() >= 1);

        // 越权读取用户明细 -> 403
        ResponseEntity<String> denied = rest.getForEntity(
                url("/api/users/uSmoke/events"), String.class);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());

        // 非法配置 -> 400 且带原因
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> badCfg = rest.exchange(url("/api/config"), HttpMethod.POST,
                new HttpEntity<>("{\"modelType\":\"TIME_DECAY\",\"attributionWindow\":\"P30D\"}",
                        headers), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, badCfg.getStatusCode());
        assertNotNull(badCfg.getBody().get("error"));
    }

    private void postEvent(String id, String type, String userId, String deviceId,
                           String channel, String time, boolean consented) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"eventId":"%s","type":"%s","userId":%s,"deviceId":%s,"channel":%s,
                 "campaignId":"c1","eventTime":"%s","consented":%s}
                """.formatted(id, type,
                userId == null ? null : "\"" + userId + "\"",
                deviceId == null ? null : "\"" + deviceId + "\"",
                channel == null ? null : "\"" + channel + "\"",
                time, consented);
        ResponseEntity<Map> resp = rest.exchange(url("/api/events"), HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }
}
