package ra.demo.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ra.demo.dto.request.*;
import ra.demo.dto.response.ApiResponse;
import ra.demo.dto.response.JwtResponse;
import ra.demo.entity.User;
import ra.demo.service.UserService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final UserService userService;

    @PostMapping("/register/customer")
    public ResponseEntity<ApiResponse<User>> register(@Valid @RequestBody UserRegister userRegister) {
        return new ResponseEntity<>(new ApiResponse<>(
                true,
                "Đăng ký tài khoản khách hàng thành công",
                userService.registerCustomer(userRegister),
                null,
                HttpStatus.CREATED
        ), HttpStatus.CREATED);
    }

    @PostMapping("/register/driver")
    public ResponseEntity<ApiResponse<User>> registerDriver(@Valid @RequestBody DriverRegisterRequest driverRegisterRequest) {
        return new ResponseEntity<>(new ApiResponse<>(
                true,
                "Đăng ký tài khoản tài xế thành công",
                userService.registerDriver(driverRegisterRequest),
                null,
                HttpStatus.CREATED
        ), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<JwtResponse>> login(@Valid @RequestBody UserLogin userLogin) {
        return new ResponseEntity<>(new ApiResponse<>(
                true,
                "Đăng nhập thành công",
                userService.login(userLogin),
                null,
                HttpStatus.OK
        ), HttpStatus.OK);
    }

    @PostMapping("/driver/send-otp")
    public ResponseEntity<ApiResponse<String>> sendDriverOtp(@Valid @RequestBody SendOtpRequest sendOtpRequest) {
        return new ResponseEntity<>(new ApiResponse<>(
                true,
                "Lấy OTP thành công",
                userService.sendDriverOtp(sendOtpRequest),
                null,
                HttpStatus.CREATED
        ), HttpStatus.CREATED);
    }

    @PostMapping("/login/driver")
    public ResponseEntity<ApiResponse<JwtResponse>> loginDriver(@Valid @RequestBody DriverLoginRequest driverLoginRequest) {
        return new ResponseEntity<>(new ApiResponse<>(
                true,
                "Đăng nhập thành công",
                userService.loginDriver(driverLoginRequest),
                null,
                HttpStatus.OK
        ), HttpStatus.OK);
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<ApiResponse<JwtResponse>> refreshToken(@Valid @RequestBody RefreshTokenRequest refreshTokenRequest) {
        return new ResponseEntity<>(new ApiResponse<>(
                true,
                "Đã cấp lại access token mới",
                userService.refreshToken(refreshTokenRequest.getRefreshToken()),
                null,
                HttpStatus.OK
        ), HttpStatus.OK);
    }

    // Đăng xuất tài khoản
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logout(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiResponse<>(
                    false,
                    "Đăng xuất thất bại",
                    "Token không hợp lệ hoặc đã hết hạn!",
                    null,
                    HttpStatus.UNAUTHORIZED
            ));
        }

        String phoneNumber = authentication.getName();
        userService.logout(phoneNumber);

        return ResponseEntity.ok(new ApiResponse<>(
                true,
                "Đăng xuất thành công!",
                "Phiên làm việc đã kết thúc.",
                null,
                HttpStatus.OK
        ));
    }

}
