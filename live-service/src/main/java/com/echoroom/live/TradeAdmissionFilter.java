package com.echoroom.live;

import java.io.IOException;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.echoroom.common.api.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs outside the controller/service transaction. Internal service ports must stay private. */
@Component
public class TradeAdmissionFilter extends OncePerRequestFilter {
    private static final Pattern SECKILL = Pattern.compile("/api/v1/activities/(\\d+)/seckill");
    private static final Pattern GIFT = Pattern.compile("/api/v1/live/rooms/(\\d+)/gifts");
    private final TrafficGuard guard;
    private final ObjectMapper mapper;
    public TradeAdmissionFilter(TrafficGuard guard, ObjectMapper mapper) { this.guard = guard; this.mapper = mapper; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var seckill = SECKILL.matcher(req.getRequestURI());
        var gift = GIFT.matcher(req.getRequestURI());
        if (!"POST".equals(req.getMethod()) || (!seckill.matches() && !gift.matches())) {
            chain.doFilter(req, res); return;
        }
        String kind = seckill.matches() ? "seckill" : "gift";
        long scope;
        try { scope = Long.parseLong("seckill".equals(kind) ? seckill.group(1) : gift.group(1)); }
        catch (NumberFormatException ex) { res.sendError(400); return; }
        TrafficGuard.Lease lease;
        try { lease = guard.enter(kind, scope, RequestUserId.required(req)); }
        catch (BusinessException ex) {
            res.setStatus(ex.errorCode() == ErrorCode.RATE_LIMITED ? 429 : ex.errorCode() == ErrorCode.UNAUTHORIZED ? 401 : 503);
            res.setContentType("application/json;charset=UTF-8");
            if (ex instanceof TrafficGuard.Limited limited) res.setHeader("Retry-After", "" + Math.max(1, (limited.retryAfterMs + 999)/1000));
            mapper.writeValue(res.getOutputStream(), ApiResponse.failure(ex.errorCode().code(), ex.getMessage()));
            return;
        }
        try (lease) { chain.doFilter(req, res); }
    }
}
