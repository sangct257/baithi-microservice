package ra.demo.config;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpRequest = servletRequest.getServletRequest();
            // 1. Đọc từ Header (Do Gateway truyền xuống)
            String userId = httpRequest.getHeader("X-Auth-User-Id");
            String userRole = httpRequest.getHeader("X-Auth-Roles");

            // 2. Nếu không có Header, đọc từ Query Param trên URL (Ví dụ: ws://localhost:8086/ws-raw?userId=3)
            if (userId == null || userId.isBlank()) {
                userId = httpRequest.getParameter("userId");
            }

            // 3. Cho phép Handshake nếu tìm thấy userId
            if (userId != null && !userId.isBlank()) {
                attributes.put("userId", userId);
                if (userRole != null) {
                    attributes.put("userRole", userRole);
                }
                return true; // Chấp nhận nâng cấp sang WebSocket
            }
        }
        // Trả về 401 thay vì mặc định 200/403 để Postman hiểu rõ lý do ngắt kết nối
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false; // Từ chối kết nối nếu không có xác thực
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, @Nullable Exception exception) {

    }
}
