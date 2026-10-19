package com.ainexus.gateway.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 直接向 HttpServletResponse 写 OpenAI 兼容错误报文。
 * 用于拦截器(鉴权失败)与转发层(上游不可达)等"还未进入/已接管 MVC"的场景。
 * 报文结构: {"error": {"message": "...", "type": "...", "code": "..."}}
 */
public final class ErrorResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ErrorResponses() {
    }

    public static void write(HttpServletResponse response, int status, String type, String code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return; // 响应已开始写出, 无法改写状态码
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        error.put("code", code);
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(Map.of("error", error)));
        response.getWriter().flush();
    }
}