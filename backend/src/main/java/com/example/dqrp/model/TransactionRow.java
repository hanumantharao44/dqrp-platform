package com.example.dqrp.model;

public record TransactionRow(int rowNumber, String txnRef, String accountId,
                         String amount, String currency, String txnDate) {}

