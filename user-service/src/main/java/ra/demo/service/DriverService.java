package ra.demo.service;

import ra.demo.dto.request.DriverProfileRegisterRequest;
import ra.demo.dto.response.DriverProfileResponse;

public interface DriverService {
    // Tài xế cập nhật/đăng ký thông tin hồ sơ & phương tiện
    DriverProfileResponse createOrUpdateProfile(Long userId, DriverProfileRegisterRequest request);

    // Lấy thông tin hồ sơ của chính tài xế đang đăng nhập
    DriverProfileResponse getMyProfile(Long userId);

    // Tài xế cập nhật trạng thái hoạt động (ONLINE/OFFLINE)
    DriverProfileResponse updateStatus(Long userId);

    // Admin duyệt hoặc từ chối hồ sơ tài xế
    DriverProfileResponse approveDriverProfile(Long driverId);

}