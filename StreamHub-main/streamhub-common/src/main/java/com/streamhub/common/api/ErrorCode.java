package com.streamhub.common.api;

public enum ErrorCode {
    INVALID_ARGUMENT("COMMON-400", "请求参数不合法"),
    UNAUTHORIZED("COMMON-401", "未登录或登录已过期"),
    FORBIDDEN("COMMON-403", "没有权限执行此操作"),
    NOT_FOUND("COMMON-404", "资源不存在"),
    RATE_LIMITED("COMMON-429", "请求过于频繁"),
    UNAVAILABLE("COMMON-503", "服务繁忙，请稍后重试"),
    INTERNAL_ERROR("COMMON-500", "系统内部错误");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
