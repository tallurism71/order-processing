package com.acme.orderplatform.common.tracing;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdTest {

    @Test
    void keepsSafeCallerSuppliedId() {
        assertThat(CorrelationId.sanitizeOrGenerate("abc-123_X.y")).isEqualTo("abc-123_X.y");
    }

    @Test
    void replacesUnsafeIdToPreventLogInjection() {
        assertThat(CorrelationId.sanitizeOrGenerate("evil\nFAKE LOG LINE")).doesNotContain("\n").hasSize(36);
        assertThat(CorrelationId.sanitizeOrGenerate(null)).hasSize(36);
    }

    @Test
    void filterEchoesIdOnResponseAndClearsMdc() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationId.HEADER, "req-42");
        var response = new MockHttpServletResponse();

        new CorrelationIdFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("req-42");
        assertThat(org.slf4j.MDC.get(CorrelationId.MDC_KEY)).isNull();
    }
}
