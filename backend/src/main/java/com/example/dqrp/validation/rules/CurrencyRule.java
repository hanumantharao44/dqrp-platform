package com.example.dqrp.validation.rules;

import com.example.dqrp.model.RuleError;
import com.example.dqrp.model.TransactionRow;
import com.example.dqrp.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class CurrencyRule implements ValidationRule {
    private static final Set<String> ALLOWED = Set.of("USD", "EUR", "GBP", "INR");
    public List<RuleError> check(TransactionRow r) {
        if (r.currency() == null || !ALLOWED.contains(r.currency().trim()))
            return List.of(new RuleError("CURRENCY_INVALID", "currency", "Currency not allowed"));
        return List.of();
    }
}