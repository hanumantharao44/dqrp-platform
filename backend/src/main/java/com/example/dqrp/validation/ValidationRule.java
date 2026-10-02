package com.example.dqrp.validation;

import com.example.dqrp.model.RuleError;
import com.example.dqrp.model.TransactionRow;

import java.util.List;

public interface ValidationRule { List<RuleError> check(TransactionRow row); }
