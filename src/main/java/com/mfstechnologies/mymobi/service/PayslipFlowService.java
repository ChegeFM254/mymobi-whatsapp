package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import com.mfstechnologies.mymobi.document.DocumentHtmlService;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.StoredDocument;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.DocumentStore;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import com.mfstechnologies.mymobi.validation.CodeGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * WORKSTREAM B (reactive -> synchronous): every method here used to
 * return Mono<Void>, chaining follow-up steps with .then() - including
 * a Mono.defer(...) wrapping the simulated STK push and document
 * generation. All converted to plain blocking void methods with
 * sequential statements; the defer wrapper is simply gone, since there's
 * no longer a reactive pipeline for it to defer within.
 */
@Service
public class PayslipFlowService {

    private static final Logger log = LoggerFactory.getLogger(PayslipFlowService.class);
    private static final double DOCUMENT_COST_PER_UNIT = 23.20;

    private final ScreenMessageService screenService;
    private final WhatsAppMessageService messageService;
    private final RegisteredUserStore userStore;
    private final DocumentStore documentStore;
    private final DocumentHtmlService documentHtmlService;
    private final WhatsAppProperties properties;

    public PayslipFlowService(
            ScreenMessageService screenService,
            WhatsAppMessageService messageService,
            RegisteredUserStore userStore,
            DocumentStore documentStore,
            DocumentHtmlService documentHtmlService,
            WhatsAppProperties properties
    ) {
        this.screenService = screenService;
        this.messageService = messageService;
        this.userStore = userStore;
        this.documentStore = documentStore;
        this.documentHtmlService = documentHtmlService;
        this.properties = properties;
    }

    public void handlePayslipMenu(String to, UserSession session) {
        session.setStep("enter_payslip_months");
        String prompt = String.format(
                "Payslip for each month costs KES %.2f. Enter the number of months (1-12):",
                DOCUMENT_COST_PER_UNIT
        );
        messageService.sendTextMessage(to, prompt);
    }

    public void handleEnterPayslipMonths(String to, String text, UserSession session) {
        Integer months = parseMonths(text);
        if (months == null) {
            messageService.sendTextMessage(to, "Please enter a whole number between 1 and 12.");
            return;
        }

        double cost = DOCUMENT_COST_PER_UNIT * months;
        session.setPendingDocumentType("payslip");
        session.setPendingDocumentMonths(months);
        session.setStep("confirm_payslip");

        screenService.sendPayslipConfirm(to, months, cost);
    }

    public void handleConfirmPayslip(String to, UserSession session) {
        Optional<RegisteredUser> userOpt = userStore.findByPhoneNumber(to);
        Integer months = session.getPendingDocumentMonths();

        if (userOpt.isEmpty() || months == null) {
            clearPendingDocumentFields(session);
            messageService.sendTextMessage(to, "Something went wrong. Please try again.");
            screenService.sendMainMenu(to);
            return;
        }
        RegisteredUser user = userOpt.get();
        double cost = DOCUMENT_COST_PER_UNIT * months;

        messageService.sendTextMessage(to,
                String.format("You are about to pay KES %.2f to MyMobi account XXXXX. Please enter your Mpesa PIN.", cost));

        log.info("mpesa_stk_push_simulated to={} purpose=payslip months={}", to, months);

        String html = documentHtmlService.generatePayslipHtml(user, months);

        StoredDocument document = new StoredDocument();
        document.setPhoneNumber(to);
        document.setDocType("payslip");
        document.setUpn(user.getUpn());
        document.setHtml(html);
        document.setCreatedAt(Instant.now());

        String token = CodeGenerator.generateDocumentToken();
        documentStore.save(token, document);

        log.info("document_generated to={} docType=payslip token={}", to, token);

        clearPendingDocumentFields(session);

        String link = properties.publicBaseUrl() + "/documents/" + token;
        messageService.sendTextMessage(to, "Please click on this link to access your Payslip " + link);
        screenService.sendMainMenu(to);
    }

    public void handleCancelPayslip(String to, UserSession session) {
        clearPendingDocumentFields(session);
        messageService.sendTextMessage(to, "Payslip request cancelled.");
        screenService.sendMainMenu(to);
    }

    private void clearPendingDocumentFields(UserSession session) {
        session.setPendingDocumentType(null);
        session.setPendingDocumentMonths(null);
    }

    private Integer parseMonths(String text) {
        if (text == null || !text.matches("^\\d+$")) {
            return null;
        }
        try {
            int months = Integer.parseInt(text);
            return (months >= 1 && months <= 12) ? months : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
