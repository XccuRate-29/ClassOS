package com.classos;

import com.classos.common.ApiException;
import com.classos.user.Role;
import com.classos.user.User;
import com.classos.user.UserDtos.*;
import com.classos.user.UserRepository;
import com.classos.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserAccountTest extends IntegrationTestBase {

    @Autowired UserService service;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    @Test
    void csvImportCreatesValidRowsAndExplainsTheRest() {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        String csv = String.join("\n",
                "email,full name,role",
                "a" + tag + "@test.local, Asha Rawat ,student",
                "b" + tag + "@test.local,Vikram Negi,Lab Assistant",
                "not-an-email,Someone,STUDENT",
                "c" + tag + "@test.local,Someone,PRINCIPAL",
                "A" + tag + "@TEST.local,Duplicate,STUDENT",
                "only,two",
                "");
        ImportResult r = service.importCsv(new ImportRequest(csv, "temporary-pass-1"));
        assertEquals(2, r.created());
        assertEquals(4, r.errors().size());
        assertEquals(4, r.errors().get(0).line());  // line numbers match the file the admin uploaded

        User asha = users.findByEmail("a" + tag + "@test.local").orElseThrow();
        assertEquals("Asha Rawat", asha.getFullName());
        assertEquals(Role.LAB_ASSISTANT, users.findByEmail("b" + tag + "@test.local").orElseThrow().getRole());
        assertTrue(encoder.matches("temporary-pass-1", asha.getPasswordHash()));
    }

    @Test
    void passwordChangeNeedsTheCurrentPassword() {
        User u = service.create(new CreateUserRequest(UUID.randomUUID() + "@test.local", "first-password-1", "P", Role.TEACHER, null));
        ApiException wrong = assertThrows(ApiException.class,
                () -> service.changePassword(u.getId(), new PasswordChangeRequest("not-my-password", "second-password-2")));
        assertEquals(HttpStatus.BAD_REQUEST, wrong.getStatus());

        service.changePassword(u.getId(), new PasswordChangeRequest("first-password-1", "second-password-2"));
        assertTrue(encoder.matches("second-password-2", users.findById(u.getId()).orElseThrow().getPasswordHash()));
    }

    @Test
    void adminsCannotLockThemselvesOut() {
        User admin = users.findByEmail(ADMIN_EMAIL).orElseThrow();
        ApiException self = assertThrows(ApiException.class, () -> service.setActive(admin.getId(), admin.getId(), false));
        assertEquals(HttpStatus.CONFLICT, self.getStatus());

        User student = service.create(new CreateUserRequest(UUID.randomUUID() + "@test.local", "password-123", "S", Role.STUDENT, null));
        assertFalse(service.setActive(student.getId(), admin.getId(), false).isActive());
        assertTrue(service.setActive(student.getId(), admin.getId(), true).isActive());
    }
}
