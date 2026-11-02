package com.classos;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthAndRbacIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String createUser(String adminToken, String email, String role) throws Exception {
        mvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password-123","fullName":"Test %s","role":"%s"}
                                """.formatted(email, role, role)))
                .andExpect(status().isCreated());
        return login(email, "password-123");
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    }

    @Test
    void bootstrapAdminCanLogInAndSeeProfile() throws Exception {
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveSameGeneric401() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"wrong-password\"}".formatted(ADMIN_EMAIL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@test.local\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void missingOrTamperedTokenIs401() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        String token = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rolesOnlyReachTheirOwnRoutes() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String teacher = createUser(admin, uniqueEmail("teacher"), "TEACHER");
        String student = createUser(admin, uniqueEmail("student"), "STUDENT");
        String lab = createUser(admin, uniqueEmail("lab"), "LAB_ASSISTANT");

        mvc.perform(get("/api/teacher/home").header("Authorization", "Bearer " + teacher)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + teacher)).andExpect(status().isForbidden());
        mvc.perform(get("/api/student/home").header("Authorization", "Bearer " + teacher)).andExpect(status().isForbidden());

        mvc.perform(get("/api/student/home").header("Authorization", "Bearer " + student)).andExpect(status().isOk());
        mvc.perform(get("/api/teacher/home").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());
        mvc.perform(get("/api/lab/home").header("Authorization", "Bearer " + student)).andExpect(status().isForbidden());

        mvc.perform(get("/api/lab/home").header("Authorization", "Bearer " + lab)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + lab)).andExpect(status().isForbidden());
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String email = uniqueEmail("dup");
        createUser(admin, email, "TEACHER");
        mvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password-123","fullName":"Dup","role":"STUDENT"}
                                """.formatted(email.toUpperCase())))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidInputIs400WithFieldErrors() throws Exception {
        String admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        mvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"fullName\":\"X\",\"role\":\"TEACHER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists())
                .andExpect(jsonPath("$.fields.password").exists());
    }
}
