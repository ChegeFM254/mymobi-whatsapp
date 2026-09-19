package com.mfstechnologies.mymobi.service;

import com.mfstechnologies.mymobi.model.RegisteredUser;
import com.mfstechnologies.mymobi.model.UserSession;
import com.mfstechnologies.mymobi.screen.ScreenMessageService;
import com.mfstechnologies.mymobi.session.RegisteredUserRepository;
import com.mfstechnologies.mymobi.session.RegisteredUserStore;
import com.mfstechnologies.mymobi.testsupport.FakeRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WORKSTREAM B (reactive -> synchronous): rewritten for the now-void
 * RegistrationFlowService methods. No more .block() calls or
 * Mono.empty() stubs anywhere.
 *
 * WORKSTREAM C (persistence): RegisteredUserStore is now Postgres-backed
 * - userStore here is wired to a fake, in-memory-backed repository (see
 * FakeRepositories) so it keeps behaving like a real, working
 * collaborator, exactly as it did with the old ConcurrentHashMap.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationFlowServiceTest {

    private static final String FROM = "254700000001";

    @Mock
    private ScreenMessageService screenService;
    @Mock
    private WhatsAppMessageService messageService;
    @Mock
    private RegisteredUserRepository registeredUserRepository;

    private RegisteredUserStore userStore;
    private RegistrationFlowService registrationFlowService;

    @BeforeEach
    void setUp() {
        FakeRepositories.wireAsInMemoryStore(registeredUserRepository, RegisteredUser::getPhoneNumber);
        userStore = new RegisteredUserStore(registeredUserRepository);
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        registrationFlowService = new RegistrationFlowService(screenService, messageService, userStore, passwordEncoder);
    }

    // ==================== ENTRY + OPT-IN ====================

    @Test
    void registerMenuStartsOptInForANewNumber() {
        UserSession session = new UserSession();

        registrationFlowService.handleRegisterMenu(FROM, session);

        assertThat(session.getStep()).isEqualTo("optin");
        verify(screenService).sendOptIn(FROM);
    }

    @Test
    void registerMenuRedirectsToLoginIfAlreadyRegistered() {
        RegisteredUser existing = new RegisteredUser();
        existing.setStatus("active");
        userStore.save(FROM, existing);

        UserSession session = new UserSession();

        registrationFlowService.handleRegisterMenu(FROM, session);

        verify(messageService).sendTextMessage(eq(FROM), contains("already have an account"));
        verify(screenService, never()).sendOptIn(anyString());
    }
    
    @Test
    void acceptingTermsMovesToFirstNameCollection() {
        UserSession session = new UserSession();

        registrationFlowService.handleAcceptTerms(FROM, session);

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

        registrationFlowService.handleFirstName(FROM, "Jane", session);

        assertThat(session.getFirstName()).isEqualTo("Jane");
        assertThat(session.getStep()).isEqualTo("middle_name");
    }

    @Test
    void blankFirstNameIsRejected() {
        UserSession session = new UserSession();

        registrationFlowService.handleFirstName(FROM, "  ", session);

        assertThat(session.getFirstName()).isNull();
        assertThat(session.getStep()).isEqualTo("welcome"); // unchanged
    }

    @Test
    void validMiddleNameAdvancesToLastName() {
        UserSession session = new UserSession();

        registrationFlowService.handleMiddleName(FROM, "Wanjiru", session);

        assertThat(session.getMiddleName()).isEqualTo("Wanjiru");
        assertThat(session.getStep()).isEqualTo("last_name");
    }

    @Test
    void blankMiddleNameIsRejected() {
        UserSession session = new UserSession();

        registrationFlowService.handleMiddleName(FROM, "", session);

        assertThat(session.getMiddleName()).isNull();
    }

    @Test
    void validLastNameAdvancesToEmailAddress() {
        UserSession session = new UserSession();

        registrationFlowService.handleLastName(FROM, "Doe", session);

        assertThat(session.getLastName()).isEqualTo("Doe");
        assertThat(session.getStep()).isEqualTo("email_address");
    }

    @Test
    void validEmailAddressAdvancesToUpn() {
        UserSession session = new UserSession();
                registrationFlowService.handleEmailAddress(FROM, "jane.doe@example.com", session);

        assertThat(session.getEmailAddress()).isEqualTo("jane.doe@example.com");
        assertThat(session.getStep()).isEqualTo("upn");
    }

    @Test
    void invalidEmailAddressIsRejected() {
        UserSession session = new UserSession();

        registrationFlowService.handleEmailAddress(FROM, "not-an-email", session);

        assertThat(session.getEmailAddress()).isNull();
        verify(messageService).sendTextMessage(eq(FROM), contains("valid Email Address"));
    }

    @Test
    void invalidUpnDuringRegistrationIsRejected() {
        UserSession session = new UserSession();

        registrationFlowService.handleUpnField(FROM, "99999", session); // starts with 9, invalid

        assertThat(session.getUpn()).isNull();
        verify(messageService).sendTextMessage(eq(FROM), contains("UPN"));
    }

    @Test
    void validUpnAdvancesToNationalId() {
        UserSession session = new UserSession();

        registrationFlowService.handleUpnField(FROM, "12345", session);

        assertThat(session.getUpn()).isEqualTo("12345");
        assertThat(session.getStep()).isEqualTo("national_id");
    }

    @Test
    void invalidNationalIdIsRejected() {
        UserSession session = new UserSession();

        registrationFlowService.handleNationalId(FROM, "01234567", session); // starts with 0

        assertThat(session.getNationalId()).isNull();
    }

    @Test
    void validNationalIdAdvancesToMobileNumber() {
        UserSession session = new UserSession();

        registrationFlowService.handleNationalId(FROM, "87654321", session);

        assertThat(session.getNationalId()).isEqualTo("87654321");
        assertThat(session.getStep()).isEqualTo("mobile_number");
    }

    @Test
    void validMobileNumberAdvancesToConfirmation() {
        UserSession session = new UserSession();

        registrationFlowService.handleMobileNumber(FROM, "0722730336", session);

        assertThat(session.getMobileNumber()).isEqualTo("0722730336");
        verify(screenService).sendConfirmation(FROM, session);
    }

    @Test
    void invalidMobileNumberIsRejected() {
        UserSession session = new UserSession();

        registrationFlowService.handleMobileNumber(FROM, "12345", session);
        
        assertThat(session.getMobileNumber()).isNull();
    }

    // ==================== CONFIRM / EDIT ====================

    @Test
    void confirmDetailsGeneratesOtpAndMovesToOtpStep() {
        UserSession session = new UserSession();

        registrationFlowService.handleConfirmDetails(FROM, session);

        assertThat(session.getStep()).isEqualTo("enter_otp");
        assertThat(session.getOtp()).matches("^\\d{5}$");
    }

    @Test
    void editFieldSelectUsesTheCorrectHumanReadableLabel() {
        UserSession session = new UserSession();

        registrationFlowService.handleEditFieldSelect(FROM, "edit_nationalid", session);

        assertThat(session.getStep()).isEqualTo("edit_nationalid");
        verify(messageService).sendTextMessage(FROM, "Enter new National ID Number:");
    }

    @Test
    void editFieldSelectWorksForMiddleNameAndEmailAddressToo() {
        UserSession session = new UserSession();

        registrationFlowService.handleEditFieldSelect(FROM, "edit_middlename", session);
        verify(messageService).sendTextMessage(FROM, "Enter new Middle Name:");

        registrationFlowService.handleEditFieldSelect(FROM, "edit_emailaddress", session);
        verify(messageService).sendTextMessage(FROM, "Enter new Email Address:");
    }

    @Test
    void editFieldTextUpdatesTheCorrectFieldAndReturnsToConfirmation() {
        UserSession session = new UserSession();
        session.setStep("edit_firstname");
        session.setFirstName("OldName");

        registrationFlowService.handleEditFieldText(FROM, "NewName", session);

        assertThat(session.getFirstName()).isEqualTo("NewName");
        verify(screenService).sendConfirmation(FROM, session);
    }

    @Test
    void editFieldTextUpdatesMiddleNameCorrectly() {
        UserSession session = new UserSession();
        session.setStep("edit_middlename");

        registrationFlowService.handleEditFieldText(FROM, "Kamau", session);

        assertThat(session.getMiddleName()).isEqualTo("Kamau");
    }

    @Test
    void editFieldTextUpdatesEmailAddressCorrectly() {
        UserSession session = new UserSession();
        session.setStep("edit_emailaddress");

        registrationFlowService.handleEditFieldText(FROM, "new@example.com", session);

        assertThat(session.getEmailAddress()).isEqualTo("new@example.com");
    }

    @Test
        void editFieldTextStillValidatesEmailFormat() {
        UserSession session = new UserSession();
        session.setStep("edit_emailaddress");

        registrationFlowService.handleEditFieldText(FROM, "not-an-email", session);

        assertThat(session.getEmailAddress()).isNull();
        verify(screenService, never()).sendConfirmation(anyString(), any());
    }

    @Test
    void editFieldTextStillValidatesUpnFormat() {
        UserSession session = new UserSession();
        session.setStep("edit_upn");

        registrationFlowService.handleEditFieldText(FROM, "notanumber", session);

        assertThat(session.getUpn()).isNull();
        verify(screenService, never()).sendConfirmation(anyString(), any());
    }

    // ==================== OTP ====================

    @Test
    void correctOtpAdvancesToNewPinStep() {
        UserSession session = new UserSession();
        session.setOtp("12345");

        registrationFlowService.handleEnterOtp(FROM, "12345", session);

        assertThat(session.getStep()).isEqualTo("enter_new_pin");
    }

    @Test
    void wrongOtpIncrementsAttempts() {
        UserSession session = new UserSession();
        session.setOtp("12345");

        registrationFlowService.handleEnterOtp(FROM, "00000", session);

        assertThat(session.getOtpAttempts()).isEqualTo(1);
        assertThat(session.getStep()).isNotEqualTo("enter_new_pin");
    }

    @Test
    void thirdWrongOtpCancelsRegistrationAndReturnsToWelcome() {
        UserSession session = new UserSession();
        session.setOtp("12345");
        session.setOtpAttempts(2); // already failed twice

        registrationFlowService.handleEnterOtp(FROM, "00000", session);

        assertThat(session.getOtp()).isNull(); // reset
        verify(screenService).sendWelcome(FROM);
    }

    // ==================== NEW PIN SETUP ====================

    @Test
    void newPinCannotMatchTheOtp() {
        UserSession session = new UserSession();
        session.setOtp("12345");

        registrationFlowService.handleEnterNewPin(FROM, "12345", session);

        assertThat(session.getNewPin()).isNull();
    }

    @Test
    void validNewPinAdvancesToConfirmStep() {
                UserSession session = new UserSession();
        session.setOtp("11111");

        registrationFlowService.handleEnterNewPin(FROM, "99999", session);

        assertThat(session.getNewPin()).isEqualTo("99999");
        assertThat(session.getStep()).isEqualTo("confirm_new_pin");
    }

    @Test
    void mismatchedPinConfirmationSendsBackToEnterNewPin() {
        UserSession session = new UserSession();
        session.setNewPin("99999");

        registrationFlowService.handleConfirmNewPin(FROM, "11111", session);

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

        registrationFlowService.handleConfirmNewPin(FROM, "99999", session);

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

        registrationFlowService.handleConfirmNewPin(FROM, "99999", session);

        String storedHash = userStore.findByPhoneNumber(FROM).get().getHashedPin();
        assertThat(storedHash).isNotEqualTo("99999");
        assertThat(storedHash.length()).isGreaterThan(20); // BCrypt hashes are always much longer than a 5-digit PIN
    }
}
