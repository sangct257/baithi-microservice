package ra.demo.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripCanceledEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long tripId;          // ID chuyến đi bị hủy
    private Long customerId;      // ID khách hàng cần hoàn trả tiền
    private Long driverId;        // ID tài xế (nếu có, trường hợp tài xế hủy)
    private BigDecimal fareAmount;// Số tiền tạm giữ cần hoàn lại
    private String paymentMethod; // Phương thức thanh toán ("WALLET" / "CASH")
    private String cancelReason;  // Lý do hủy chuyến
    private String cancelledBy;   // Người hủy ("ROLE_CUSTOMER" hoặc "ROLE_DRIVER")
}