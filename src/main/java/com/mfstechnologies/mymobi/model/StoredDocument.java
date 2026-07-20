package com.mfstechnologies.mymobi.model;

import java.time.Instant;

public class StoredDocument {
    private String phoneNumber;
    private String docType;
    private String upn;
    private String html;
    private Instant createdAt;
    private int failedAttempts = 0;
    private boolean invalidated = false;

    public StoredDocument() {
    }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }

    public String getUpn() { return upn; }
    public void setUpn(String upn) { this.upn = upn; }

    public String getHtml() { return html; }
    public void setHtml(String html) { this.html = html; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }

    public boolean isInvalidated() { return invalidated; }
    public void setInvalidated(boolean invalidated) { this.invalidated = invalidated; }
}
