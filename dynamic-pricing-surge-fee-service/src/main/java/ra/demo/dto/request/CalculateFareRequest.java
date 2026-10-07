package ra.demo.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ra.demo.constants.VehicleType;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CalculateFareRequest {

    // [MỚI] Tên địa chỉ thực tế khi khách hàng ping điểm đón/trả
    private String pickupAddress;
    private String dropoffAddress;

    // Tọa độ trực tiếp (Nếu app di động đã lấy sẵn từ GPS)
    private Double pickupLat; // Vĩ độ điểm đón
    private Double pickupLng; // Kinh độ điểm đón
    private Double dropoffLat; // Vĩ độ điểm trả
    private Double dropoffLng; // Kinh độ điểm trả
    private VehicleType vehicleType; // Loại xe
}
