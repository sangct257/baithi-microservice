package ra.demo.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ra.demo.constants.DriverStatus;
import ra.demo.constants.VehicleType;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverProfileResponse {
    private Long userId;
    private String fullName;
    private String phoneNumber;
    private String driverLicenseNumber;
    private String identityCard;
    private DriverStatus status;
    private Double rating;
    private Boolean isApproved;

    // Thông tin phương tiện
    private String licensePlate;
    private String brand;
    private String model;
    private VehicleType vehicleType;

    private LocalDateTime createdAt;
}
