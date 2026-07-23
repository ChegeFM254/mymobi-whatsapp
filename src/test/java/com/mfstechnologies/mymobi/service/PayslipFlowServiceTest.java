package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.config.WhatsAppProperties;
import com.mfstechnologies.mymobi.document.DocumentHtmlService;
import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.DocumentStore;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PayslipFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;

    private RegisteredUserStore userStore;
    private DocumentStore documentStore;
    private DocumentHtmlService documentHtmlService;
    private PayslipFlowService payslipFlow;

    @BeforeEach
    void setUp() {
        userStore = new RegisteredUserStore();
        documentStore = new DocumentStore();
        documentHtmlService = new DocumentHtmlService();
        WhatsAppProperties properties = new WhatsAppProperties(
                "test_token", "test_phone_id", "test_verify_token", "test_secret",
                "v21.0", "https://mymobi-test.onrender.com"
        );
        payslipFlow = new PayslipFlowService(screenService, messageService, userStore, documentStore, documentHtmlService, properties);
    }

    @Test
    void payslipMenuPromptsForMonths() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        payslipFlow.handlePayslipMenu(FROM, session).block();

        assertThat(session.getStep()).isEqualTo("enter_payslip_months");
    }

    @Test
    void payslipMenuShowsThePerMonthPriceUpfront() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        payslipFlow.handlePayslipMenu(FROM, session).block();

        verify(messageService).sendTextMessage(eq(FROM),
                eq("Payslip for each month costs KES 23.20. Enter the number of months (1-12):"));
    }

    @Test
    void validMonthsShowsConfirmationWithCorrectCost() {
        UserSession session = new UserSession();
        when(screenService.sendPayslipConfirm(FROM, 3, 69.60)).thenReturn(Mono.empty());

        payslipFlow.handleEnterPayslipMonths(FROM, "3", session).block();

        assertThat(session.getPendingDocumentMonths()).isEqualTo(3);
        assertThat(session.getStep()).isEqualTo("confirm_payslip");
        verify(screenService).sendPayslipConfirm(FROM, 3, 69.60);
    }
    @Test
    void confirmingSendsStkPushPromptBeforeGeneratingTheDocument() {
        RegisteredUser user = new RegisteredUser();
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setUpn("12345");
        userStore.save(FROM, user);

        UserSession session = new UserSession();
        session.setPendingDocumentMonths(3); // cost = 23.20 * 3 = 69.60
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        payslipFlow.handleConfirmPayslip(FROM, session).block();

        verify(messageService).sendTextMessage(eq(FROM),
                eq("You are about to pay KES 69.60 to MyMobi account XXXXX. Please enter your Mpesa PIN."));
    }

    @Test
    void confirmingGeneratesAndStoresADocumentWithAWorkingLink() {
        RegisteredUser user = new RegisteredUser();
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setUpn("12345");
        userStore.save(FROM, user);

        UserSession session = new UserSession();
        session.setPendingDocumentMonths(2);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        payslipFlow.handleConfirmPayslip(FROM, session).block();

        assertThat(session.getPendingDocumentMonths()).isNull(); // cleared after use
        verify(messageService).sendTextMessage(eq(FROM),
                contains("Please click on this link to access your Payslip"));
        verify(messageService).sendTextMessage(eq(FROM), contains("mymobi-test.onrender.com/documents/"));
    }

    @Test
    void cancelingClearsPendingFieldsAndReturnsToMainMenu() {
        UserSession session = new UserSession();
        session.setPendingDocumentMonths(3);
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendMainMenu(FROM)).thenReturn(Mono.empty());

        payslipFlow.handleCancelPayslip(FROM, session).block();

        assertThat(session.getPendingDocumentMonths()).isNull();
        verify(screenService).sendMainMenu(FROM);
    }
}
