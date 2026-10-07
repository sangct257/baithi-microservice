package ra.demo.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ra.demo.event.PaymentHoldEvent;
import ra.demo.event.TripCanceledEvent;
import ra.demo.event.TripCompletedEvent;
import ra.demo.service.PaymentService;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentKafkaConsumer {

    private final PaymentService paymentService;

    // 1. Khi chuyến đi vừa được tạo (Dành cho WALLET) -> TẠM GIỮ TIỀN
    @KafkaListener(topics = "trip-created-topic", groupId = "payment-group")
    public void handleTripCreated(PaymentHoldEvent event) {
        log.info("[KAFKA RECV] Event Tạo Chuyến #{} | Khách #{} | Tiền cước: {} VNĐ",
                event.getTripId(), event.getCustomerId(), event.getFareAmount());
        try {
            paymentService.holdCustomerWallet(event.getCustomerId(), event.getTripId(), event.getFareAmount());
        } catch (Exception e) {
            log.error("[KAFKA ERROR] Giữ tiền thất bại Chuyến #{}: {}", event.getTripId(), e.getMessage());
        }
    }

    // 2. Khi chuyến đi hoàn thành (Dành cho cả WALLET và CASH)
    @KafkaListener(topics = "trip-completed-topic", groupId = "payment-group")
    public void handleTripCompleted(TripCompletedEvent event) {
        log.info("[KAFKA RECV] Event Hoàn Thành Chuyến #{} | PTTT: {}",
                event.getTripId(), event.getPaymentMethod());
        try {
            if ("WALLET".equalsIgnoreCase(event.getPaymentMethod())) {
                // Trừ số tiền đã Hold của khách + Cộng 80% thu nhập cho ví Tài Xế
                paymentService.processWalletPaymentOnComplete(
                        event.getCustomerId(),
                        event.getDriverId(),
                        event.getTripId(),
                        event.getFareAmount()
                );
            } else if ("CASH".equalsIgnoreCase(event.getPaymentMethod())) {
                // Khách trả cash -> Trừ 20% hoa hồng trực tiếp vào ví tài xế
                paymentService.deductDriverCommission(event.getDriverId(), event.getTripId(), event.getFareAmount());
            }
        } catch (Exception e) {
            log.error("[KAFKA ERROR] Xử lý thanh toán chuyến hoàn thành #{} thất bại: {}",
                    event.getTripId(), e.getMessage());
        }
    }

    // 3. Khi chuyến đi bị HỦY (Dành cho WALLET) -> HOÀN TIỀN TẠM GIỮ
    @KafkaListener(topics = "trip-canceled-topic", groupId = "payment-group")
    public void handleTripCanceled(TripCanceledEvent event) {
        log.info("[KAFKA RECV] Event Hủy Chuyến #{} | PTTT: {}",
                event.getTripId(), event.getPaymentMethod());
        try {
            if ("WALLET".equalsIgnoreCase(event.getPaymentMethod())) {
                paymentService.refundHoldAmount(event.getCustomerId(), event.getTripId(), event.getFareAmount());
            }
        } catch (Exception e) {
            log.error("[KAFKA ERROR] Hoàn tiền giữ chuyến bị hủy #{} thất bại: {}",
                    event.getTripId(), e.getMessage());
        }
    }
}