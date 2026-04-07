package com.vijay.campaign.exception;

import lombok.Getter;

@Getter
public class LegoWorkflowException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public LegoWorkflowException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }
}
