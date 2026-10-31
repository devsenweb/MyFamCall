package com.myfamcall.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class AuthUiErrorTest {
    @Test public void unknownProviderTextNeverLeaks(){assertEquals("UNKNOWN",AuthUiError.safeCode("token=private@example.com"));}
    @Test public void knownErrorCodeIsPreserved(){assertEquals("ERROR_TOO_MANY_REQUESTS",AuthUiError.safeCode("ERROR_TOO_MANY_REQUESTS"));}
    @Test public void rateLimitDoesNotTellUserToRecreateAccount(){String message=AuthUiError.verification("rate_limit","ERROR_TOO_MANY_REQUESTS");assertTrue(message.contains("wait"));assertTrue(message.contains("spam"));assertFalse(message.contains("create"));}
    @Test public void networkFailureIsDistinct(){assertTrue(AuthUiError.verification("network","NETWORK_ERROR").contains("connection"));}
    @Test public void providerFailureShowsActionableCode(){assertTrue(AuthUiError.verification("auth","ERROR_OPERATION_NOT_ALLOWED").contains("configured"));}
}
