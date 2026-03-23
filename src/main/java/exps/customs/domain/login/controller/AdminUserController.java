package exps.customs.domain.login.controller;

import exps.customs.domain.login.dto.request.ChangePasswordRequest;
import exps.customs.domain.login.dto.request.CreateStaffRequest;
import exps.customs.domain.login.dto.response.MyPageResponse;
import exps.customs.domain.login.dto.response.StaffResponse;
import exps.customs.domain.login.service.AdminUserService;
import exps.customs.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@Tag(name = "User", description = "사용자 관리 API")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping("/me")
    @Operation(summary = "마이페이지")
    public ResponseEntity<MyPageResponse> getMyPage(@AuthenticationPrincipal CustomUserDetails me) {
        return ResponseEntity.ok(adminUserService.getMyPage(me.getUserId()));
    }

    @GetMapping("/staff")
    @Operation(summary = "직원 목록")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER')")
    public ResponseEntity<List<StaffResponse>> getStaffList() {
        return ResponseEntity.ok(adminUserService.getStaffList());
    }

    @PostMapping("/staff")
    @Operation(summary = "직원 생성")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER')")
    public ResponseEntity<StaffResponse> createStaff(@Valid @RequestBody CreateStaffRequest req) {
        return ResponseEntity.ok(adminUserService.createStaff(req));
    }

    @PatchMapping("/password")
    @Operation(summary = "비밀번호 변경")
    public ResponseEntity<String> changePassword(
            @AuthenticationPrincipal CustomUserDetails me,
            @Valid @RequestBody ChangePasswordRequest req) {
        adminUserService.changePassword(me.getUserId(), req);
        return ResponseEntity.ok("ok");
    }

    @PatchMapping("/staff/{staffId}/toggle-active")
    @Operation(summary = "직원 활성/비활성 토글")
    @PreAuthorize("hasAnyRole('ADMIN','MASTER')")
    public ResponseEntity<String> toggleStaffActive(@PathVariable Long staffId) {
        adminUserService.toggleStaffActive(staffId);
        return ResponseEntity.ok("ok");
    }
}
