package ra.demo.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CalculateFareResponse {
    private Double distanceKm;
    private Integer durationMinutes;
    private BigDecimal fareAmount;
    private Double surgeMultiplier;

    // Trả về tọa độ thực tế đã định vị được từ địa chỉ ping
    private Double pickupLat;
    private Double pickupLng;
    private Double dropoffLat;
    private Double dropoffLng;
}