package ra.demo.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RideRequestEvent {
    private Long tripId;
    private Long customerId;
    private List<String> candidateDriverIds;
    private String pickupAddress;
    private String dropoffAddress;
    private BigDecimal fareAmount;
}