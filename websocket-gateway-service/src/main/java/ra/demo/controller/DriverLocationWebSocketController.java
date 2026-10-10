package ra.demo.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import ra.demo.dto.request.DriverLocationUpdateRequest;

@Controller
@RequiredArgsConstructor
@Slf4j
public class DriverLocationWebSocketController {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Driver App gửi tin định kỳ 3s/lần vào đường dẫn STOMP: "/app/driver/location"
    @MessageMapping("/driver/location")
    public void handleDriverLocationStream(DriverLocationUpdateRequest request) {
        log.debug("[WEBSOCKET GPS INGEST] Nhận GPS từ driver: {}", request.getDriverId());

        // Forward tọa độ vào Kafka Topic để Location Service tiêu thụ bất đồng bộ
        kafkaTemplate.send("driver-location-stream", request.getDriverId(), request);
    }
}