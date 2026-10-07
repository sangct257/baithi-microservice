package ra.demo.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import ra.demo.constants.DriverStatus;

@Data
public class UpdateDriverStatusRequest {
    @NotNull(message = "Trạng thái hoạt động không được để trống")
    private DriverStatus status; // ONLINE, OFFLINE, BUSY
}
