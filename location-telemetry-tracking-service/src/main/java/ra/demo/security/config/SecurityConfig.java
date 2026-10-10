package ra.demo.security.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ra.demo.security.exception.CustomAccessDeniedHandler;
import ra.demo.security.exception.JwtAuthenticationEntryPoint;
import ra.demo.security.filter.CustomHeaderAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final CustomHeaderAuthenticationFilter headerAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtEntryPoint; // Xử lý 401
    private final CustomAccessDeniedHandler accessDeniedHandler; // Xử lý 403
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Cho phép DRIVER gửi tọa độ GPS
                        .requestMatchers("/api/v1/location/update").hasAuthority("ROLE_DRIVER")
                        .requestMatchers("/api/v1/location/nearby").hasAnyAuthority("ROLE_CUSTOMER", "ROLE_ADMIN")
                        // Hoặc cho phép permitAll() nếu đi qua API Gateway đã lọc Security
                        // .requestMatchers("/api/v1/location/**").permitAll()
                        .anyRequest().authenticated()                 // Tất cả request khác bắt buộc phải qua Gateway (có Header Auth)
                )
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtEntryPoint) // Bắt lỗi 401 (Chưa đăng nhập / Token hết hạn)
                        .accessDeniedHandler(accessDeniedHandler) // Bắt lỗi 403 (Đã đăng nhập nhưng không đủ quyền)
                )
                // THÊM FILTER ĐỂ ĐỌC X-Auth-User-Id / JWT TOKEN TỪ GATEWAY
                .addFilterBefore(headerAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
