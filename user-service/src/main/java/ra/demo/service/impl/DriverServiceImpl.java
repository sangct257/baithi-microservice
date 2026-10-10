package ra.demo.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ra.demo.constants.DriverStatus;
import ra.demo.dto.request.DriverLocationUpdateRequest;
import ra.demo.dto.request.DriverProfileRegisterRequest;
import ra.demo.dto.response.DriverProfileResponse;
import ra.demo.entity.DriverProfile;
import ra.demo.entity.User;
import ra.demo.entity.Vehicle;
import ra.demo.exception.BadRequestException;
import ra.demo.exception.ConflictException;
import ra.demo.exception.ResourceNotFoundException;
import ra.demo.repository.DriverProfileRepository;
import ra.demo.repository.UserRepository;
import ra.demo.repository.VehicleRepository;
import ra.demo.service.DriverService;

@Service
@RequiredArgsConstructor
@Slf4j
public class DriverServiceImpl implements DriverService {

    private final DriverProfileRepository driverProfileRepository;
    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    @Transactional
    public DriverProfileResponse createOrUpdateProfile(Long userId, DriverProfileRegisterRequest request) {
        // 1. Check trùng DriverLicenseNumber (Trừ chính userId này ra)
        if (driverProfileRepository.existsByDriverLicenseNumberAndIdNot(request.getDriverLicenseNumber(), userId)) {
            throw new ConflictException("Số giấy phép lái xe đã được sử dụng bởi tài khoản khác!");
        }

        // 2. Check trùng IdentityCard / CCCD (Trừ chính userId này ra)
        if (driverProfileRepository.existsByIdentityCardAndIdNot(request.getIdentityCard(), userId)) {
            throw new ConflictException("Số CCCD/CMND đã được sử dụng bởi tài khoản khác!");
        }

        // 3. Check trùng Biển số xe (Trừ xe của chính userId này ra)
        if (vehicleRepository.existsByLicensePlateAndDriverProfileIdNot(request.getLicensePlate(), userId)) {
            throw new ConflictException("Biển số xe đã được đăng ký trên hệ thống!");
        }

        // 1. Tìm profile cũ hoặc khởi tạo profile mới dựa trên userId
        DriverProfile profile = driverProfileRepository.findById(userId)
                .orElseGet(() -> {
                    // getReferenceById KHÔNG bắn SQL SELECT, chỉ lấy Entity Proxy để gán FK
                    User userProxy = userRepository.getReferenceById(userId);
                    return DriverProfile.builder()
                            .user(userProxy)
                            .status(DriverStatus.OFFLINE)
                            .rating(5.0)
                            .isApproved(false)
                            .build();
                });

        profile.setDriverLicenseNumber(request.getDriverLicenseNumber());
        profile.setIdentityCard(request.getIdentityCard());

        // 2. Xử lý khởi tạo Vehicle nếu chưa có
        Vehicle vehicle = profile.getVehicle();
        if (vehicle == null) {
            vehicle = new Vehicle();
            vehicle.setDriverProfile(profile); // Gán mối quan hệ 2 chiều
            profile.setVehicle(vehicle);
        }

        // 3. Cập nhật thông tin phương tiện
        vehicle.setLicensePlate(request.getLicensePlate());
        vehicle.setBrand(request.getBrand());
        vehicle.setModel(request.getModel());
        vehicle.setVehicleType(request.getVehicleType());

        // 4. Lưu profile (CascadeType.ALL tự động lưu Vehicle)
        DriverProfile savedProfile = driverProfileRepository.save(profile);
        return mapToResponse(savedProfile);
    }

    @Override
    public DriverProfileResponse getMyProfile(Long userId) {
        DriverProfile profile = driverProfileRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Chưa tìm thấy hồ sơ tài xế. Vui lòng cập nhật thông tin hồ sơ!"));

        return mapToResponse(profile);
    }

    @Override
    @Transactional
    public DriverProfileResponse updateStatus(Long userId) {
        DriverProfile profile = driverProfileRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Chưa tìm thấy hồ sơ tài xế!"));

        // 1. Kiểm tra Admin đã duyệt chưa
        if (!Boolean.TRUE.equals(profile.getIsApproved())) {
            throw new BadRequestException("Hồ sơ tài xế của bạn chưa được Admin phê duyệt. Không thể bật hoạt động!");
        }

        // 2. Tự động đảo trạng thái: ONLINE -> OFFLINE
        DriverStatus newStatus = (profile.getStatus() == DriverStatus.ONLINE)
                ? DriverStatus.OFFLINE
                : DriverStatus.ONLINE;
        profile.setStatus(newStatus);
        driverProfileRepository.save(profile);

        // 2. [BỔ SUNG QUAN TRỌNG] Bắn Kafka đồng bộ sang Location Service
        DriverLocationUpdateRequest syncRequest = DriverLocationUpdateRequest.builder()
                .driverId(String.valueOf(userId))
                .status(newStatus)
                .build();

        kafkaTemplate.send("driver-status-topic", String.valueOf(userId), syncRequest);
        log.info("[DRIVER SERVICE] Đã bắn Event đổi trạng thái tài xế #{}: {}", userId, newStatus);

        return mapToResponse(profile);
    }


    @Transactional
    @Override
    public DriverProfileResponse approveDriverProfile(Long driverId) {
        DriverProfile profile = driverProfileRepository.findById(driverId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hồ sơ tài xế với ID: " + driverId));

        // Đảo ngược trạng thái phê duyệt: true -> false, false -> true
        boolean currentApprovedState = Boolean.TRUE.equals(profile.getIsApproved());
        boolean newApprovedState = !currentApprovedState;

        profile.setIsApproved(newApprovedState);

        // Nếu chuyển thành Hủy duyệt (false) thì ép trạng thái hoạt động về OFFLINE ngay lập tức
        if (!newApprovedState) {
            profile.setStatus(DriverStatus.OFFLINE);
        }

        DriverProfile savedProfile = driverProfileRepository.save(profile);
        return mapToResponse(savedProfile);
    }

    private DriverProfileResponse mapToResponse(DriverProfile profile) {
        Vehicle vehicle = profile.getVehicle();
        User user = profile.getUser();

        return DriverProfileResponse.builder()
                .userId(profile.getId()) // profile.getId() trùng với user.getId() nhờ @MapsId
                .fullName(user != null ? user.getFullName() : null)
                .phoneNumber(user != null ? user.getPhoneNumber() : null)
                .driverLicenseNumber(profile.getDriverLicenseNumber())
                .identityCard(profile.getIdentityCard())
                .status(profile.getStatus())
                .rating(profile.getRating())
                .isApproved(profile.getIsApproved())
                .licensePlate(vehicle != null ? vehicle.getLicensePlate() : null)
                .brand(vehicle != null ? vehicle.getBrand() : null)
                .model(vehicle != null ? vehicle.getModel() : null)
                .vehicleType(vehicle != null ? vehicle.getVehicleType() : null)
                .createdAt(profile.getCreatedAt())
                .build();
    }
}