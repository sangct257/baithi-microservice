package ra.demo.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ra.demo.constants.AccountStatus;
import ra.demo.constants.RoleEnums;
import ra.demo.dto.request.*;
import ra.demo.dto.response.JwtResponse;
import ra.demo.entity.RefreshToken;
import ra.demo.entity.Role;
import ra.demo.entity.User;
import ra.demo.exception.BadRequestException;
import ra.demo.exception.ResourceNotFoundException;
import ra.demo.repository.RefreshTokenRepository;
import ra.demo.repository.RoleRepository;
import ra.demo.repository.UserRepository;
import ra.demo.security.filter.JWTProvider;
import ra.demo.security.principal.CustomUserDetails;
import ra.demo.security.principal.CustomUserDetailsService;
import ra.demo.service.UserService;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleRepository roleRepository;
    private final AuthenticationManager authenticationManager;
    private final JWTProvider jwtProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final CustomUserDetailsService userDetailsService;
    private final OtpService otpService;

    @Value("${jwt-refresh-expire}")
    private long jwtRefreshExpire;


    @Override
    public User registerCustomer(UserRegister userRegister) {
        Role customerRole = roleRepository.findByRoleName(RoleEnums.ROLE_CUSTOMER)
                .orElseThrow(() -> new ResourceNotFoundException("Chưa khởi tạo ROLE_CUSTOMER trong hệ thống!"));

        return userRepository.findByPhoneNumber(userRegister.getPhoneNumber())
                .map(existingUser -> {
                    // Kiểm tra xem user này đã có ROLE_CUSTOMER chưa
                    boolean hasCustomerRole = existingUser.getRoles().stream()
                            .anyMatch(role -> role.getRoleName().equals(RoleEnums.ROLE_CUSTOMER));

                    if (hasCustomerRole) {
                        throw new BadRequestException("Số điện thoại này đã được đăng ký!");
                    }

                    // Nếu là Driver đăng ký thêm Customer -> Bổ sung ROLE_CUSTOMER & Mật khẩu
                    existingUser.getRoles().add(customerRole);
                    existingUser.setPassword(passwordEncoder.encode(userRegister.getPassword()));

                    // Cập nhật bổ sung tên/email nếu tài khoản cũ chưa có
                    if (existingUser.getFullName() == null || existingUser.getFullName().isEmpty()) {
                        existingUser.setFullName(userRegister.getFullName());
                    }
                    if (existingUser.getEmail() == null || existingUser.getEmail().isEmpty()) {
                        existingUser.setEmail(userRegister.getEmail());
                    }

                    return userRepository.save(existingUser);
                })
                .orElseGet(() -> {
                    // Tạo mới hoàn toàn nếu chưa từng tồn tại SĐT này
                    User user = User.builder()
                            .phoneNumber(userRegister.getPhoneNumber())
                            .password(passwordEncoder.encode(userRegister.getPassword()))
                            .fullName(userRegister.getFullName())
                            .email(userRegister.getEmail())
                            .status(AccountStatus.ACTIVE)
                            .roles(new HashSet<>(Set.of(customerRole)))
                            .build();
                    return userRepository.save(user);
                });
    }

    @Override
    public User registerDriver(DriverRegisterRequest driverRegisterRequest) {
        Role driverRole = roleRepository.findByRoleName(RoleEnums.ROLE_DRIVER)
                .orElseThrow(() -> new ResourceNotFoundException("Chưa khởi tạo ROLE_DRIVER trong hệ thống!"));

        return userRepository.findByPhoneNumber(driverRegisterRequest.getPhoneNumber())
                .map(existingUser -> {
                    // Kiểm tra xem user đã có ROLE_DRIVER chưa
                    boolean hasDriverRole = existingUser.getRoles().stream()
                            .anyMatch(role -> role.getRoleName().equals(RoleEnums.ROLE_DRIVER));

                    if (hasDriverRole) {
                        throw new BadRequestException("Số điện thoại này đã được đăng ký!");
                    }

                    // SĐT đã tồn tại (ví dụ đã làm Customer) -> Bổ sung thêm ROLE_DRIVER
                    existingUser.getRoles().add(driverRole);
                    return userRepository.save(existingUser);
                })
                .orElseGet(() -> {
                    // Tạo mới Driver
                    User newDriver = User.builder()
                            .phoneNumber(driverRegisterRequest.getPhoneNumber())
                            .fullName(driverRegisterRequest.getFullName())
                            .email(driverRegisterRequest.getEmail())
                            .status(AccountStatus.ACTIVE)
                            .roles(new HashSet<>(Set.of(driverRole)))
                            .build();
                    return userRepository.save(newDriver);
                });
    }

    @Override
    @Transactional
    public JwtResponse login(UserLogin userLogin) {
        Authentication authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(userLogin.getPhoneNumber(), userLogin.getPassword()));

        CustomUserDetails customUserDetails = (CustomUserDetails) authentication.getPrincipal();

        String accessToken = jwtProvider.generateToken(customUserDetails);

        // Xóa token cũ của user trước khi cấp token mới
        refreshTokenRepository.deleteByPhoneNumber(customUserDetails.getUsername());

        //Tạo ra refresh-token
        RefreshToken refreshToken = RefreshToken.builder()
                .phoneNumber(customUserDetails.getUsername())
                .expiryDate(Instant.now().plusMillis(jwtRefreshExpire))
                .refreshToken(UUID.randomUUID().toString())
                .invoke(false)
                .build();
        //save vao database
        refreshTokenRepository.save(refreshToken);

        return JwtResponse.builder()
                .phoneNumber(customUserDetails.getPhoneNumber())
                .fullName(customUserDetails.getFullName())
                .email(customUserDetails.getEmail())
                .status(customUserDetails.getStatus().toString())
                .authorities(customUserDetails.getAuthorities())
                .accessToken(accessToken)
                .refreshToken(refreshToken.getRefreshToken())
                .build();
    }

    @Override
    public String sendDriverOtp(SendOtpRequest request) {
        User user = userRepository.findByPhoneNumber(request.getPhoneNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Số điện thoại tài xế chưa tồn tại trên hệ thống!"));

        boolean isDriver = user.getRoles().stream()
                .anyMatch(role -> role.getRoleName().equals(RoleEnums.ROLE_DRIVER));

        if (!isDriver) {
            throw new BadRequestException("Số điện thoại này không có quyền Tài xế!");
        }

        String otp = otpService.generateAndSaveOtp(request.getPhoneNumber());
        log.info("Mã OTP gửi tới SĐT {}: {}", request.getPhoneNumber(), otp);

        return "Mã OTP đã được gửi thành công. Vui lòng kiểm tra tin nhắn!";
    }

    @Override
    @Transactional
    public JwtResponse loginDriver(DriverLoginRequest driverLoginRequest) {

        // 1. Kiểm tra OTP từ Redis (nếu đúng thì OtpService tự động xóa khỏi Redis)
        boolean isValidOtp = otpService.validateOtp(driverLoginRequest.getPhoneNumber(), driverLoginRequest.getOtp());
        if (!isValidOtp) {
            throw new BadRequestException("Mã OTP không chính xác hoặc đã hết hạn!");
        }

        // 2. Lấy thông tin Tài xế
        User user = userRepository.findByPhoneNumber(driverLoginRequest.getPhoneNumber())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản Tài xế!"));

        // 3. Kiểm tra trạng thái khóa/xóa riêng cho Tài xế
        if (user.getStatus() == AccountStatus.DRIVER_LOCKED) {
            throw new BadRequestException("Tài khoản Tài xế của bạn đã bị khóa. Vui lòng liên hệ Admin!");
        }
        if (user.getStatus() == AccountStatus.DRIVER_DELETED) {
            throw new BadRequestException("Tài khoản Tài xế của bạn đã bị xóa khỏi hệ thống!");
        }

        CustomUserDetails customUserDetails = (CustomUserDetails) userDetailsService.loadUserByUsername(user.getPhoneNumber());
        String accessToken = jwtProvider.generateToken(customUserDetails);

        // Xóa token cũ của user trước khi cấp token mới
        refreshTokenRepository.deleteByPhoneNumber(customUserDetails.getUsername());

        //Tạo ra refresh-token
        RefreshToken refreshToken = RefreshToken.builder()
                .phoneNumber(customUserDetails.getUsername())
                .expiryDate(Instant.now().plusMillis(jwtRefreshExpire))
                .refreshToken(UUID.randomUUID().toString())
                .invoke(false)
                .build();
        //save vao database
        refreshTokenRepository.save(refreshToken);

        return JwtResponse.builder()
                .phoneNumber(customUserDetails.getPhoneNumber())
                .fullName(customUserDetails.getFullName())
                .email(customUserDetails.getEmail())
                .status(customUserDetails.getStatus().toString())
                .authorities(customUserDetails.getAuthorities())
                .accessToken(accessToken)
                .refreshToken(refreshToken.getRefreshToken())
                .build();
    }

    @Override
    public JwtResponse refreshToken(String refreshToken) {
        RefreshToken objRefreshToken = refreshTokenRepository.findByRefreshToken(refreshToken)
                .orElseThrow(() -> new ResourceNotFoundException("Refresh token không tồn tại trong hệ thống!"));

        if (objRefreshToken.getInvoke()) {
            throw new BadRequestException("Refresh token đã bị thu hồi!");
        }

        if (objRefreshToken.getExpiryDate().isBefore(Instant.now())) {
            throw new BadRequestException("Refresh token đã hết hạn!");
        }

        CustomUserDetails customUserDetails = (CustomUserDetails) userDetailsService.loadUserByUsername(objRefreshToken.getPhoneNumber());

        String accessToken = jwtProvider.generateToken(customUserDetails);

        return JwtResponse.builder()
                .phoneNumber(customUserDetails.getPhoneNumber())
                .fullName(customUserDetails.getFullName())
                .email(customUserDetails.getEmail())
                .status(customUserDetails.getStatus().toString())
                .authorities(customUserDetails.getAuthorities())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    @Override
    @Transactional
    public void logout(String phoneNumber) {
        if (!StringUtils.hasText(phoneNumber)) {
            throw new BadRequestException("Thông tin người dùng không hợp lệ!");
        }

        refreshTokenRepository.deleteByPhoneNumber(phoneNumber);
        log.info("Đã đăng xuất thành công và xóa Refresh Token của SĐT: {}", phoneNumber);
    }

    @Override
    public void adminChangeAccountStatus(Long userId, AccountStatus status) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản người dùng với ID: " + userId));

        user.setStatus(status);
        userRepository.save(user);

        // Thu hồi toàn bộ Refresh Token để buộc tài khoản đó phải đăng xuất ngay lập tức
        refreshTokenRepository.findByPhoneNumber(user.getPhoneNumber())
                .ifPresent(token -> {
                    token.setInvoke(true);
                    refreshTokenRepository.save(token);
                });
    }

    @Override
    public void customerSelfDisableAccount(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin tài khoản Khách hàng!"));

        // Kiểm tra xem có đúng là Khách hàng không
        boolean isCustomer = user.getRoles().stream()
                .anyMatch(role -> role.getRoleName().equals(RoleEnums.ROLE_CUSTOMER));

        if (!isCustomer) {
            throw new BadRequestException("Tài khoản này không phải là Khách hàng!");
        }

        user.setStatus(AccountStatus.CUSTOMER_DELETED);
        userRepository.save(user);

        // Thu hồi toàn bộ Refresh Token để buộc tài khoản đó phải đăng xuất ngay lập tức
        refreshTokenRepository.findByPhoneNumber(user.getPhoneNumber())
                .ifPresent(token -> {
                    token.setInvoke(true);
                    refreshTokenRepository.save(token);
                });
    }

    @Override
    public void driverSelfDisableAccount(String phoneNumber) {
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin tài khoản Tài xế!"));

        // Kiểm tra xem có đúng là Tài xế không
        boolean isDriver = user.getRoles().stream()
                .anyMatch(role -> role.getRoleName().equals(RoleEnums.ROLE_DRIVER));

        if (!isDriver) {
            throw new BadRequestException("Tài khoản này không phải là Tài xế!");
        }

        user.setStatus(AccountStatus.DRIVER_DELETED);
        userRepository.save(user);

        // Thu hồi toàn bộ Refresh Token để buộc tài khoản đó phải đăng xuất ngay lập tức
        refreshTokenRepository.findByPhoneNumber(user.getPhoneNumber())
                .ifPresent(token -> {
                    token.setInvoke(true);
                    refreshTokenRepository.save(token);
                });
    }

}
