package com.example.dqrp.validation.rules;

import com.example.dqrp.model.RuleError;
import com.example.dqrp.model.TransactionRow;
import com.example.dqrp.validation.ValidationRule;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class DateRule implements ValidationRule {
    public List<RuleError> check(TransactionRow r) {
        try {
            LocalDate d = LocalDate.parse(r.txnDate().trim());
            if (d.isAfter(LocalDate.now()))
                return List.of(new RuleError("DATE_FUTURE", "txn_date", "Date is in the future"));
        } catch (Exception e) {   // null, blank, or wrong format
            return List.of(new RuleError("DATE_INVALID", "txn_date", "Not a valid date (yyyy-MM-dd)"));
        }
        return List.of();
    }
}
