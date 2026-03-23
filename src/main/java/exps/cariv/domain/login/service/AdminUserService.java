package exps.cariv.domain.login.service;


import exps.cariv.domain.login.dto.request.ChangePasswordRequest;
import exps.cariv.domain.login.dto.request.ChangeStaffPasswordRequest;
import exps.cariv.domain.login.dto.request.CreateStaffRequest;
import exps.cariv.domain.login.dto.response.MyPageResponse;
import exps.cariv.domain.login.dto.response.StaffResponse;
import exps.cariv.domain.login.entity.Company;
import exps.cariv.domain.login.entity.User;
import exps.cariv.domain.login.entity.enumType.Role;
import exps.cariv.domain.login.repository.CompanyRepository;
import exps.cariv.domain.login.repository.UserRepository;
import exps.cariv.global.exception.CustomException;
import exps.cariv.global.exception.ErrorCode;
import exps.cariv.global.jwt.service.RefreshTokenService;
import exps.cariv.global.security.CustomUserDetails;
import exps.cariv.global.security.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AdminUserService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final CustomUserDetailsService userDetailsService;
    private final RefreshTokenService refreshTokenService;

    /**
     * STAFF 생성
     */
    @Transactional
    public void createStaff(CreateStaffRequest req, CustomUserDetails currentUser) {
        Long targetCompanyId = validateAdminAndGetCompanyId(currentUser);

        Company company = companyRepository.findById(targetCompanyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        String email = req.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미 사용 중인 이메일입니다: " + email);
        }

        User staff = User.builder()
                .loginId(email)
                .email(email)
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .role(Role.STAFF)
                .active(true)
                .build();

        staff.setCompanyId(company.getId()); // ⬅ TenantEntity의 companyId 설정

        try {
            userRepository.save(staff);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미 사용 중인 이메일입니다: " + email);
        }
        log.info("[Admin] staff created byUserId={}, staffId={}, companyId={}",
                currentUser.getUserId(), staff.getId(), company.getId());
    }


    /**
     * Admin 본인 비밀번호 변경
     */
    @Transactional
    public void changeAdminPassword(CustomUserDetails currentUser, ChangePasswordRequest req) {

        User admin = getUser(currentUser.getUserId());

        if (admin.getRole() != Role.ADMIN) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        if (!req.getNewPassword().equals(req.getNewCheckPassword())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "새 비밀번호와 비밀번호 확인이 일치하지 않습니다.");
        }

        if (req.getOldPassword().equals(req.getNewPassword())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "기존 비밀번호와 새 비밀번호는 같을 수 없습니다.");
        }

        if (!passwordEncoder.matches(req.getOldPassword(), admin.getPasswordHash())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        admin.changePassword(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(admin);
        userDetailsService.evictUserCache(admin.getLoginId()); // 캐시 무효화
        refreshTokenService.revokeAllByUserId(admin.getId());
        log.info("[Admin] password changed userId={}, companyId={}", admin.getId(), admin.getCompanyId());
    }


    /**
     * STAFF 비밀번호 변경
     */
    @Transactional
    public void changeStaffPassword(Long staffId, ChangeStaffPasswordRequest req, CustomUserDetails currentUser) {
        Long companyId = validateAdminAndGetCompanyId(currentUser);

        User staff = getUser(staffId);

        // tenant 경계를 먼저 검증해서 타사 계정 존재 유추를 최소화
        if (!Objects.equals(staff.getCompanyId(), companyId)) {
            throw new CustomException(ErrorCode.TENANT_MISMATCH);
        }

        if (staff.getRole() != Role.STAFF) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        // staff 비밀번호 변경 요청에서도 확인값이 들어오면 일치 검증
        if (!req.getNewPassword().equals(req.getNewCheckPassword())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "새 비밀번호와 비밀번호 확인이 일치하지 않습니다.");
        }

        staff.changePassword(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(staff);
        userDetailsService.evictUserCache(staff.getLoginId()); // 캐시 무효화
        refreshTokenService.revokeAllByUserId(staff.getId());
        log.info("[Admin] staff password changed byUserId={}, staffId={}, companyId={}",
                currentUser.getUserId(), staff.getId(), companyId);
    }


    /**
     * STAFF 삭제(물리 삭제)
     */
    @Transactional
    public void deleteStaff(Long staffId, CustomUserDetails currentUser) {
        Long companyId = validateAdminAndGetCompanyId(currentUser);

        User staff = userRepository.findById(staffId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "삭제할 staff를 찾을 수 없습니다."));

        if (!Objects.equals(staff.getCompanyId(), companyId)) {
            throw new CustomException(ErrorCode.TENANT_MISMATCH);
        }

        if (staff.getRole() != Role.STAFF) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        userRepository.delete(staff);
        userDetailsService.evictUserCache(staff.getLoginId()); // 캐시 무효화
        refreshTokenService.revokeAllByUserId(staff.getId());
        log.info("[Admin] staff deleted byUserId={}, staffId={}, companyId={}",
                currentUser.getUserId(), staff.getId(), companyId);
    }


    /**
     * Admin/Master 권한 체크 후 토큰의 companyId 반환
     */
    private Long validateAdminAndGetCompanyId(CustomUserDetails currentUser) {
        if (currentUser == null || currentUser.getUserId() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        // 현재 로그인한 사용자 정보 조회
        User admin = getUser(currentUser.getUserId());

        // 권한 체크
        if (admin.getRole() != Role.ADMIN && admin.getRole() != Role.MASTER) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }

        if (admin.getCompanyId() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        return admin.getCompanyId();
    }
    //직원 리스트 검색
    public List<StaffResponse> getStaffList(CustomUserDetails currentUser) {
        Long companyId = validateAdminAndGetCompanyId(currentUser);

        List<User> staffList = userRepository
                .findAllByCompanyIdAndRoleAndActiveTrue(companyId, Role.STAFF);

        return staffList.stream()
                .map(StaffResponse::from)
                .toList();
    }

    public MyPageResponse getMyPage(CustomUserDetails currentUser) {
        User user = getUser(currentUser.getUserId());
        if (user.getRole() != Role.ADMIN && user.getRole() != Role.MASTER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "마이페이지는 관리자만 접근할 수 있습니다.");
        }
        Company company = getCompany(user.getCompanyId());
        List<StaffResponse> staffList = userRepository
                .findAllByCompanyIdAndRoleAndActiveTrue(user.getCompanyId(), Role.STAFF)
                .stream()
                .map(StaffResponse::from)
                .toList();

        return MyPageResponse.from(user, company, staffList);
    }

    private Company getCompany(Long companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    }

    private User getUser(Long id) {
        if (id == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        return userRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    }
}

