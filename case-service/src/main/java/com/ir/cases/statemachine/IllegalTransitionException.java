package com.ir.cases.statemachine;

import com.ir.cases.domain.CaseStatus;
import com.ir.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class IllegalTransitionException extends ApiException {
    public IllegalTransitionException(CaseStatus from, CaseStatus to) {
        super(HttpStatus.CONFLICT, "illegal-transition", "Cannot move case from " + from + " to " + to);
    }
}
