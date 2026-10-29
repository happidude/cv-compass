package com.cvcompass.backend.candidate.exception;

public class CandidateFailure extends RuntimeException {
    private final int status;

    public CandidateFailure(int status, String code) {
        super(code);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
