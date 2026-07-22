package com.mfstechnologies.mymobi.service;
n
import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import com.mfstechnologies.mymobi.document.DocumentHtmlService;
import com.mfstechnologies.mymobi.model.Loan;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.StoredDocument;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.DocumentStore;
import com.mfstechnologies.mymobi.session.LoanStore;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import com.mfstechnologies.mymobi.validation.CodeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Optional;

@Service
public class LoanDocumentFlowService {

    private static final Logger log = LoggerFactory.getLogger(LoanDocumentFlowService.class);
    private static final double DOCUMENT_COST = 23.20;

    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final RegisteredUserStore userStore;
    private final LoanStore loanStore;
    private final DocumentStore documentStore;
    private final DocumentHtmlService documentHtmlService;
    private final WhatsAppProperties properties;

    public LoanDocumentFlowService(
            ScreenMessageService screenService,
            WhatsAppMessageService messageService,
            RegisteredUserStore userStore,
            LoanStore loanStore,
            DocumentStore documentStore,
            DocumentHtmlService documentHtmlService,
            WhatsAppProperties properties
    ) {
        this.screenService = screenService;
        this.messageService = messageService;
        this.userStore = userStore;
        this.loanStore = loanStore;
        this.documentStore = documentStore;
        this.documentHtmlService = documentHtmlService;
        this.properties = properties;
    }

    // ==================== LOAN STATEMENT ====================

    public Mono<Void> handleLoanStatementMenu(String to, UserSession session) {
        if (loanStore.findByPhoneNumber(to).isEmpty()) {
            return messageService.sendTextMessage(to, "You have no loan on record.")
                    .then(screenService.sendMainMenu(to));
        }

        session.setPendingDocumentType("loan_statement");
        return screenService.sendLoanStatementConfirm(to, DOCUMENT_COST);
    }

    public Mono<Void> handleConfirmLoanStatement(String to, UserSession session) {
        Optional<RegisteredUser> userOpt = userStore.findByPhoneNumber(to);
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);

        if (userOpt.isEmpty() || loanOpt.isEmpty()) {
            session.setPendingDocumentType(null);
            return messageService.sendTextMessage(to, "Something went wrong. Please try again.")
                    .then(screenService.sendMainMenu(to));
        }
        RegisteredUser user = userOpt.get();
        Loan loan = loanOpt.get();

        return sendStkPushPrompt(to, DOCUMENT_COST)
                .then(Mono.defer(() -> {
                    log.info("mpesa_stk_push_simulated to={} purpose=loan_statement", to);
                    String html = documentHtmlService.generateLoanStatementHtml(user, loan);
                    return generateAndSendDocumentLink(to, "loan_statement", "Loan Statement", user.getUpn(), html, session);
                }));
    }

    public Mono<Void> handleCancelLoanStatement(String to, UserSession session) {
        session.setPendingDocumentType(null);
        return messageService.sendTextMessage(to, "Loan Statement request cancelled.")
                .then(screenService.sendMainMenu(to));
    }

    // ==================== LOAN CLEARANCE LETTER ====================
public Mono<Void> handleLoanClearanceMenu(String to, UserSession session) {
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);

        if (loanOpt.isEmpty()) {
            return messageService.sendTextMessage(to, "You have no loan on record for a clearance letter.")
                    .then(screenService.sendMainMenu(to));
        }
        if (!"paid".equals(loanOpt.get().getStatus())) {
            return messageService.sendTextMessage(to, "You have an outstanding loan balance. Pay Loan to download Loan Clearance Letter.")
                    .then(screenService.sendMainMenu(to));
        }

        session.setPendingDocumentType("loan_clearance");
        return screenService.sendLoanClearanceConfirm(to, DOCUMENT_COST);
    }

    public Mono<Void> handleConfirmLoanClearance(String to, UserSession session) {
        Optional<RegisteredUser> userOpt = userStore.findByPhoneNumber(to);
        Optional<Loan> loanOpt = loanStore.findByPhoneNumber(to);

        if (userOpt.isEmpty() || loanOpt.isEmpty() || !"paid".equals(loanOpt.get().getStatus())) {
            session.setPendingDocumentType(null);
            return messageService.sendTextMessage(to, "Something went wrong. Please try again.")
                    .then(screenService.sendMainMenu(to));
        }
        RegisteredUser user = userOpt.get();
        Loan loan = loanOpt.get();

        return sendStkPushPrompt(to, DOCUMENT_COST)
                .then(Mono.defer(() -> {
                    log.info("mpesa_stk_push_simulated to={} purpose=loan_clearance", to);
                    String html = documentHtmlService.generateLoanClearanceHtml(user, loan);
                    return generateAndSendDocumentLink(to, "loan_clearance", "Loan Clearance Letter", user.getUpn(), html, session);
                }));
    }

    public Mono<Void> handleCancelLoanClearance(String to, UserSession session) {
        session.setPendingDocumentType(null);
        return messageService.sendTextMessage(to, "Loan Clearance Letter request cancelled.")
                .then(screenService.sendMainMenu(to));
    }

    // ==================== SHARED ====================

    private Mono<Void> sendStkPushPrompt(String to, double cost) {
        return messageService.sendTextMessage(to,
                String.format("You are about to pay KES %.2f to MyMobi account XXXXX. Please enter your Mpesa PIN.", cost));
    }

    private Mono<Void> generateAndSendDocumentLink(String to, String docType, String docTitle, String upn, String html, UserSession session) {
        StoredDocument document = new StoredDocument();
        document.setPhoneNumber(to);
        document.setDocType(docType);
        document.setUpn(upn);
        document.setHtml(html);
        document.setCreatedAt(Instant.now());

        String token = CodeGenerator.generateDocumentToken();
        documentStore.save(token, document);

        log.info("document_generated to={} docType={} token={}", to, docType, token);

        session.setPendingDocumentType(null);

        String link = properties.publicBaseUrl() + "/documents/" + token;
        return messageService.sendTextMessage(to, "Please click on this link to access your " + docTitle + " " + link)
                .then(screenService.sendMainMenu(to));
    }
}
