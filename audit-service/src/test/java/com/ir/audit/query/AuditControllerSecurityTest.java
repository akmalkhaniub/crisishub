package com.ir.audit.query;

import com.ir.audit.config.SecurityConfig;
import com.ir.audit.store.AuditRecordStore.AuditPage;
import com.ir.common.config.IrCommonAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(IrCommonAutoConfiguration.class)
class AuditControllerSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuditQueryService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void stub() {
        when(service.query(any(), anyBoolean(), any(), any(), anyInt(), any())).thenReturn(new AuditPage(List.of(), null));
    }

    @ParameterizedTest
    @CsvSource({"auditor,200", "admin,200", "operator,403", "member,403", "responder,403"})
    void onlyAuditorsAndAdminsReadTheTrail(String role, int expected) throws Exception {
        mvc.perform(get("/api/audit").param("entityId", "case-1").with(as(role)))
                .andExpect(status().is(expected));
    }

    @Test
    void requiresExactlyOneOfEntityIdOrActor() throws Exception {
        mvc.perform(get("/api/audit").with(as("auditor"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/audit").param("entityId", "x").param("actor", "y").with(as("auditor")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/audit").param("entityId", "x")).andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.subject("user-" + role)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
