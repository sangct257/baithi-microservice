package ra.demo.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ra.demo.constants.VehicleType;
import ra.demo.dto.request.CalculateFareRequest;
import ra.demo.dto.response.CalculateFareResponse;
import ra.demo.dto.response.GeocodingResponse;
import ra.demo.exception.ResourceNotFoundException;
import ra.demo.service.PricingService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PricingServiceImpl implements PricingService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final StringRedisTemplate redisTemplate;

    @Value("${osrm.url:http://router.project-osrm.org/route/v1/driving}")
    private String osrmUrl;

    @Override
    public CalculateFareResponse calculateFare(CalculateFareRequest request) {
        Double pickupLat = request.getPickupLat();
        Double pickupLng = request.getPickupLng();
        Double dropoffLat = request.getDropoffLat();
        Double dropoffLng = request.getDropoffLng();

        // 1. Tự động chuyển Tên địa chỉ -> Tọa độ chuẩn
        if (request.getPickupAddress() != null && !request.getPickupAddress().isBlank()) {
            GeocodingResponse pickupGeo = getCoordinatesFromAddress(request.getPickupAddress());
            pickupLat = pickupGeo.getLat();
            pickupLng = pickupGeo.getLng();
        }

        if (request.getDropoffAddress() != null && !request.getDropoffAddress().isBlank()) {
            GeocodingResponse dropoffGeo = getCoordinatesFromAddress(request.getDropoffAddress());
            dropoffLat = dropoffGeo.getLat();
            dropoffLng = dropoffGeo.getLng();
        }

        if (pickupLat == null || pickupLng == null || dropoffLat == null || dropoffLng == null) {
            throw new ResourceNotFoundException("Vui lòng nhập đầy đủ địa chỉ hoặc tọa độ điểm đón/trả!");
        }

        // 2. Lấy khoảng cách & thời gian từ OSRM Map
        Map<String, Object> mapData = getRouteFromOsrm(pickupLat, pickupLng, dropoffLat, dropoffLng);
        Double distanceKm = (Double) mapData.get("distanceKm");
        Integer durationMinutes = (Integer) mapData.get("durationMinutes");

        // 3. Hệ số tăng giá (Surge Multiplier)
        Double surgeMultiplier = calculateDynamicSurge(pickupLat, pickupLng);

        // 4. Tính tiền cước
        BigDecimal fareAmount = computeFare(distanceKm, request.getVehicleType(), surgeMultiplier);

        return CalculateFareResponse.builder()
                .distanceKm(distanceKm)
                .durationMinutes(durationMinutes)
                .fareAmount(fareAmount)
                .surgeMultiplier(surgeMultiplier)
                .pickupLat(pickupLat)
                .pickupLng(pickupLng)
                .dropoffLat(dropoffLat)
                .dropoffLng(dropoffLng)
                .build();
    }

    /**
     * Lấy tọa độ chuẩn xác 100% kết hợp Photon & Nominatim Fallback
     */
    public GeocodingResponse getCoordinatesFromAddress(String address) {
        String queryAddress = address.toLowerCase().contains("việt nam") ? address : address + ", Việt Nam";

        // Thử cách 1: Dùng Photon API (Ưu tiên)
        try {
            String url = UriComponentsBuilder.fromUriString("https://photon.komoot.io/api/")
                    .queryParam("q", queryAddress)
                    .queryParam("limit", "1")
                    .build().toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            if (response.getBody() != null && response.getBody().containsKey("features")) {
                List<Map<String, Object>> features = (List<Map<String, Object>>) response.getBody().get("features");
                if (!features.isEmpty()) {
                    Map<String, Object> geometry = (Map<String, Object>) features.get(0).get("geometry");
                    List<?> coords = (List<?>) geometry.get("coordinates");

                    Double lng = Double.parseDouble(coords.get(0).toString());
                    Double lat = Double.parseDouble(coords.get(1).toString());

                    log.info("[GEOCODING SUCCESS] '{}' -> Lat: {}, Lng: {}", queryAddress, lat, lng);
                    return createGeocodingResponse(lat, lng);
                }
            }
        } catch (Exception e) {
            log.warn("[GEOCODING WARN] Photon API gặp sự cố, chuyển sang Nominatim: {}", e.getMessage());
        }

        // Thử cách 2: Dùng Nominatim OSM
        try {
            String url = UriComponentsBuilder.fromUriString("https://nominatim.openstreetmap.org/search")
                    .queryParam("q", queryAddress)
                    .queryParam("format", "json")
                    .queryParam("limit", "1")
                    .build().toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "DispatchAppService/1.0 (contact@demo.com)");
            ResponseEntity<List> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), List.class);

            if (response.getBody() != null && !response.getBody().isEmpty()) {
                Map<String, Object> firstResult = (Map<String, Object>) response.getBody().get(0);
                Double lat = Double.parseDouble(firstResult.get("lat").toString());
                Double lng = Double.parseDouble(firstResult.get("lon").toString());

                log.info("[NOMINATIM SUCCESS] '{}' -> Lat: {}, Lng: {}", queryAddress, lat, lng);
                return createGeocodingResponse(lat, lng);
            }
        } catch (Exception e) {
            log.error("[GEOCODING ERROR] Không thể định vị địa chỉ '{}': {}", queryAddress, e.getMessage());
        }

        throw new ResourceNotFoundException("Không thể tìm thấy tọa độ cho địa chỉ: " + address);
    }

    private GeocodingResponse createGeocodingResponse(Double lat, Double lng) {
        GeocodingResponse geo = new GeocodingResponse();
        try {
            geo.setLat(lat);
            geo.setLng(lng);
        } catch (NoSuchMethodError | Exception e) {
            try {
                geo.getClass().getMethod("setLat", String.class).invoke(geo, String.valueOf(lat));
                geo.getClass().getMethod("setLonStr", String.class).invoke(geo, String.valueOf(lng));
            } catch (Exception ignored) {}
        }
        return geo;
    }

    /**
     * TÍNH CƯỚC CHI TIẾT + LOG CÔNG THỨC VÀ TOÀN BỘ THÔNG SỐ
     */
    private BigDecimal computeFare(Double distanceKm, VehicleType vehicleType, Double surgeMultiplier) {
        double baseFare = getFallbackBaseFare(vehicleType);
        double perKmRate = getFallbackPerKmRate(vehicleType);

        try {
            String redisBaseFare = (String) redisTemplate.opsForHash().get("pricing:rules:" + vehicleType, "baseFare");
            String redisPerKmRate = (String) redisTemplate.opsForHash().get("pricing:rules:" + vehicleType, "perKmRate");

            if (redisBaseFare != null && redisPerKmRate != null) {
                baseFare = Double.parseDouble(redisBaseFare);
                perKmRate = Double.parseDouble(redisPerKmRate);
            }
        } catch (Exception e) {
            log.warn("[REDIS WARN] Dùng giá mặc định hệ thống");
        }

        // 1. Tính km vượt ngoài 2 km đầu
        double extraKm = Math.max(0.0, distanceKm - 2.0);

        // 2. Tính tổng tiền thô trước khi làm tròn
        double rawFare = (baseFare + (extraKm * perKmRate)) * surgeMultiplier;

        // 3. Làm tròn lên hàng nghìn đồng
        long roundedFare = (long) (Math.ceil(rawFare / 1000.0) * 1000);

        // --- LOG TOÀN BỘ THÔNG TIN VÀ CÔNG THỨC TÍNH CƯỚC ---
        log.info("========== [CHI TIẾT TÍNH GIÁ CƯỚC] ==========");
        log.info("1. Loại xe: {}", vehicleType);
        log.info("2. Quãng đường tổng: {} km | Số km vượt (trên 2km đầu): {} km", distanceKm, extraKm);
        log.info("3. Cấu hình giá: BaseFare (2km đầu) = {} VNĐ | Đơn giá/km vượt = {} VNĐ", baseFare, perKmRate);
        log.info("4. Hệ số Surge (Tăng giá): {}x", surgeMultiplier);
        log.info("5. CÔNG THỨC: Total = (BaseFare + (ExtraKm * PerKmRate)) * Surge");
        log.info("              Total = ({} + ({} * {})) * {} = {} VNĐ", baseFare, extraKm, perKmRate, surgeMultiplier, rawFare);
        log.info("6. GIÁ SAU LÀM TRÒN: {} VNĐ", roundedFare);
        log.info("==============================================");

        return BigDecimal.valueOf(roundedFare);
    }

    private double getFallbackBaseFare(VehicleType vehicleType) {
        if (VehicleType.BIKE.equals(vehicleType)) return 12000.0;
        if (VehicleType.CAR_7_SEAT.equals(vehicleType)) return 20000.0;
        return 15000.0;
    }

    private double getFallbackPerKmRate(VehicleType vehicleType) {
        if (VehicleType.BIKE.equals(vehicleType)) return 4500.0;
        if (VehicleType.CAR_7_SEAT.equals(vehicleType)) return 15000.0;
        return 10000.0;
    }

    private Double calculateDynamicSurge(Double lat, Double lng) {
        try {
            double truncatedLat = Math.floor(lat * 100.0) / 100.0;
            double truncatedLng = Math.floor(lng * 100.0) / 100.0;
            String zoneKey = String.format(java.util.Locale.US, "%.2f_%.2f", truncatedLat, truncatedLng);

            String manualSurge = redisTemplate.opsForValue().get("surge:zone:" + zoneKey);
            if (manualSurge != null) {
                return Double.parseDouble(manualSurge);
            }

            String demandStr = redisTemplate.opsForValue().get("density:demand:" + zoneKey);
            String driverStr = redisTemplate.opsForValue().get("density:driver:" + zoneKey);

            int demandCount = (demandStr != null) ? Integer.parseInt(demandStr) : 1;
            int driverCount = (driverStr != null) ? Integer.parseInt(driverStr) : 1;

            double ratio = (double) demandCount / Math.max(1, driverCount);

            if (ratio >= 3.0) return 2.0;
            if (ratio >= 2.0) return 1.5;
            if (ratio >= 1.2) return 1.2;

            return 1.0;
        } catch (Exception e) {
            return 1.0;
        }
    }

    private Map<String, Object> getRouteFromOsrm(Double lat1, Double lng1, Double lat2, Double lng2) {
        String url = String.format(java.util.Locale.US, "%s/%f,%f;%f,%f?overview=false", osrmUrl, lng1, lat1, lng2, lat2);
        log.info("[OSRM CALL] URL: {}", url);

        double distanceMeters = 0.0;
        double durationSeconds = 0.0;

        try {
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && "Ok".equals(response.get("code"))) {
                List<Map<String, Object>> routes = (List<Map<String, Object>>) response.get("routes");
                if (routes != null && !routes.isEmpty()) {
                    Map<String, Object> route = routes.get(0);
                    distanceMeters = Double.parseDouble(route.get("distance").toString());
                    durationSeconds = Double.parseDouble(route.get("duration").toString());
                }
            }
        } catch (Exception e) {
            log.error("[OSRM ERROR] Lỗi lấy tuyến đường: {}", e.getMessage());
        }

        Double distanceKm = Math.round((distanceMeters / 1000.0) * 10.0) / 10.0;
        Integer durationMinutes = (int) Math.ceil(durationSeconds / 60.0);

        return Map.of("distanceKm", distanceKm, "durationMinutes", durationMinutes);
    }
}