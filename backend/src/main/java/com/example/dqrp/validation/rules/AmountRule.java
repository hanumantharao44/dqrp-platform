package com.example.dqrp.validation.rules;

import com.example.dqrp.model.RuleError;
import com.example.dqrp.model.TransactionRow;
import com.example.dqrp.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class AmountRule implements ValidationRule {
    public List<RuleError> check(TransactionRow r) {
        if (r.amount() == null || r.amount().isBlank())
            return List.of(new RuleError("AMOUNT_INVALID", "amount", "Amount is missing"));
        try {
            if (new BigDecimal(r.amount().trim()).signum() < 0)
                return List.of(new RuleError("AMOUNT_NEGATIVE", "amount", "Amount is negative"));
        } catch (NumberFormatException e) {
            return List.of(new RuleError("AMOUNT_INVALID", "amount", "Not a number"));
        }
        return List.of();
    }
}