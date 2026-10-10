package ra.demo.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import ra.demo.constants.TripStatus;
import ra.demo.dto.request.CalculateFareRequest;
import ra.demo.dto.request.CreateTripRequest;
import ra.demo.dto.request.RideRequestEvent;
import ra.demo.dto.response.*;
import ra.demo.entity.Trip;
import ra.demo.event.PaymentHoldEvent;
import ra.demo.event.TripCanceledEvent;
import ra.demo.event.TripCompletedEvent;
import ra.demo.exception.TripException;
import ra.demo.repository.TripRepository;
import ra.demo.security.utils.SecurityUtils;
import ra.demo.service.TripService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripServiceImpl implements TripService {
    private final RestTemplate restTemplate;
    private final TripRepository tripRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${services.pricing-service.url}")
    private String pricingServiceUrl;

    @Value("${services.payment-service.url}")
    private String paymentServiceUrl;

    private static final Set<TripStatus> ACTIVE_STATUSES = Set.of(
            TripStatus.Requested,
            TripStatus.Accepted,
            TripStatus.Arrived,
            TripStatus.InProgress
    );

    // ------------------------------------------------------------------
    // KHÁCH HÀNG (CUSTOMER)
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public TripResponse createTrip(CreateTripRequest request) {
        Long customerId = SecurityUtils.getCurrentUserId();

        // 1. Check khách có chuyến chưa xong không
        if (tripRepository.existsByCustomerIdAndStatusIn(customerId, ACTIVE_STATUSES)) {
            log.warn("[TẠO CHUYẾN THẤT BẠI] Khách #{}: Đang có chuyến đi chưa hoàn thành", customerId);
            throw new TripException("Bạn đang có một chuyến đi chưa hoàn thành. Không thể đặt thêm!");
        }

        // 2. Chuẩn bị request tính giá: Truyền tên địa chỉ để Pricing Service tính tiền & trả về Tọa độ
        CalculateFareRequest fareRequest = CalculateFareRequest.builder()
                .pickupAddress(request.getPickupAddress())
                .dropoffAddress(request.getDropoffAddress())
                .vehicleType(request.getVehicleType())
                .build();

        Double distanceKm = 0.0;
        BigDecimal fareAmount = BigDecimal.ZERO;
        Double pickupLat = null;
        Double pickupLng = null;
        Double dropoffLat = null;
        Double dropoffLng = null;

        try {
            log.info("[TRIP SERVICE] Gọi sang Pricing Service tại URL: {}", pricingServiceUrl);
            HttpEntity<CalculateFareRequest> entity = new HttpEntity<>(fareRequest);

            ResponseEntity<ApiResponse<CalculateFareResponse>> responseEntity = restTemplate.exchange(
                    pricingServiceUrl,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<ApiResponse<CalculateFareResponse>>() {
                    }
            );

            if (responseEntity.getBody() != null && responseEntity.getBody().getData() != null) {
                CalculateFareResponse fareResponse = responseEntity.getBody().getData();
                distanceKm = fareResponse.getDistanceKm();
                fareAmount = fareResponse.getFareAmount();

                // Lấy tọa độ chuẩn hóa đã qua Geocoding từ Pricing Service
                pickupLat = fareResponse.getPickupLat();
                pickupLng = fareResponse.getPickupLng();
                dropoffLat = fareResponse.getDropoffLat();
                dropoffLng = fareResponse.getDropoffLng();

                log.info("[TÍNH CƯỚC THÀNH CÔNG] Quãng đường: {} km | Cước phí: {} VNĐ | Tọa độ đón: {},{}",
                        distanceKm, fareAmount, pickupLat, pickupLng);
            } else {
                throw new TripException("Không thể lấy thông tin cước phí từ hệ thống tính giá.");
            }
        } catch (Exception e) {
            log.error("[LỖI PRICING] Không thể tính giá cước: {}", e.getMessage());
            throw new TripException("Lỗi hệ thống tính giá cước. Vui lòng thử lại sau!");
        }

        // 3. Kiểm tra số dư ví nếu chọn WALLET
        if ("WALLET".equalsIgnoreCase(String.valueOf(request.getPaymentMethod()))) {
            checkCustomerWalletBalance(customerId, fareAmount);
        }

        // 4. Lưu chuyến đi vào Database với tọa độ đã được tự động chuẩn hóa
        Trip trip = Trip.builder()
                .customerId(customerId)
                .pickupAddress(request.getPickupAddress())
                .dropoffAddress(request.getDropoffAddress())
                .vehicleType(request.getVehicleType())
                .paymentMethod(request.getPaymentMethod())
                .distanceKm(distanceKm)
                .fareAmount(fareAmount)
                .status(TripStatus.Requested)
                .requestedAt(LocalDateTime.now())
                .build();

        Trip savedTrip = tripRepository.save(trip);
        log.info("[TẠO CHUYẾN MỚI] Chuyến #{} | Khách #{} | PTTT: {} | Cước: {} VNĐ",
                savedTrip.getId(), customerId, request.getPaymentMethod(), fareAmount);

        // 5. Bắn Event Kafka TẠM GIỮ TIỀN VÍ
        if ("WALLET".equalsIgnoreCase(String.valueOf(request.getPaymentMethod()))) {
            PaymentHoldEvent holdEvent = PaymentHoldEvent.builder()
                    .tripId(savedTrip.getId())
                    .customerId(customerId)
                    .fareAmount(fareAmount)
                    .paymentMethod("WALLET")
                    .build();

            kafkaTemplate.send("trip-created-topic", holdEvent);
            log.info("[KAFKA SEND] Đã gửi Event Giữ tiền cho Chuyến #{}", savedTrip.getId());
        }

        // 6. TÌM TÀI XẾ XUNG QUANH BÁN KÍNH 3.0 KM & BẮN EVENT PHÁT CHUYẾN (DISPATCH)
        if (pickupLat != null && pickupLng != null) {
            try {
                log.info("[DISPATCH START] Bắt đầu tìm tài xế xung quanh cho Chuyến #{}: Lat={}, Lng={}",
                        savedTrip.getId(), pickupLat, pickupLng);

                String locationUrl = String.format(java.util.Locale.US,
                        "http://localhost:8085/api/v1/location/nearby?latitude=%.6f&longitude=%.6f&radiusKm=3.0", pickupLat, pickupLng);

                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.set("X-Auth-User-Id", String.valueOf(customerId));
                headers.set("X-Auth-Roles", "ROLE_CUSTOMER");
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                ResponseEntity<ApiResponse<List<NearbyDriverResponse>>> locationResponse = restTemplate.exchange(
                        locationUrl,
                        HttpMethod.GET,
                        entity,
                        new ParameterizedTypeReference<ApiResponse<List<NearbyDriverResponse>>>() {
                        }
                );

                if (locationResponse.getBody() != null && locationResponse.getBody().getData() != null) {
                    List<NearbyDriverResponse> nearbyDrivers = locationResponse.getBody().getData();

                    if (!nearbyDrivers.isEmpty()) {
                        List<String> candidateDriverIds = nearbyDrivers.stream()
                                .map(NearbyDriverResponse::getDriverId)
                                .toList();

                        log.info("[DISPATCH FOUND] Tìm thấy {} tài xế khả dụng xung quanh Chuyến #{}: Danh sách ID = {}",
                                candidateDriverIds.size(), savedTrip.getId(), candidateDriverIds);

                        RideRequestEvent rideRequestEvent = RideRequestEvent.builder()
                                .tripId(savedTrip.getId())
                                .customerId(customerId)
                                .candidateDriverIds(candidateDriverIds)
                                .pickupAddress(savedTrip.getPickupAddress())
                                .dropoffAddress(savedTrip.getDropoffAddress())
                                .fareAmount(fareAmount)
                                .build();

                        kafkaTemplate.send("ride-request-topic", String.valueOf(savedTrip.getId()), rideRequestEvent);

                        log.info("[KAFKA SUCCESS] Đã bắn tin nổ chuyến sang Kafka topic 'ride-request-topic' cho Chuyến #{} thành công!",
                                savedTrip.getId());
                    } else {
                        log.warn("[DISPATCH NO DRIVER] Không tìm thấy tài xế nào rảnh trong bán kính 3km xung quanh điểm đón của Chuyến #{}",
                                savedTrip.getId());
                    }
                }
            } catch (Exception e) {
                log.error("[DISPATCH ERROR] Thất bại khi tìm tài xế hoặc phát chuyến cho Chuyến #{}. Lý do: {}",
                        savedTrip.getId(), e.getMessage(), e);
            }
        } else {
            log.error("[DISPATCH ABORT] Không thể thực hiện tìm tài xế cho Chuyến #{} do thiếu tọa độ pickupLat/pickupLng!", savedTrip.getId());
        }

        return mapToResponse(savedTrip);
    }

    @Override
    public TripResponse getCustomerTripDetail(Long tripId) {
        Long customerId = SecurityUtils.getCurrentUserId();
        Trip trip = tripRepository.findByIdAndCustomerId(tripId, customerId)
                .orElseThrow(() -> new TripException("Không tìm thấy chuyến đi hoặc bạn không có quyền truy cập!"));
        return mapToResponse(trip);
    }

    @Override
    public List<TripResponse> getCustomerTripHistory() {
        Long customerId = SecurityUtils.getCurrentUserId();
        return tripRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public TripResponse cancelTripByCustomer(Long tripId, String reason) {
        Long customerId = SecurityUtils.getCurrentUserId();
        Trip trip = tripRepository.findByIdAndCustomerId(tripId, customerId)
                .orElseThrow(() -> new TripException("Không tìm thấy chuyến đi!"));

        // Cho phép hủy khi xe chưa bắt đầu chạy (Requested, Accepted, Arrived)
        if (trip.getStatus() == TripStatus.InProgress || trip.getStatus() == TripStatus.Completed || trip.getStatus() == TripStatus.Cancelled) {
            log.warn("[HỦY CHUYẾN THẤT BẠI] Chuyến #{}: Trạng thái hiện tại ({}) không thể hủy", tripId, trip.getStatus());
            throw new TripException("Không thể hủy chuyến đi đang di chuyển hoặc đã hoàn thành/đã hủy!");
        }

        trip.setStatus(TripStatus.Cancelled);
        trip.setCancelReason(reason);
        trip.setCancelledBy("ROLE_CUSTOMER");
        trip.setCancelledAt(LocalDateTime.now());

        Trip savedTrip = tripRepository.save(trip); // Lưu DB thành công trước khi gửi Kafka
        log.warn("[HỦY CHUYẾN THẤT BẠI] Chuyến #{}: Trạng thái hiện tại ({}) không thể hủy", tripId, trip.getStatus());

        // Bắn event Kafka hoàn tiền Hold
        if ("WALLET".equalsIgnoreCase(String.valueOf(savedTrip.getPaymentMethod()))) {
            TripCanceledEvent canceledEvent = TripCanceledEvent.builder()
                    .tripId(savedTrip.getId())
                    .customerId(savedTrip.getCustomerId())
                    .driverId(savedTrip.getDriverId())
                    .fareAmount(savedTrip.getFareAmount())
                    .paymentMethod(String.valueOf(savedTrip.getPaymentMethod()))
                    .cancelReason(reason)
                    .cancelledBy("ROLE_CUSTOMER")
                    .build();

            kafkaTemplate.send("trip-canceled-topic", canceledEvent);
            log.info("[KAFKA SEND] Đã gửi Event Hủy chuyến #{}", savedTrip.getId());
        }

        return mapToResponse(savedTrip);
    }

    // ------------------------------------------------------------------
    // TÀI XẾ (DRIVER)
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public TripResponse acceptTrip(Long tripId) {
        Long driverId = SecurityUtils.getCurrentUserId();

        if (tripRepository.existsByDriverIdAndStatusIn(driverId, ACTIVE_STATUSES)) {
            log.warn("[NHẬN CHUYẾN THẤT BẠI] Tài xế #{}: Đang thực hiện chuyến đi khác", driverId);
            throw new TripException("Bạn đang trong một chuyến đi khác. Không thể nhận thêm đơn!");
        }

        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripException("Chuyến đi không tồn tại!"));

        if (trip.getStatus() != TripStatus.Requested) {
            log.warn("[NHẬN CHUYẾN THẤT BẠI] Chuyến #{}: Trạng thái không hợp lệ ({})", tripId, trip.getStatus());
            throw new TripException("Chuyến đi đã được tài xế khác nhận hoặc đã bị hủy!");
        }

        trip.setDriverId(driverId);
        trip.setStatus(TripStatus.Accepted);
        trip.setAcceptedAt(LocalDateTime.now());
        log.info("[TÀI XẾ NHẬN CHUYẾN] Tài xế #{} | Chuyến #{}", driverId, tripId);
        return mapToResponse(tripRepository.save(trip));
    }

    @Override
    @Transactional
    public TripResponse updateTripStatusByDriver(Long tripId) {
        Long driverId = SecurityUtils.getCurrentUserId();
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripException("Chuyến đi không tồn tại!"));

        if (!driverId.equals(trip.getDriverId())) {
            throw new TripException("Bạn không phải tài xế của chuyến đi này!");
        }

        TripStatus oldStatus = trip.getStatus();

        switch (trip.getStatus()) {
            case Accepted:
                trip.setStatus(TripStatus.Arrived);
                trip.setArrivedAt(LocalDateTime.now());
                break;
            case Arrived:
                trip.setStatus(TripStatus.InProgress);
                trip.setStartedAt(LocalDateTime.now());
                break;
            case InProgress:
                trip.setStatus(TripStatus.Completed);
                trip.setCompletedAt(LocalDateTime.now());
                trip.setPaymentStatus("PAID");
                break;
            default:
                throw new TripException("Không thể cập nhật trạng thái cho chuyến đi này!");
        }

        Trip savedTrip = tripRepository.save(trip);

        log.info("[CẬP NHẬT TRẠNG THÁI] Chuyến #{} | {} -> {}", tripId, oldStatus, savedTrip.getStatus());

        // Bắn Kafka khi hoàn thành chuyến đi
        if (savedTrip.getStatus() == TripStatus.Completed) {
            TripCompletedEvent completedEvent = TripCompletedEvent.builder()
                    .tripId(savedTrip.getId())
                    .customerId(savedTrip.getCustomerId())
                    .driverId(savedTrip.getDriverId())
                    .fareAmount(savedTrip.getFareAmount())
                    .paymentMethod(savedTrip.getPaymentMethod() != null ? String.valueOf(savedTrip.getPaymentMethod()) : "CASH")
                    .build();

            kafkaTemplate.send("trip-completed-topic", completedEvent);
            log.info("[KAFKA SEND] Đã gửi Event Hoàn thành Chuyến #{}", savedTrip.getId());
        }

        return mapToResponse(savedTrip);
    }

    @Override
    @Transactional
    public TripResponse cancelTripByDriver(Long tripId, String reason) {
        Long driverId = SecurityUtils.getCurrentUserId();
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new TripException("Không tìm thấy chuyến đi!"));

        if (trip.getDriverId() == null || !trip.getDriverId().equals(driverId)) {
            throw new TripException("Bạn không phải tài xế nhận chuyến đi này!");
        }

        if (trip.getStatus() == TripStatus.InProgress || trip.getStatus() == TripStatus.Completed || trip.getStatus() == TripStatus.Cancelled) {
            log.warn("[HỦY CHUYẾN THẤT BẠI] Chuyến #{}: Trạng thái hiện tại ({}) không thể hủy", tripId, trip.getStatus());
            throw new TripException("Không thể hủy chuyến đi đang di chuyển hoặc đã hoàn thành/đã hủy!");
        }

        trip.setStatus(TripStatus.Cancelled);
        trip.setCancelReason(reason);
        trip.setCancelledBy("ROLE_DRIVER");
        trip.setCancelledAt(LocalDateTime.now());

        Trip savedTrip = tripRepository.save(trip); // Lưu DB thành công trước khi gửi Kafka
        log.info("[TÀI XẾ HỦY CHUYẾN] Tài xế #{} | Chuyến #{} | Lý do: {}", driverId, tripId, reason);

        // Bắn event Kafka hoàn tiền nếu thanh toán bằng Ví
        if ("WALLET".equalsIgnoreCase(String.valueOf(savedTrip.getPaymentMethod()))) {
            TripCanceledEvent canceledEvent = TripCanceledEvent.builder()
                    .tripId(savedTrip.getId())
                    .customerId(savedTrip.getCustomerId())
                    .driverId(savedTrip.getDriverId())
                    .fareAmount(savedTrip.getFareAmount())
                    .paymentMethod(String.valueOf(savedTrip.getPaymentMethod()))
                    .cancelReason(reason)
                    .cancelledBy("ROLE_DRIVER")
                    .build();

            kafkaTemplate.send("trip-canceled-topic", canceledEvent);
            log.info("[KAFKA SEND] Đã gửi Event Hủy chuyến #{}", savedTrip.getId());
        }

        return mapToResponse(savedTrip);
    }

    @Override
    public List<TripResponse> getDriverTripHistory() {
        Long driverId = SecurityUtils.getCurrentUserId();
        return tripRepository.findByDriverIdOrderByCreatedAtDesc(driverId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // HELPER METHODS
    // ------------------------------------------------------------------

    private void checkCustomerWalletBalance(Long customerId, BigDecimal fareAmount) {
        try {
            String checkUrl = String.format("%s?userId=%d&amount=%s", paymentServiceUrl, customerId, fareAmount.toString());
            log.info("[TRIP SERVICE] Kiểm tra số dư ví tại URL: {}", checkUrl);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            org.springframework.web.context.request.ServletRequestAttributes attributes =
                    (org.springframework.web.context.request.ServletRequestAttributes)
                            org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();

            if (attributes != null) {
                String token = attributes.getRequest().getHeader("Authorization");
                if (token != null) {
                    headers.set("Authorization", token);
                }
            }

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<ApiResponse<WalletBalanceCheckResponse>> balanceResponse = restTemplate.exchange(
                    checkUrl,
                    HttpMethod.GET,
                    entity,
                    new ParameterizedTypeReference<ApiResponse<WalletBalanceCheckResponse>>() {
                    }
            );

            if (balanceResponse.getBody() != null && balanceResponse.getBody().getData() != null) {
                WalletBalanceCheckResponse checkData = balanceResponse.getBody().getData();

                if (!checkData.isEnough()) {
                    java.text.NumberFormat formatter = java.text.NumberFormat.getInstance(new java.util.Locale("vi", "VN"));
                    String missingFormatted = formatter.format(checkData.getMissingAmount());
                    String balanceFormatted = formatter.format(checkData.getCurrentBalance());

                    log.warn("[VÍ KHÔNG ĐỦ] Khách #{}: Có {} VNĐ - Thiếu {} VNĐ", customerId, balanceFormatted, missingFormatted);

                    throw new TripException(String.format(
                            "Số dư ví không đủ! Hiện tại bạn có %s VNĐ, còn thiếu %s VNĐ để thực hiện chuyến đi.",
                            balanceFormatted, missingFormatted
                    ));
                }
            } else {
                throw new TripException("Không thể xác định thông tin số dư ví.");
            }
        } catch (TripException te) {
            throw te;
        } catch (Exception e) {
            log.error("[LỖI WALLET SERVICE] Kiểm tra số dư ví thất bại: {}", e.getMessage());
            throw new TripException("Không thể xác thực số dư ví. Vui lòng thử lại sau!");
        }
    }

    private TripResponse mapToResponse(Trip trip) {
        return TripResponse.builder()
                .tripId(trip.getId())
                .customerId(trip.getCustomerId())
                .driverId(trip.getDriverId())
                .pickupAddress(trip.getPickupAddress())
                .dropoffAddress(trip.getDropoffAddress())
                .vehicleType(trip.getVehicleType())
                .distanceKm(trip.getDistanceKm())
                .fareAmount(trip.getFareAmount())
                .paymentMethod(trip.getPaymentMethod())
                .status(trip.getStatus())
                .cancelReason(trip.getCancelReason())
                .cancelledBy(trip.getCancelledBy())
                .createdAt(trip.getCreatedAt())
                .build();
    }
}