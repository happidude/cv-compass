package com.cvcompass.backend.web;

/** Optional envelope for endpoints that benefit from a data property. */
public record ApiResponse<T>(T data) {
}
