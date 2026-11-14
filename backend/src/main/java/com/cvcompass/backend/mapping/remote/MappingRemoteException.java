package com.cvcompass.backend.mapping.remote;

public final class MappingRemoteException extends RuntimeException {
    private final int status;

    public MappingRemoteException(int status, String code) {
        super(code);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static void require(boolean condition, int status, String code) {
        if (!condition) throw new MappingRemoteException(status, code);
    }
}
