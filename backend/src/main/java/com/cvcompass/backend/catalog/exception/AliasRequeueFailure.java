package com.cvcompass.backend.catalog.exception;

public class AliasRequeueFailure extends RuntimeException {
    public AliasRequeueFailure() {
        super("INVALID_ALIAS_KIND");
    }
}
