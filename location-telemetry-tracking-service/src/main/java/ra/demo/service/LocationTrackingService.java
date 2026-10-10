package ra.demo.service;

import ra.demo.dto.request.DriverLocationUpdateRequest;
import ra.demo.dto.response.NearbyDriverResponse;

import java.util.List;

public interface LocationTrackingService {
    void updateDriverLocation(DriverLocationUpdateRequest request);
    List<NearbyDriverResponse> findNearbyDrivers(Double latitude, Double longitude, Double radiusKm);
    String getDriverCurrentLocation(String driverId);
    void removeDriverLocation(String driverId);
}