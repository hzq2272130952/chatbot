package com.chatbot.exceptions;

import com.chatbot.common.enums.BizStatus;

public class BizException extends RuntimeException {
    private BizStatus bizStatus;

    public BizException(BizStatus bizStatus) {
        super(bizStatus.getMessage());
        this.bizStatus = bizStatus;
    }

    public BizStatus getBizStatus() {
        return bizStatus;
    }
}
