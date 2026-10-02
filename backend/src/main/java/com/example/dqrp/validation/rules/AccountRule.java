package com.example.dqrp.validation.rules;

import com.example.dqrp.model.RuleError;
import com.example.dqrp.model.TransactionRow;
import com.example.dqrp.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AccountRule implements ValidationRule {
    public List<RuleError> check(TransactionRow r) {
        if (r.accountId() == null || r.accountId().isBlank())
            return List.of(new RuleError("ACCOUNT_MISSING", "account_id", "Account id is missing"));
        return List.of();
    }
}