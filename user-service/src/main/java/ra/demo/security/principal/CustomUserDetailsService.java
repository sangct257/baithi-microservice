package ra.demo.security.principal;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import ra.demo.entity.Role;
import ra.demo.entity.User;
import ra.demo.repository.UserRepository;


import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String phoneNumber) throws UsernameNotFoundException {
        User users = userRepository.findByPhoneNumber(phoneNumber).orElseThrow(() -> new UsernameNotFoundException("Tài khoản không tồn tại"));

        return CustomUserDetails.builder()
                .id(users.getId())
                .phoneNumber(users.getPhoneNumber())
                .password(users.getPassword())
                .fullName(users.getFullName())
                .email(users.getEmail())
                .status(users.getStatus())
                .authorities(mapRoleToAuthorities(users.getRoles().stream().toList()))
                .build();
    }

    private Collection<? extends GrantedAuthority> mapRoleToAuthorities(List<Role> roles) {
        return roles.stream().map(role-> new SimpleGrantedAuthority(role.getRoleName().toString())).toList();
    }
}
