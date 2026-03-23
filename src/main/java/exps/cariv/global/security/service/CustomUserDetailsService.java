package exps.cariv.global.security.service;


import exps.cariv.domain.login.entity.User;
import exps.cariv.domain.login.repository.UserRepository;
import exps.cariv.global.config.RedisConfig;
import exps.cariv.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Cacheable(value = RedisConfig.USER_DETAILS_CACHE, key = "#username")
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // username = loginId
        User user = userRepository.findByLoginId(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return new CustomUserDetails(user);
    }

    @CacheEvict(value = RedisConfig.USER_DETAILS_CACHE, key = "#loginId")
    public void evictUserCache(String loginId) {
        // 비밀번호 변경, 역할 변경 등 User 정보 수정 시 호출
    }
}
