package com.github.cuixudong74.eventattributiontaggingjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 接口集成测试：摄入、对账、快照、鉴权、标签与配置热加载。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttributionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullFlowOverHttp() throws Exception {
        // 隐私授权
        mockMvc.perform(post("/api/consent/u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consented").value(true));

        String events = """
                [{"eventId":"i1","type":"IMPRESSION","userId":"u1","channel":"web","timestamp":1000},
                 {"eventId":"c1","type":"CLICK","userId":"u1","channel":"mobile","timestamp":2000},
                 {"eventId":"cv1","type":"CONVERSION","userId":"u1","channel":"web","timestamp":3000}]
                """;
        // 首次摄入：3 条全部接收
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(events))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(3))
                .andExpect(jsonPath("$.duplicates").value(0));

        // 重复上报：3 条全部记为重复，不计入归因
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(events))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(3));

        // 总量对账：恒等式成立
        mockMvc.perform(get("/api/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.totalReceived").value(6))
                .andExpect(jsonPath("$.duplicates").value(3))
                .andExpect(jsonPath("$.linked").value(3));

        // 快照自洽：末次触点 c1
        mockMvc.perform(get("/api/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.cv1.credits[0].eventId").value("c1"))
                .andExpect(jsonPath("$.results.cv1.credits[0].weight").value(1.0));

        // 用户级明细：本人可查
        mockMvc.perform(get("/api/users/u1").param("actor", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversionEventId").value("cv1"));

        // 越权读取他人明细 -> 403
        mockMvc.perform(get("/api/users/u1").param("actor", "u2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        // 非法事件类型 -> 400
        String bad = """
                [{"eventId":"x1","type":"BOGUS","userId":"u1","channel":"web","timestamp":9999}]
                """;
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        // 标签写入与读取
        mockMvc.perform(post("/api/tags").param("actor", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"channel\":\"web\",\"key\":\"seg\","
                                + "\"value\":\"a\",\"priority\":1,\"source\":\"s\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("a"));
        mockMvc.perform(get("/api/tags").param("actor", "u1")
                        .param("userId", "u1").param("channel", "web").param("key", "seg"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("a"));

        // 配置查看与热加载
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("LAST_TOUCH"));
        mockMvc.perform(post("/api/config/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("LAST_TOUCH"));

        // 修订历史可追溯
        mockMvc.perform(get("/api/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.reason == 'INCREMENTAL')]").exists());
    }
}
