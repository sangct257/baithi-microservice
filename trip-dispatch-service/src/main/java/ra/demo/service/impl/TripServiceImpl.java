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
import ra.demo.dto.response.ApiResponse;
import ra.demo.dto.response.CalculateFareResponse;
import ra.demo.dto.response.TripResponse;
import ra.demo.dto.response.WalletBalanceCheckResponse;
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

    @Value("${services.pricing.url:http://localhost:8083/api/v1/pricing/calculate}")
    private String pricingServiceUrl;

    @Value("${services.payment.url:http://localhost:8084/api/v1/wallets/check-balance}")
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

        // 2. [MỚI] Chuẩn bị request tính giá: Truyền tên địa chỉ ping để Pricing Service tự lấy tọa độ
        CalculateFareRequest fareRequest = CalculateFareRequest.builder()
                .pickupAddress(request.getPickupAddress())
                .dropoffAddress(request.getDropoffAddress())
                .pickupLat(request.getPickupLatitude())
                .pickupLng(request.getPickupLongitude())
                .dropoffLat(request.getDropoffLatitude())
                .dropoffLng(request.getDropoffLongitude())
                .vehicleType(request.getVehicleType())
                .build();

        Double distanceKm = 0.0;
        BigDecimal fareAmount = BigDecimal.ZERO;

        // Lưu trữ tọa độ thực tế sau khi Pricing Service định vị xong
        Double finalPickupLat = request.getPickupLatitude();
        Double finalPickupLng = request.getPickupLongitude();
        Double finalDropoffLat = request.getDropoffLatitude();
        Double finalDropoffLng = request.getDropoffLongitude();

        try {
            log.info("[TRIP SERVICE] Gọi sang Pricing Service tại URL: {}", pricingServiceUrl);
            HttpEntity<CalculateFareRequest> entity = new HttpEntity<>(fareRequest);

            ResponseEntity<ApiResponse<CalculateFareResponse>> responseEntity = restTemplate.exchange(
                    pricingServiceUrl,
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<ApiResponse<CalculateFareResponse>>() {}
            );

            if (responseEntity.getBody() != null && responseEntity.getBody().getData() != null) {
                CalculateFareResponse fareResponse = responseEntity.getBody().getData();
                distanceKm = fareResponse.getDistanceKm();
                fareAmount = fareResponse.getFareAmount();

                // Lấy tọa độ thực tế mà Pricing Service đã tìm được từ địa chỉ ping
                if (fareResponse.getPickupLat() != null) finalPickupLat = fareResponse.getPickupLat();
                if (fareResponse.getPickupLng() != null) finalPickupLng = fareResponse.getPickupLng();
                if (fareResponse.getDropoffLat() != null) finalDropoffLat = fareResponse.getDropoffLat();
                if (fareResponse.getDropoffLng() != null) finalDropoffLng = fareResponse.getDropoffLng();

                log.info("[TÍNH CƯỚC THÀNH CÔNG] Quãng đường: {} km | Cước phí: {} VNĐ", distanceKm, fareAmount);
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
                .pickupLat(finalPickupLat)
                .pickupLng(finalPickupLng)
                .dropoffAddress(request.getDropoffAddress())
                .dropoffLat(finalDropoffLat)
                .dropoffLng(finalDropoffLng)
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
                    new ParameterizedTypeReference<ApiResponse<WalletBalanceCheckResponse>>() {}
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

    @Scheduled(fixedRate = 60000) // 1 phút chạy 1 lần
    @Transactional
    public void autoCancelExpiredTrips() {
        LocalDateTime timeout = LocalDateTime.now().minusMinutes(3);
        List<Trip> expiredTrips = tripRepository.findByStatusAndRequestedAtBefore(TripStatus.Requested, timeout);

        for (Trip trip : expiredTrips) {
            trip.setStatus(TripStatus.Cancelled);
            trip.setCancelReason("Hết thời gian chờ . Hệ thống không tìm thấy tài xế phù hợp!");
            trip.setCancelledBy("Hệ thống");
            tripRepository.save(trip);

            log.info("[HỆ THỐNG HỦY] Chuyến #{} hết hạn tìm tài xế (quá 3 phút)", trip.getId());

            if ("WALLET".equalsIgnoreCase(String.valueOf(trip.getPaymentMethod()))) {
                TripCanceledEvent event = TripCanceledEvent.builder()
                        .tripId(trip.getId())
                        .customerId(trip.getCustomerId())
                        .fareAmount(trip.getFareAmount())
                        .paymentMethod("WALLET")
                        .cancelReason("Khôn tìm thấy tài xế sau 3 phút")
                        .cancelledBy("SYSTEM")
                        .build();
                kafkaTemplate.send("trip-canceled-topic", event);
                log.info("[KAFKA SEND] Đã gửi Event Hoàn tiền giữ do Timeout Chuyến #{}", trip.getId());
            }
        }
    }

    private TripResponse mapToResponse(Trip trip) {
        return TripResponse.builder()
                .tripId(trip.getId())
                .customerId(trip.getCustomerId())
                .driverId(trip.getDriverId())
                .pickupAddress(trip.getPickupAddress())
                .pickupLatitude(trip.getPickupLat())
                .pickupLongitude(trip.getPickupLng())
                .dropoffAddress(trip.getDropoffAddress())
                .dropoffLatitude(trip.getDropoffLat())
                .dropoffLongitude(trip.getDropoffLng())
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