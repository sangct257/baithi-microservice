package ra.demo.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ra.demo.dto.request.CreateTripRequest;
import ra.demo.dto.response.ApiResponse;
import ra.demo.dto.response.TripResponse;
import ra.demo.service.TripService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;

    // ENDPOINTS CHO KHÁCH HÀNG (CUSTOMER)

    @PostMapping
    public ResponseEntity<ApiResponse<TripResponse>> createTrip(@Valid @RequestBody CreateTripRequest request) {
        TripResponse response = tripService.createTrip(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(
                true,
                "Đặt chuyến đi thành công!",
                response,
                null,
                HttpStatus.CREATED
        ));
    }

    @GetMapping("/{tripId}")
    public ResponseEntity<ApiResponse<TripResponse>> getCustomerTripDetail(@PathVariable Long tripId) {
        TripResponse response = tripService.getCustomerTripDetail(tripId);
        return ResponseEntity.ok(new ApiResponse<>(
                true,
                "Lấy thông tin chi tiết chuyến đi thành công!",
                response,
                null,
                HttpStatus.OK
        ));
    }

    @GetMapping("/customer/history")
    public ResponseEntity<ApiResponse<List<TripResponse>>> getCustomerTripHistory() {
        List<TripResponse> response = tripService.getCustomerTripHistory();
        return ResponseEntity.ok(new ApiResponse<>(
                true,
                "Lấy lịch sử chuyến đi của khách hàng thành công!",
                response,
                null,
                HttpStatus.OK
        ));
    }

    @PutMapping("/{tripId}/customer-cancel")
    public ResponseEntity<ApiResponse<TripResponse>> cancelTripByCustomer(
            @PathVariable Long tripId,
            @RequestParam(required = false, defaultValue = "Khách hàng hủy chuyến") String reason) {
        TripResponse response = tripService.cancelTripByCustomer(tripId, reason);
        return ResponseEntity.ok(new ApiResponse<>(
                true,
                "Khách hàng hủy chuyến đi thành công!",
                response,
                null,
                HttpStatus.OK
        ));
    }

    // ENDPOINTS CHO TÀI XẾ (DRIVER)

    @PutMapping("/{tripId}/accept")
    public ResponseEntity<ApiResponse<TripResponse>> acceptTrip(@PathVariable Long tripId) {
        TripResponse response = tripService.acceptTrip(tripId);
        return ResponseEntity.ok(new ApiResponse<>(
                true,
                "Tài xế nhận chuyến đi thành công!",
                response,
                null,
                HttpStatus.OK
        ));
    }

    @PutMapping("/{tripId}/status")
    public ResponseEntity<ApiResponse<TripResponse>> updateTripStatus(@PathVariable Long tripId) {
        TripResponse response = tripService.updateTripStatusByDriver(tripId);
        return ResponseEntity.ok(new ApiResponse<>(
                true,
                "Cập nhật trạng thái chuyến đi thành: " + response.getStatus(),
                response,
                null,
                HttpStatus.OK
        ));
    }

    @PutMapping("/{tripId}/driver-cancel")
    @PreAuthorize("hasAuthority('ROLE_DRIVER')")
    public ResponseEntity<ApiResponse<TripResponse>> cancelTripByDriver(
            @PathVariable Long tripId,
            @RequestParam(required = false, defaultValue = "Tài xế hủy chuyến") String reason) {
        TripResponse response = tripService.cancelTripByDriver(tripId, reason);
        return ResponseEntity.ok(new ApiResponse<>(
                true,
                "Tài xế hủy chuyến đi thành công!",
                response,
                null,
                HttpStatus.OK
        ));
    }

    @GetMapping("/driver/history")
    public ResponseEntity<ApiResponse<List<TripResponse>>> getDriverTripHistory() {
        List<TripResponse> response = tripService.getDriverTripHistory();
        return ResponseEntity.ok(new ApiResponse<>(
                true,
                "Lấy lịch sử chuyến đi của tài xế thành công!",
                response,
                null,
                HttpStatus.OK
        ));
    }
}