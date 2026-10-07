package ra.demo.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ra.demo.constants.PaymentMethod;
import ra.demo.constants.TripStatus;
import ra.demo.constants.VehicleType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class TripResponse {
    private Long tripId;
    private Long customerId;
    private Long driverId;
    private String pickupAddress;
    private Double pickupLatitude;
    private Double pickupLongitude;
    private String dropoffAddress;
    private Double dropoffLatitude;
    private Double dropoffLongitude;
    private VehicleType vehicleType;
    private Double distanceKm;
    private BigDecimal fareAmount;
    private PaymentMethod paymentMethod;
    private TripStatus status;
    private String cancelReason;
    private String cancelledBy;
    private LocalDateTime createdAt;
}
