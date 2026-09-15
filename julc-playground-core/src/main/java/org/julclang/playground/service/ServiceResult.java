package org.julclang.playground.service;

/**
 * Transport-neutral result of a playground operation: the HTTP status the server responds with, the JSON body,
 * and, for internal failures, the cause so the transport can log it.
 */
public record ServiceResult<T>(int status, T body, Throwable failure) {

    public static <T> ServiceResult<T> ok(T body) {
        return new ServiceResult<>(200, body, null);
    }

    public static <T> ServiceResult<T> status(int status, T body) {
        return new ServiceResult<>(status, body, null);
    }

    public static <T> ServiceResult<T> failed(int status, T body, Throwable failure) {
        return new ServiceResult<>(status, body, failure);
    }
}
