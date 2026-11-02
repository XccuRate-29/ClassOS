package com.classos.user;

import com.classos.security.AuthenticatedUser;
import com.classos.user.UserDtos.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// URL-level rule in SecurityConfig restricts /api/admin/** to ADMIN.
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserService service;
    private final UserRepository users;

    public AdminUserController(UserService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
        return UserResponse.from(service.create(req));
    }

    @GetMapping
    public Page<UserResponse> list(Pageable pageable) {
        return users.findAll(pageable).map(UserResponse::from);
    }

    @PostMapping("/import")
    public ImportResult importCsv(@Valid @RequestBody ImportRequest req) {
        return service.importCsv(req);
    }

    @PatchMapping("/{id}/active")
    public UserResponse setActive(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable long id,
                                  @RequestBody ActiveRequest req) {
        return UserResponse.from(service.setActive(id, admin.id(), req.active()));
    }
}
