package ra.demo.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ra.demo.constants.DriverStatus;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class DriverLocationUpdateRequest {
    private String driverId;
    private Double latitude;
    private Double longitude;
    private DriverStatus status; // ONLINE, OFFLINE
}
