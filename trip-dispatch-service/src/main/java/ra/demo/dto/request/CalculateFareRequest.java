package ra.demo.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ra.demo.constants.VehicleType;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class CalculateFareRequest {
    // Truyền tên địa chỉ sang Pricing Service để tự động chuyển thành Tọa độ
    private String pickupAddress;
    private String dropoffAddress;

    private Double pickupLat;
    private Double pickupLng;
    private Double dropoffLat;
    private Double dropoffLng;

    private VehicleType vehicleType;
}