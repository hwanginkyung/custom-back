package exps.customs.domain.login.service;

import exps.customs.domain.login.dto.request.ChangePasswordRequest;
import exps.customs.domain.login.dto.request.CreateStaffRequest;
import exps.customs.domain.login.dto.response.MyPageResponse;
import exps.customs.domain.login.dto.response.StaffResponse;
import exps.customs.domain.login.entity.Company;
import exps.customs.domain.login.entity.User;
import exps.customs.domain.login.entity.enumType.Role;
import exps.customs.domain.login.repository.CompanyRepository;
import exps.customs.domain.login.repository.UserRepository;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import exps.customs.global.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AdminUserService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder encoder;

    public MyPageResponse getMyPage(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        return MyPageResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .active(user.isActive())
                .companyId(user.getCompanyId())
                .build();
    }

    public List<StaffResponse> getStaffList() {
        Long companyId = TenantContext.getCompanyId();
        List<User> users = userRepository.findAllByCompanyId(companyId);
        return users.stream().map(StaffResponse::from).toList();
    }

    @Transactional
    public StaffResponse createStaff(CreateStaffRequest req) {
        Long companyId = TenantContext.getCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        String email = req.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "이미 사용 중인 이메일입니다.");
        }

        User user = User.builder()
                .loginId(email)
                .email(email)
                .passwordHash(encoder.encode(req.getPassword()))
                .active(true)
                .role(Role.STAFF)
                .build();
        user.setCompanyId(company.getId());
        userRepository.save(user);

        log.info("[Admin] staff created email={}, companyId={}", email, companyId);
        return StaffResponse.from(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

        if (!encoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "현재 비밀번호가 일치하지 않습니다.");
        }

        user.setPasswordHash(encoder.encode(req.getNewPassword()));
        userRepository.save(user);
        log.info("[Admin] password changed userId={}", userId);
    }

    @Transactional
    public void toggleStaffActive(Long staffId) {
        User user = userRepository.findById(staffId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
        Long companyId = TenantContext.getCompanyId();
        if (!user.getCompanyId().equals(companyId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
        user.setActive(!user.isActive());
        userRepository.save(user);
        log.info("[Admin] staff active toggled userId={}, active={}", staffId, user.isActive());
    }
}
