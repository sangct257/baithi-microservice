package ra.demo.service;

import ra.demo.constants.AccountStatus;
import ra.demo.dto.request.*;
import ra.demo.dto.response.JwtResponse;
import ra.demo.entity.User;

public interface UserService {
    User registerCustomer(UserRegister userRegister);
    User registerDriver(DriverRegisterRequest driverRegisterRequest);
    JwtResponse login(UserLogin userLogin);
    String sendDriverOtp(SendOtpRequest request);
    JwtResponse loginDriver(DriverLoginRequest driverLoginRequest);
    JwtResponse refreshToken(String refreshToken);
    void logout(String token);

    // Admin cập nhật trạng thái tài khoản (ACTIVE, CUSTOMER_LOCKED, DRIVER_LOCKED,...)
    void adminChangeAccountStatus(Long userId, AccountStatus status);

    // Khách hàng tự yêu cầu vô hiệu hóa / xóa tài khoản Khách hàng
    void customerSelfDisableAccount(String phoneNumber);

    // Tài xế tự yêu cầu vô hiệu hóa / xóa tài khoản Tài xế
    void driverSelfDisableAccount(String phoneNumber);
}
