package com.example.dqrp.validation.rules;

import com.example.dqrp.model.RuleError;
import com.example.dqrp.model.TransactionRow;
import com.example.dqrp.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReferenceRule implements ValidationRule {
    public List<RuleError> check(TransactionRow r) {
        if (r.txnRef() == null || r.txnRef().isBlank())
            return List.of(new RuleError("REF_MISSING", "txn_ref", "Transaction reference is missing"));
        return List.of();
    }
}