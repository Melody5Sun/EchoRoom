package com.streamhub.live;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class TradeAdmissionFilterTest {
    @Test void limitedRequestNeverReachesBusinessChain() throws Exception {
        var guard=mock(TrafficGuard.class);
        when(guard.enter("seckill",7,42)).thenThrow(new TrafficGuard.Limited(2100));
        var filter=new TradeAdmissionFilter(guard,new ObjectMapper().findAndRegisterModules());
        var request=new MockHttpServletRequest("POST","/api/v1/activities/7/seckill");
        request.addHeader("X-User-Id","42"); var response=new MockHttpServletResponse(); var chain=mock(FilterChain.class);
        filter.doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(429); assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        verifyNoInteractions(chain);
    }
}
