package com.mfstechnologies.mymobi.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
public class StoredDocument {
    private String phoneNumber;
    private String docType; // payslip, loan_statement, loan_clearance
    private String upn;
    private String html;
    private Instant createdAt;
    private int failedAttempts = 0;
    private boolean invalidated = false;
}