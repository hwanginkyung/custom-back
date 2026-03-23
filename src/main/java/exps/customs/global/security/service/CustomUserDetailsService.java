package exps.customs.global.security.service;

import exps.customs.domain.login.entity.User;
import exps.customs.domain.login.repository.UserRepository;
import exps.customs.global.config.RedisConfig;
import exps.customs.global.security.CustomUserDetails;
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
        User user = userRepository.findByLoginId(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return new CustomUserDetails(user);
    }

    @CacheEvict(value = RedisConfig.USER_DETAILS_CACHE, key = "#loginId")
    public void evictUserCache(String loginId) {}
}
