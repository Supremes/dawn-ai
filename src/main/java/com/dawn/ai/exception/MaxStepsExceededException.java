package com.dawn.ai.exception;

public class MaxStepsExceededException extends RuntimeException {
    public MaxStepsExceededException(String message) {
        super(message);
    }
}
