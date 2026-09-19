package com.mfstechnologies.mymobi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * WORKSTREAM C (persistence): now a proper JPA entity, backed by
 * Postgres via DocumentRepository. token is new here - it didn't exist
 * as a field before, since the token was always the external Map key
 * rather than something stored ON the object (same situation as
 * RegisteredUser/Loan). This is purely additive: nothing in the
 * existing flow services calls getToken(), since they already have the
 * token as their own local variable throughout.
 *
 * html is mapped with columnDefinition "TEXT" rather than the default
 * VARCHAR, since a generated payslip/loan-statement/clearance-letter
 * document's HTML can run to several KB - well past a default VARCHAR
 * length limit.
 */
@Entity
@Table(name = "stored_documents")
public class StoredDocument {

    @Id
    @Column(name = "token")
    private String token;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "doc_type")
    private String docType;

    @Column(name = "upn")
    private String upn;

    @Column(name = "html", columnDefinition = "TEXT")
    private String html;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "failed_attempts")
    private int failedAttempts = 0;

    @Column(name = "invalidated")
    private boolean invalidated = false;

    public StoredDocument() {
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

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
