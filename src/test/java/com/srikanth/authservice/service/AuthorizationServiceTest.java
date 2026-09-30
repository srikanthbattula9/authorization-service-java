package com.srikanth.authservice.service;

import com.srikanth.authservice.dto.AuthorizeRequest;
import com.srikanth.authservice.dto.AuthorizeResponse;
import com.srikanth.authservice.repo.AuthorizationRepository;
import com.srikanth.authservice.repo.IdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the rule order itself: card status -> MCC -> velocity ->
 * balance. Both repositories are mocked, so these run with no database and
 * no Docker, unlike the integration test blocked by the Testcontainers/
 * Docker Desktop issue documented in the README.
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

    @Mock AuthorizationRepository authRepo;
    @Mock IdempotencyRepository idempotencyRepo;

    AuthorizationService service;

    static final String CARD_TOKEN = "11111111-1111-1111-1111-111111111111";
    static final long ACCOUNT_ID = 42L;

    @BeforeEach
    void setUp() {
        service = new AuthorizationService(authRepo, idempotencyRepo);
        ReflectionTestUtils.setField(service, "velocityMaxAuths", 5);
        ReflectionTestUtils.setField(service, "velocityWindowSeconds", 60);
        when(idempotencyRepo.begin(any(), any(), any()))
                .thenReturn(new IdempotencyRepository.Begun(true, "started"));
    }

    private AuthorizeRequest request(long amount, String mcc) {
        return new AuthorizeRequest(CARD_TOKEN, amount, "merchant_1", mcc);
    }

    @Test
    void frozenCardIsDeclinedWithoutCheckingMccOrBalance() {
        when(authRepo.findCardByToken(CARD_TOKEN)).thenReturn(
                Optional.of(new AuthorizationRepository.Card("frozen", ACCOUNT_ID, LocalDate.now().plusYears(1))));

        AuthorizeResponse response = service.authorize("key-1", request(1000, "5812"));

        assertThat(response.status()).isEqualTo("declined");
        assertThat(response.declineCode()).isEqualTo("62");
        verify(authRepo, never()).isMccBlocked(any());
        verify(authRepo, never()).availableBalance(anyLong());
    }

    @Test
    void expiredCardIsDeclinedWithoutCheckingMccOrBalance() {
        when(authRepo.findCardByToken(CARD_TOKEN)).thenReturn(
                Optional.of(new AuthorizationRepository.Card("active", ACCOUNT_ID, LocalDate.now().minusDays(1))));

        AuthorizeResponse response = service.authorize("key-2", request(1000, "5812"));

        assertThat(response.declineCode()).isEqualTo("54");
        verify(authRepo, never()).isMccBlocked(any());
    }

    @Test
    void blockedMccIsDeclinedBeforeVelocityOrBalanceAreChecked() {
        when(authRepo.findCardByToken(CARD_TOKEN)).thenReturn(
                Optional.of(new AuthorizationRepository.Card("active", ACCOUNT_ID, LocalDate.now().plusYears(1))));
        when(authRepo.isMccBlocked("7995")).thenReturn(true);

        AuthorizeResponse response = service.authorize("key-3", request(1000, "7995"));

        assertThat(response.declineCode()).isEqualTo("57");
        verify(authRepo, never()).countRecentAuthorizations(any(), anyInt());
        verify(authRepo, never()).availableBalance(anyLong());
    }

    @Test
    void velocityLimitIsCheckedBeforeBalance() {
        when(authRepo.findCardByToken(CARD_TOKEN)).thenReturn(
                Optional.of(new AuthorizationRepository.Card("active", ACCOUNT_ID, LocalDate.now().plusYears(1))));
        when(authRepo.isMccBlocked(any())).thenReturn(false);
        when(authRepo.countRecentAuthorizations(eq(CARD_TOKEN), anyInt())).thenReturn(5L);

        AuthorizeResponse response = service.authorize("key-4", request(1000, "5812"));

        assertThat(response.declineCode()).isEqualTo("61");
        verify(authRepo, never()).availableBalance(anyLong());
    }

    @Test
    void insufficientBalanceIsDeclinedWhenEveryEarlierRulePasses() {
        when(authRepo.findCardByToken(CARD_TOKEN)).thenReturn(
                Optional.of(new AuthorizationRepository.Card("active", ACCOUNT_ID, LocalDate.now().plusYears(1))));
        when(authRepo.isMccBlocked(any())).thenReturn(false);
        when(authRepo.countRecentAuthorizations(any(), anyInt())).thenReturn(0L);
        when(authRepo.availableBalance(ACCOUNT_ID)).thenReturn(500L);

        AuthorizeResponse response = service.authorize("key-5", request(1000, "5812"));

        assertThat(response.declineCode()).isEqualTo("51");
    }

    @Test
    void approvedWhenEveryRulePasses() {
        when(authRepo.findCardByToken(CARD_TOKEN)).thenReturn(
                Optional.of(new AuthorizationRepository.Card("active", ACCOUNT_ID, LocalDate.now().plusYears(1))));
        when(authRepo.isMccBlocked(any())).thenReturn(false);
        when(authRepo.countRecentAuthorizations(any(), anyInt())).thenReturn(0L);
        when(authRepo.availableBalance(ACCOUNT_ID)).thenReturn(5000L);

        AuthorizeResponse response = service.authorize("key-6", request(1000, "5812"));

        assertThat(response.status()).isEqualTo("approved");
        assertThat(response.declineCode()).isNull();
        verify(authRepo).insertAuthorization(any(), eq(CARD_TOKEN), eq(ACCOUNT_ID), eq(1000L),
                any(), any(), eq("approved"), isNull(), eq("key-6"), any());
        verify(idempotencyRepo).finish("key-6");
    }

    @Test
    void invalidCardTokenThrowsDecline14() {
        when(authRepo.findCardByToken(CARD_TOKEN)).thenReturn(Optional.empty());

        assertThat(catchDeclineCode("key-7")).isEqualTo("14");
    }

    private String catchDeclineCode(String key) {
        try {
            service.authorize(key, request(1000, "5812"));
            return null;
        } catch (AuthorizationService.DeclineException e) {
            return e.code;
        }
    }

    @Test
    void finishedIdempotencyKeyReturnsStoredResultWithoutReEvaluatingRules() {
        when(idempotencyRepo.begin(eq("key-8"), any(), any()))
                .thenReturn(new IdempotencyRepository.Begun(false, "finished"));
        when(authRepo.findByIdempotencyKey("key-8"))
                .thenReturn(Optional.of(new AuthorizationRepository.StoredAuth("auth-999", "approved", null)));

        AuthorizeResponse response = service.authorize("key-8", request(1000, "5812"));

        assertThat(response.authId()).isEqualTo("auth-999");
        verify(authRepo, never()).findCardByToken(any());
    }
}
