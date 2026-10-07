package ra.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Kênh để Server gửi thông báo / nổ chuyến xuống Driver App
        registry.enableSimpleBroker("/topic","/queue");
        // Tiền tố cho các message từ Driver App gửi lên Server (ví dụ gửi tọa độ GPS)
        registry.setApplicationDestinationPrefixes("/app");
    }
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Endpoint để ứng dụng tài xế kết nối WebSocket vào: ws://localhost:8085/ws-driver
        registry.addEndpoint("/ws")
                .setAllowedOrigins("*")// Cho phép kết nối từ mọi nguồn
                .withSockJS(); // Hỗ trợ SockJS làm fallback khi kết nối bị ngắt
    }
}
