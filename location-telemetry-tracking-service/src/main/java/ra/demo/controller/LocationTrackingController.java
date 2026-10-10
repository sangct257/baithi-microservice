package ra.demo.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ra.demo.dto.request.DriverLocationUpdateRequest;
import ra.demo.dto.response.ApiResponse;
import ra.demo.dto.response.NearbyDriverResponse;
import ra.demo.service.LocationTrackingService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/location")
public class LocationTrackingController {

    private final LocationTrackingService locationTrackingService;

    public LocationTrackingController(LocationTrackingService locationTrackingService) {
        this.locationTrackingService = locationTrackingService;
    }

    @PostMapping("/update")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateLocation(
            Authentication authentication,
            @RequestBody DriverLocationUpdateRequest request) {

        Long driverId = (Long) authentication.getPrincipal();
        request.setDriverId(String.valueOf(driverId));

        locationTrackingService.updateDriverLocation(request);

        // Trả về dữ liệu chi tiết tọa độ vừa cập nhật
        Map<String, Object> locationData = Map.of(
                "driverId", driverId,
                "latitude", request.getLatitude(),
                "longitude", request.getLongitude(),
                "status", request.getStatus() != null ? request.getStatus().name() : "ONLINE"
        );

        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .success(true)
                .message("Cập nhật tọa độ tài xế thành công")
                .data(locationData)
                .status(HttpStatus.OK)
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/nearby")
    public ResponseEntity<ApiResponse<List<NearbyDriverResponse>>> findNearbyDrivers(
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            @RequestParam(defaultValue = "2.0") Double radiusKm) {

        List<NearbyDriverResponse> drivers = locationTrackingService.findNearbyDrivers(latitude, longitude, radiusKm);

        ApiResponse<List<NearbyDriverResponse>> response = ApiResponse.<List<NearbyDriverResponse>>builder()
                .success(true)
                .message("Tìm thấy " + drivers.size() + " tài xế xung quanh")
                .data(drivers)
                .status(HttpStatus.OK)
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/driver/{driverId}")
    public ResponseEntity<ApiResponse<String>> getCurrentDriverLocation(@PathVariable String driverId) {
        String location = locationTrackingService.getDriverCurrentLocation(driverId);

        ApiResponse<String> response = ApiResponse.<String>builder()
                .success(true)
                .message("Lấy vị trí tài xế thành công")
                .data(location)
                .status(HttpStatus.OK)
                .build();

        return ResponseEntity.ok(response);
    }
}