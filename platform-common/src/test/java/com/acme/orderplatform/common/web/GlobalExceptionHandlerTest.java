package com.acme.orderplatform.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @RestController
    static class Boom {
        @GetMapping("/missing")
        String missing() {
            throw new ResourceNotFoundException("Order", "42");
        }

        @GetMapping("/rule")
        String rule() {
            throw new BusinessRuleViolationException("LIMIT_EXCEEDED", "Too much");
        }

        @GetMapping("/crash")
        String crash() {
            throw new IllegalStateException("db password is hunter2");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Boom())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void notFoundIsProblemDetailWithCorrelationId() throws Exception {
        mvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void businessRuleIs422WithStableCode() throws Exception {
        mvc.perform(get("/rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));
    }

    @Test
    void unexpectedErrorDoesNotLeakInternals() throws Exception {
        mvc.perform(get("/crash"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))));
    }
}
