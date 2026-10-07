package ra.demo.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ra.demo.security.utils.JwtUtils; // Thêm JwtUtils siêu gọn để đọc khi test trực tiếp 8082

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Filter này chạy 1 lần cho mỗi Request gửi tới Trip-Service.
 * Nhiệm vụ: Hứng các Header 'X-User-Id' và 'X-User-Roles' do Gateway gửi sang,
 * sau đó biến chúng thành đối tượng Authentication chính chủ của Spring Security.
 */
@Component
@RequiredArgsConstructor
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils; // Inject JwtUtils vào đây

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String userIdHeader = request.getHeader("X-User-Id");
        String rolesHeader = request.getHeader("X-User-Roles");

        // 1. Ưu tiên đọc Header do Gateway chuyển xuống
        if (StringUtils.hasText(userIdHeader)) {
            setSecurityContext(Long.parseLong(userIdHeader), rolesHeader);
        }
        // 2. Dự phòng: Nếu Test trực tiếp qua Port 8082 không qua Gateway
        else {
            String token = getJwtFromRequest(request);
            if (StringUtils.hasText(token) && jwtUtils.validateToken(token)) {
                Long userId = jwtUtils.extractUserId(token);
                List<String> roles = jwtUtils.extractRoles(token);
                String rolesStr = roles != null ? String.join(",", roles) : "";

                setSecurityContext(userId, rolesStr);
            }
        }

        filterChain.doFilter(request, response);
    }

    // Helper set thông tin vào Spring Security
    private void setSecurityContext(Long userId, String rolesHeader) {
        List<SimpleGrantedAuthority> authorities = List.of();
        if (StringUtils.hasText(rolesHeader)) {
            authorities = Arrays.stream(rolesHeader.split(","))
                    .map(String::trim)
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    // Helper lấy Token từ Header Authorization
    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}