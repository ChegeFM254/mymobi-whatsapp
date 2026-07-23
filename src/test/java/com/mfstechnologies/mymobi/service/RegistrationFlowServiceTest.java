package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistrationFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;

    private RegisteredUserStore userStore;
    private RegistrationFlowService registrationFlowService;

    @BeforeEach
    void setUp() {
        userStore = new RegisteredUserStore();
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        registrationFlowService = new RegistrationFlowService(screenService, messageService, userStore, passwordEncoder);
    }

    // ==================== ENTRY + OPT-IN ====================

    @Test
    void registerMenuStartsOptInForANewNumber() {
        UserSession session = new UserSession();
        when(screenService.sendOptIn(FROM)).thenReturn(Mono.empty());

        registrationFlowService.handleRegisterMenu(FROM, session).block();

        assertThat(session.getStep()).isEqualTo("optin");
        verify(screenService).sendOptIn(FROM);
    }

    @Test
    void registerMenuRedirectsToLoginIfAlreadyRegistered() {
        RegisteredUser existing = new RegisteredUser();
        existing.setStatus("active");
        userStore.save(FROM, existing);

        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendCivilServantsMenu(FROM)).thenReturn(Mono.empty());

        registrationFlowService.handleRegisterMenu(FROM, session).block();

        verify(messageService).sendTextMessage(eq(FROM), contains("already have an account"));
        verify(screenService, never()).sendOptIn(anyString());
    }

    @Test
    void acceptingTermsMovesToFirstNameCollection() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleAcceptTerms(FROM, session).block();

        assertThat(session.getStep()).isEqualTo("first_name");
    }

    // ==================== KYC FIELD COLLECTION ====================
    // Corrected order: First Name -> Middle Name -> Last Name -> Email
    // Address -> UPN Number -> National ID Number -> Mpesa Mobile Number
    // -> Confirmation. The five ORIGINAL fields keep their exact
    // original relative order; only Middle Name and Email Address are
    // newly inserted at specific points.

    @Test
    void validFirstNameAdvancesToMiddleName() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleFirstName(FROM, "Jane", session).block();

        assertThat(session.getFirstName()).isEqualTo("Jane");
        assertThat(session.getStep()).isEqualTo("middle_name");
    }

    @Test
    void blankFirstNameIsRejected() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleFirstName(FROM, "  ", session).block();

        assertThat(session.getFirstName()).isNull();
        assertThat(session.getStep()).isEqualTo("welcome"); // unchanged
    }

    @Test
    void validMiddleNameAdvancesToLastName() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleMiddleName(FROM, "Wanjiru", session).block();

        assertThat(session.getMiddleName()).isEqualTo("Wanjiru");
        assertThat(session.getStep()).isEqualTo("last_name");
    }

    @Test
    void blankMiddleNameIsRejected() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleMiddleName(FROM, "", session).block();

        assertThat(session.getMiddleName()).isNull();
    }

    @Test
    void validLastNameAdvancesToEmailAddress() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleLastName(FROM, "Doe", session).block();

        assertThat(session.getLastName()).isEqualTo("Doe");
        assertThat(session.getStep()).isEqualTo("email_address");
    }

    @Test
    void validEmailAddressAdvancesToUpn() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleEmailAddress(FROM, "jane.doe@example.com", session).block();

        assertThat(session.getEmailAddress()).isEqualTo("jane.doe@example.com");
        assertThat(session.getStep()).isEqualTo("upn");
    }

    @Test
    void invalidEmailAddressIsRejected() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleEmailAddress(FROM, "not-an-email", session).block();

        assertThat(session.getEmailAddress()).isNull();
        verify(messageService).sendTextMessage(eq(FROM), contains("valid Email Address"));
    }

    @Test
    void invalidUpnDuringRegistrationIsRejected() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleUpnField(FROM, "99999", session).block(); // starts with 9, invalid

        assertThat(session.getUpn()).isNull();
        verify(messageService).sendTextMessage(eq(FROM), contains("UPN"));
    }

    @Test
    void validUpnAdvancesToNationalId() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleUpnField(FROM, "12345", session).block();

        assertThat(session.getUpn()).isEqualTo("12345");
        assertThat(session.getStep()).isEqualTo("national_id");
    }

    @Test
    void invalidNationalIdIsRejected() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleNationalId(FROM, "01234567", session).block(); // starts with 0

        assertThat(session.getNationalId()).isNull();
    }

    @Test
    void validNationalIdAdvancesToMobileNumber() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleNationalId(FROM, "87654321", session).block();

        assertThat(session.getNationalId()).isEqualTo("87654321");
        assertThat(session.getStep()).isEqualTo("mobile_number");
    }

    @Test
    void validMobileNumberAdvancesToConfirmation() {
        UserSession session = new UserSession();
        when(screenService.sendConfirmation(eq(FROM), eq(session))).thenReturn(Mono.empty());

        registrationFlowService.handleMobileNumber(FROM, "0722730336", session).block();

        assertThat(session.getMobileNumber()).isEqualTo("0722730336");
        verify(screenService).sendConfirmation(FROM, session);
    }

    @Test
    void invalidMobileNumberIsRejected() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleMobileNumber(FROM, "12345", session).block();

        assertThat(session.getMobileNumber()).isNull();
    }

    // ==================== CONFIRM / EDIT ====================

    @Test
    void confirmDetailsGeneratesOtpAndMovesToOtpStep() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleConfirmDetails(FROM, session).block();

        assertThat(session.getStep()).isEqualTo("enter_otp");
        assertThat(session.getOtp()).matches("^\\d{5}$");
    }

    @Test
    void editFieldSelectUsesTheCorrectHumanReadableLabel() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleEditFieldSelect(FROM, "edit_nationalid", session).block();

        assertThat(session.getStep()).isEqualTo("edit_nationalid");
        verify(messageService).sendTextMessage(FROM, "Enter new National ID Number:");
    }

    @Test
    void editFieldSelectWorksForMiddleNameAndEmailAddressToo() {
        UserSession session = new UserSession();
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleEditFieldSelect(FROM, "edit_middlename", session).block();
        verify(messageService).sendTextMessage(FROM, "Enter new Middle Name:");

        registrationFlowService.handleEditFieldSelect(FROM, "edit_emailaddress", session).block();
        verify(messageService).sendTextMessage(FROM, "Enter new Email Address:");
    }

    @Test
    void editFieldTextUpdatesTheCorrectFieldAndReturnsToConfirmation() {
        UserSession session = new UserSession();
        session.setStep("edit_firstname");
        session.setFirstName("OldName");
        when(screenService.sendConfirmation(eq(FROM), eq(session))).thenReturn(Mono.empty());

        registrationFlowService.handleEditFieldText(FROM, "NewName", session).block();

        assertThat(session.getFirstName()).isEqualTo("NewName");
        verify(screenService).sendConfirmation(FROM, session);
    }

    @Test
    void editFieldTextUpdatesMiddleNameCorrectly() {
        UserSession session = new UserSession();
        session.setStep("edit_middlename");
        when(screenService.sendConfirmation(eq(FROM), eq(session))).thenReturn(Mono.empty());

        registrationFlowService.handleEditFieldText(FROM, "Kamau", session).block();

        assertThat(session.getMiddleName()).isEqualTo("Kamau");
    }

    @Test
    void editFieldTextUpdatesEmailAddressCorrectly() {
        UserSession session = new UserSession();
        session.setStep("edit_emailaddress");
        when(screenService.sendConfirmation(eq(FROM), eq(session))).thenReturn(Mono.empty());

        registrationFlowService.handleEditFieldText(FROM, "new@example.com", session).block();

        assertThat(session.getEmailAddress()).isEqualTo("new@example.com");
    }

    @Test
    void editFieldTextStillValidatesEmailFormat() {
        UserSession session = new UserSession();
        session.setStep("edit_emailaddress");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleEditFieldText(FROM, "not-an-email", session).block();

        assertThat(session.getEmailAddress()).isNull();
        verify(screenService, never()).sendConfirmation(anyString(), any());
    }

    @Test
    void editFieldTextStillValidatesUpnFormat() {
        UserSession session = new UserSession();
        session.setStep("edit_upn");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleEditFieldText(FROM, "notanumber", session).block();

        assertThat(session.getUpn()).isNull();
        verify(screenService, never()).sendConfirmation(anyString(), any());
    }

    // ==================== OTP ====================

    @Test
    void correctOtpAdvancesToNewPinStep() {
        UserSession session = new UserSession();
        session.setOtp("12345");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleEnterOtp(FROM, "12345", session).block();

        assertThat(session.getStep()).isEqualTo("enter_new_pin");
    }

    @Test
    void wrongOtpIncrementsAttempts() {
        UserSession session = new UserSession();
        session.setOtp("12345");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleEnterOtp(FROM, "00000", session).block();

        assertThat(session.getOtpAttempts()).isEqualTo(1);
        assertThat(session.getStep()).isNotEqualTo("enter_new_pin");
    }

    @Test
    void thirdWrongOtpCancelsRegistrationAndReturnsToWelcome() {
        UserSession session = new UserSession();
        session.setOtp("12345");
        session.setOtpAttempts(2); // already failed twice
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        registrationFlowService.handleEnterOtp(FROM, "00000", session).block();

        assertThat(session.getOtp()).isNull(); // reset
        verify(screenService).sendWelcome(FROM);
    }

    // ==================== NEW PIN SETUP ====================

    @Test
    void newPinCannotMatchTheOtp() {
        UserSession session = new UserSession();
        session.setOtp("12345");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleEnterNewPin(FROM, "12345", session).block();

        assertThat(session.getNewPin()).isNull();
    }

    @Test
    void validNewPinAdvancesToConfirmStep() {
        UserSession session = new UserSession();
        session.setOtp("11111");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleEnterNewPin(FROM, "99999", session).block();

        assertThat(session.getNewPin()).isEqualTo("99999");
        assertThat(session.getStep()).isEqualTo("confirm_new_pin");
    }

    @Test
    void mismatchedPinConfirmationSendsBackToEnterNewPin() {
        UserSession session = new UserSession();
        session.setNewPin("99999");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());

        registrationFlowService.handleConfirmNewPin(FROM, "11111", session).block();

        assertThat(session.getStep()).isEqualTo("enter_new_pin");
    }

    @Test
    void matchingPinConfirmationCompletesRegistration() {
        UserSession session = new UserSession();
        session.setFirstName("Jane");
        session.setMiddleName("Wanjiru");
        session.setLastName("Doe");
        session.setEmailAddress("jane.doe@example.com");
        session.setUpn("12345");
        session.setNationalId("87654321");
        session.setMobileNumber("0722730336");
        session.setNewPin("99999");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        registrationFlowService.handleConfirmNewPin(FROM, "99999", session).block();

        assertThat(session.isAuthenticated()).isTrue();
        assertThat(session.getNewPin()).isNull(); // cleared after use
        assertThat(userStore.findByPhoneNumber(FROM)).isPresent();

        RegisteredUser stored = userStore.findByPhoneNumber(FROM).get();
        assertThat(stored.getFirstName()).isEqualTo("Jane");
        assertThat(stored.getMiddleName()).isEqualTo("Wanjiru");
        assertThat(stored.getLastName()).isEqualTo("Doe");
        assertThat(stored.getEmailAddress()).isEqualTo("jane.doe@example.com");
        assertThat(stored.getUpn()).isEqualTo("12345");
        assertThat(stored.getNationalId()).isEqualTo("87654321");
        assertThat(stored.getMobileNumber()).isEqualTo("0722730336");
        verify(screenService).sendWelcome(FROM);
    }

    @Test
    void completedRegistrationNeverStoresTheRawPin() {
        UserSession session = new UserSession();
        session.setNewPin("99999");
        when(messageService.sendTextMessage(eq(FROM), anyString())).thenReturn(Mono.empty());
        when(screenService.sendWelcome(FROM)).thenReturn(Mono.empty());

        registrationFlowService.handleConfirmNewPin(FROM, "99999", session).block();

        String storedHash = userStore.findByPhoneNumber(FROM).get().getHashedPin();
        assertThat(storedHash).isNotEqualTo("99999");
        assertThat(storedHash.length()).isGreaterThan(20); // BCrypt hashes are always much longer than a 5-digit PIN
    }
}
