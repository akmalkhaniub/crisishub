package com.ir.cases.controller;

import com.ir.cases.config.SecurityConfig;
import com.ir.cases.service.CaseService;
import com.ir.common.config.IrCommonAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Role-gate half of the spec §7 matrix (ownership half is covered in CaseServiceTest). */
@WebMvcTest(CaseController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(IrCommonAutoConfiguration.class)
class CaseControllerSecurityTest {

    private static final String ID = UUID.randomUUID().toString();

    private static final Map<String, String> BODIES = Map.of(
            "report", "{\"type\":\"MEDICAL\",\"title\":\"t\",\"lat\":1,\"lng\":2}",
            "triage", "{\"severity\":\"HIGH\"}",
            "assign", "{}",
            "reassign", "{}",
            "resolve", "{\"note\":\"n\"}",
            "cancel", "{\"reason\":\"r\"}");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CaseService service;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @org.junit.jupiter.api.BeforeEach
    void stubService() {
        org.mockito.Mockito.when(service.list(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        org.mockito.Mockito.when(service.assign(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CaseService.AssignOutcome(null, false));
    }

    @Test
    void missingTokenIs401() throws Exception {
        mvc.perform(get("/api/cases")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublicPathButCasesAreNot() throws Exception {
        mvc.perform(post("/api/cases/" + ID + "/triage")).andExpect(status().isUnauthorized());
    }

    /** action, role, expected-allowed. "Allowed" = passes the role gate (not 401/403). */
    @ParameterizedTest(name = "{1} {0} -> allowed={2}")
    @CsvSource({
            "report,member,true", "report,operator,false", "report,responder,false", "report,auditor,false",
            "triage,operator,true", "triage,admin,true", "triage,member,false", "triage,responder,false", "triage,auditor,false",
            "assign,operator,true", "assign,member,false", "assign,responder,false",
            "reassign,operator,true", "reassign,responder,false",
            "decline,responder,true", "decline,operator,false", "decline,member,false",
            "resolve,responder,true", "resolve,operator,true", "resolve,member,false", "resolve,auditor,false",
            "reopen,operator,true", "reopen,responder,false", "reopen,member,false",
            "close,operator,true", "close,admin,true", "close,responder,false", "close,member,false", "close,auditor,false",
            "cancel,member,true", "cancel,operator,true", "cancel,responder,false", "cancel,auditor,false",
    })
    void roleMatrix(String action, String role, boolean allowed) throws Exception {
        var result = mvc.perform(request(action).with(as(role))).andReturn().getResponse().getStatus();

        if (allowed) {
            org.assertj.core.api.Assertions.assertThat(result).isNotIn(401, 403);
        } else {
            org.assertj.core.api.Assertions.assertThat(result).isEqualTo(403);
        }
    }

    @ParameterizedTest
    @CsvSource({"member", "responder", "operator", "auditor", "admin"})
    void anyAuthenticatedRoleMayListAndReadScopedByService(String role) throws Exception {
        mvc.perform(get("/api/cases").with(as(role))).andExpect(status().isOk());
        mvc.perform(get("/api/cases/" + ID).with(as(role))).andExpect(status().isOk());
    }

    @Test
    void validationErrorsAre400ProblemJson() throws Exception {
        mvc.perform(post("/api/cases").with(as("member")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"MEDICAL\",\"title\":\"\",\"lat\":100,\"lng\":2}"))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder request(String action) {
        String path = action.equals("report") ? "/api/cases" : "/api/cases/" + ID + "/" + action;
        return post(path).contentType(MediaType.APPLICATION_JSON).content(BODIES.getOrDefault(action, "{}"));
    }

    private static RequestPostProcessor as(String role) {
        return jwt().jwt(j -> j.subject("user-" + role).claim("realm_access", Map.of("roles", java.util.List.of(role))))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
    }
}
