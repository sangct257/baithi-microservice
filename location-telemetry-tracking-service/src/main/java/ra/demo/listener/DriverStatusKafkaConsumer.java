package ra.demo.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ra.demo.dto.request.DriverLocationUpdateRequest;
import ra.demo.service.LocationTrackingService;

@Component
@RequiredArgsConstructor
@Slf4j
public class DriverStatusKafkaConsumer {

    private final LocationTrackingService locationTrackingService;

    @KafkaListener(topics = "driver-status-topic", groupId = "location-tracking-group")
    public void consumeStatusChange(DriverLocationUpdateRequest request) {
        log.info("[LOCATION SERVICE] Nhận sự kiện đổi trạng thái tài xế #{}: {}",
                request.getDriverId(), request.getStatus());

        // Nếu status = OFFLINE, hàm updateDriverLocation sẽ tự động xóa tài xế khỏi Redis GEO
        locationTrackingService.updateDriverLocation(request);
    }
}