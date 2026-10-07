package com.ir.common.security;

/** Keycloak realm roles (realm `incident-response`). Spring authorities are {@code ROLE_<name>}. */
public final class Roles {
    public static final String MEMBER = "member";
    public static final String OPERATOR = "operator";
    public static final String RESPONDER = "responder";
    public static final String AUDITOR = "auditor";
    public static final String ADMIN = "admin";

    private Roles() {
    }
}
