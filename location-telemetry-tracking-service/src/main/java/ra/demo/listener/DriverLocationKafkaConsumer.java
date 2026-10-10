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
public class DriverLocationKafkaConsumer {

    private final LocationTrackingService locationTrackingService;

    // Lắng nghe dữ liệu GPS do WebSocket Gateway gửi vào Kafka Topic
    @KafkaListener(topics = "driver-location-stream", groupId = "location-tracking-group")
    public void consumeDriverGpsStream(DriverLocationUpdateRequest request) {
        log.info("[GPS CONSUMER] Nhận GPS từ tài xế #{}: lat={}, lon={}",
                request.getDriverId(), request.getLatitude(), request.getLongitude());

        // Cập nhật trực tiếp vào Redis GEO (driver:geo) và Redis Hash (driver:status)
        locationTrackingService.updateDriverLocation(request);
    }
}