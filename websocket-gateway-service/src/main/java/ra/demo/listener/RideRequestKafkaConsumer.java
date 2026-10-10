package ra.demo.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import ra.demo.dto.request.RideRequestEvent;

@Component
@RequiredArgsConstructor
@Slf4j
public class RideRequestKafkaConsumer {

    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(
            topics = "ride-request-topic",
            groupId = "notification-dispatch-group-v2", // Đổi group ID mới
            containerFactory = "kafkaListenerContainerFactory" // BẮT BUỘC có dòng này!
    )
    public void handleRideRequest(RideRequestEvent event) {
        log.info("[WEBSOCKET DISPATCH] Nhận đề nghị chuyến mới #{} từ Kafka. Tiến hành bắn WebSocket...", event.getTripId());

        if (event.getCandidateDriverIds() != null) {
            for (String driverId : event.getCandidateDriverIds()) {
                String destination = "/topic/driver/" + driverId + "/ride-request";

                // ĐÂY CHÍNH LA HÀM THỰC HIỆN NHIỆM VỤ THÔNG BÁO CHO TÀI XẾ VIA WEBSOCKET
                messagingTemplate.convertAndSend(destination, event);

                log.info("[WEBSOCKET SENT] Đã bắn thông báo nổ chuyến #{} tới tài xế #{} qua destination: {}",
                        event.getTripId(), driverId, destination);
            }
        }
    }
}