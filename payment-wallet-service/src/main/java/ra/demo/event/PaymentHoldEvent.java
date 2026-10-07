package ra.demo.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentHoldEvent {
    private Long tripId;
    private Long customerId;
    private BigDecimal fareAmount;
    private String paymentMethod;
}
