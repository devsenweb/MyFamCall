package com.myfamcall.app;

/** Sanitized diagnostics: never expose provider exception messages, tokens, emails or URLs. */
final class AuthUiError {
    private AuthUiError() {}
    static String safeCode(String code){return code!=null&&code.matches("[A-Z0-9_]{1,80}")?code:"UNKNOWN";}
    static String verification(String category,String code){
        String reference=safeCode(code);
        if("network".equals(category))return "The verification request did not complete. Check your inbox/spam and connection before trying once more. ["+reference+"]";
        if("rate_limit".equals(category))return "Firebase is limiting verification requests. Check your inbox and spam folder; wait before requesting another email. ["+reference+"]";
        if("ERROR_OPERATION_NOT_ALLOWED".equals(reference)||"ERROR_CONFIGUATION_NOT_FOUND".equals(reference)||"ERROR_CONFIGURATION_NOT_FOUND".equals(reference))return "Firebase email verification is not configured correctly for this project. ["+reference+"]";
        if("ERROR_USER_DISABLED".equals(reference)||"ERROR_USER_TOKEN_EXPIRED".equals(reference)||"ERROR_INVALID_USER_TOKEN".equals(reference))return "Your sign-in session needs attention. Sign out and sign in again; do not create another account. ["+reference+"]";
        return "The verification request was not confirmed. Check your inbox/spam, keep this account, and share this error code if needed. ["+reference+"]";
    }
}
