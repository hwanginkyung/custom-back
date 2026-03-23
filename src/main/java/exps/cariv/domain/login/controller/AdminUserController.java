package exps.cariv.domain.login.controller;


import exps.cariv.domain.login.dto.request.ChangePasswordRequest;
import exps.cariv.domain.login.dto.request.ChangeStaffPasswordRequest;
import exps.cariv.domain.login.dto.request.CreateStaffRequest;
import exps.cariv.domain.login.dto.response.MyPageResponse;
import exps.cariv.domain.login.dto.response.StaffResponse;
import exps.cariv.domain.login.service.AdminUserService;
import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController

@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "admin에서 사용할 수 있는 기능들")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    // STAFF 생성
    @PostMapping("/staff")
    @Operation(
            summary = "staff 생성",
            description = "자기 회사의 staff 생성해주기 "
    )
    public ResponseEntity<String> createStaff(
            @Valid @RequestBody CreateStaffRequest req,
            @AuthenticationPrincipal CustomUserDetails currentUser
    ) {
        adminUserService.createStaff(req, currentUser);
        return ResponseEntity.ok("ok");
    }
    @PutMapping("/me/password")
    @Operation(
            summary = "비밀 번호 변경",
            description = "ADMIN 자신의 비밀번호 변경"
    )
    public ResponseEntity<String> changeMyPassword(
            @Valid @RequestBody ChangePasswordRequest req,
            @AuthenticationPrincipal CustomUserDetails currentUser
    ) {
        adminUserService.changeAdminPassword(currentUser, req);
        return ResponseEntity.ok("ok");
    }

    @GetMapping("/me")
    @Operation(
            summary = "마이페이지 조회",
            description = "로그인 사용자 정보 및 ADMIN인 경우 같은 회사 직원 목록 조회"
    )
    public ResponseEntity<MyPageResponse> getMyPage(
            @AuthenticationPrincipal CustomUserDetails currentUser
    ) {
        return ResponseEntity.ok(adminUserService.getMyPage(currentUser));
    }

    // 3) STAFF 비밀번호 변경
    @PutMapping("/staff/{staffId}/password")
    @Operation(
            summary = "비밀 번호 변경",
            description = "Staff의 비밀번호 변경"
    )
    public ResponseEntity<String> changeStaffPassword(
            @PathVariable Long staffId,
            @Valid @RequestBody ChangeStaffPasswordRequest req,
            @AuthenticationPrincipal CustomUserDetails currentUser
    ) {
        adminUserService.changeStaffPassword(staffId, req, currentUser);
        return ResponseEntity.ok("ok");
    }

    @GetMapping("/staff")
    @Operation(
            summary = "직원 목록 조회",
            description = "회사 소속 직원 목록을 조회합니다."
    )
    public ResponseEntity<List<StaffResponse>> getStaffList(
            @AuthenticationPrincipal CustomUserDetails currentUser
    ) {
        return ResponseEntity.ok(
                adminUserService.getStaffList(currentUser)
        );
    }

    // 4) STAFF 삭제
    @DeleteMapping("/staff/{staffId}")
    @Operation(
            summary = "Staff 삭제",
            description = "자기 회사의 staff 삭제"
    )
    public ResponseEntity<String> deleteStaff(
            @PathVariable Long staffId,
            @AuthenticationPrincipal CustomUserDetails currentUser
    ) {
        adminUserService.deleteStaff(staffId, currentUser);
        return ResponseEntity.ok("ok");
    }
}
