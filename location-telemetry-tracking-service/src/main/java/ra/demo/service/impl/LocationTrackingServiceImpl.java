package ra.demo.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import ra.demo.constants.DriverStatus;
import ra.demo.dto.request.DriverLocationUpdateRequest;
import ra.demo.dto.response.NearbyDriverResponse;
import ra.demo.service.LocationTrackingService;

import java.util.*;

@Service
@Slf4j
public class LocationTrackingServiceImpl implements LocationTrackingService {

    private static final String DRIVER_GEO_KEY = "driver:geo";
    private static final String DRIVER_STATUS_KEY = "driver:status";
    private static final String DRIVER_LOCATION_KEY_PREFIX = "driver:location:";

    private final StringRedisTemplate redisTemplate;

    public LocationTrackingServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void updateDriverLocation(DriverLocationUpdateRequest request) {
        String driverId = request.getDriverId();
        DriverStatus newStatus = request.getStatus();

        // 1. Cập nhật trạng thái mới vào Redis Hash (nếu request có truyền status từ Kafka/API)
        if (newStatus != null) {
            redisTemplate.opsForHash().put(DRIVER_STATUS_KEY, driverId, newStatus.name());
            log.info("[REDIS] Cập nhật status tài xế #{}: {}", driverId, newStatus);
        }

        // 2. Lấy trạng thái hiện tại thực tế trong Redis Hash để kiểm tra
        Object currentStatusObj = redisTemplate.opsForHash().get(DRIVER_STATUS_KEY, driverId);
        String currentStatus = currentStatusObj != null ? currentStatusObj.toString() : DriverStatus.OFFLINE.name();

        // 3. Nếu tài xế OFFLINE -> Dọn dẹp sạch Redis GEO & không ghi nhận tọa độ
        if (DriverStatus.OFFLINE.name().equalsIgnoreCase(currentStatus)) {
            removeDriverLocation(driverId);
            log.info("[REDIS] Tài xế #{} đang OFFLINE. Đã làm sạch dữ liệu vị trí.", driverId);
            return;
        }

        // 4. Nếu tài xế ONLINE và có tọa độ GPS hợp lệ -> Ghi nhận vào Redis GEO
        Double lat = request.getLatitude();
        Double lng = request.getLongitude();

        if (lat != null && lng != null && lat != 0 && lng != 0) {
            // Ghi vào Spatial Index (Redis GEO)
            redisTemplate.opsForGeo().add(
                    DRIVER_GEO_KEY,
                    new Point(lng, lat),
                    driverId
            );

            // Ghi vào Key String vị trí tức thời
            String locationValue = lat + "," + lng;
            redisTemplate.opsForValue().set(DRIVER_LOCATION_KEY_PREFIX + driverId, locationValue);

            log.info("[REDIS GEO] Đã lưu vị trí tài xế #{}: lat={}, lng={}", driverId, lat, lng);
        } else {
            log.debug("[LOCATION UPDATE] Sự kiện đổi status chưa kèm tọa độ GPS cho driver: {}", driverId);
        }
    }

    /**
     * Tìm kiếm danh sách tài xế rảnh (ONLINE) xung quanh tọa độ chỉ định trong bán kính radiusKm.
     *
     * @param latitude  Vĩ độ điểm đón (Khách hàng)
     * @param longitude Kinh độ điểm đón (Khách hàng)
     * @param radiusKm  Bán kính tìm kiếm (km)
     * @return Danh sách tài xế khả dụng kèm khoảng cách và tọa độ hiện tại
     */
    @Override
    public List<NearbyDriverResponse> findNearbyDrivers(Double latitude, Double longitude, Double radiusKm) {
        // 1. Tạo điểm tâm (Center Point) từ tọa độ điểm đón của khách hàng
        Point center = new Point(longitude, latitude);
        Distance radius = new Distance(radiusKm, Metrics.KILOMETERS);

        // 2. Cấu hình câu lệnh GEOSEARCH của Redis
        RedisGeoCommands.GeoSearchCommandArgs args = RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                .includeDistance()    // Trả về khoảng cách từ tâm tới tài xế
                .includeCoordinates() // Trả về tọa độ Lat/Lng hiện tại của tài xế
                .sortAscending()      // Sắp xếp ưu tiên tài xế gần nhất lên đầu
                .limit(20);           // Giới hạn tối đa 20 tài xế gần nhất để tối ưu hiệu năng

        // 3. Thực hiện truy vấn Spatial Search trên Redis GEO
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisTemplate.opsForGeo().search(
                        DRIVER_GEO_KEY,
                        GeoReference.fromCoordinate(center),
                        radius,
                        args
                );

        // Nếu không tìm thấy tài xế nào trong bán kính chỉ định -> Trả về danh sách rỗng
        if (results == null || results.getContent().isEmpty()) {
            log.info("[LOCATION SEARCH] Không tìm thấy tài xế nào trong bán kính {}km xung quanh ({}, {})",
                    radiusKm, latitude, longitude);
            return Collections.emptyList();
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();

        // 4. Trích xuất danh sách Driver ID để chuẩn bị query trạng thái
        List<String> driverIds = content.stream()
                .map(item -> item.getContent().getName())
                .toList();

        // 5. Query batch (MultiGet) từ Redis Hash để lấy trạng thái tất cả tài xế chỉ trong 1 RTT (Round Trip Time)
        List<Object> statusList = redisTemplate.opsForHash().multiGet(DRIVER_STATUS_KEY, new ArrayList<>(driverIds));

        // 6. Map ID -> Status để tra cứu O(1), đảm bảo chính xác tuyệt đối không bị lệch Index
        Map<String, String> driverStatusMap = new HashMap<>();
        for (int i = 0; i < driverIds.size(); i++) {
            Object statusObj = (statusList != null && i < statusList.size()) ? statusList.get(i) : null;
            // Mặc định nếu chưa set status thì coi như ONLINE (hoặc tùy cấu hình nghiệp vụ)
            String status = (statusObj != null) ? statusObj.toString() : DriverStatus.ONLINE.name();
            driverStatusMap.put(driverIds.get(i), status);
        }

        // 7. Lọc các tài xế có trạng thái ONLINE và đóng gói thành List Response
        List<NearbyDriverResponse> nearbyDrivers = new ArrayList<>();

        for (GeoResult<RedisGeoCommands.GeoLocation<String>> resultItem : content) {
            RedisGeoCommands.GeoLocation<String> location = resultItem.getContent();

            String driverId = location.getName();
            Point point = location.getPoint();

            Double distance = (resultItem.getDistance() != null) ? resultItem.getDistance().getValue() : 0.0;
            String status = driverStatusMap.getOrDefault(driverId, DriverStatus.ONLINE.name());

            // Chỉ lấy những tài xế đang trong trạng thái ONLINE (Sẵn sàng nhận chuyến)
            if (DriverStatus.ONLINE.name().equalsIgnoreCase(status)) {
                Double lat = (point != null) ? point.getY() : latitude;
                Double lng = (point != null) ? point.getX() : longitude;

                nearbyDrivers.add(new NearbyDriverResponse(
                        driverId,
                        lat,
                        lng,
                        distance,
                        status
                ));
            }
        }

        log.info("[LOCATION SEARCH SUCCESS] Tìm thấy {} tài xế ONLINE trong bán kính {}km xung quanh ({}, {})",
                nearbyDrivers.size(), radiusKm, latitude, longitude);

        return nearbyDrivers;
    }

    @Override
    public String getDriverCurrentLocation(String driverId) {
        return redisTemplate.opsForValue().get(DRIVER_LOCATION_KEY_PREFIX + driverId);
    }

    @Override
    public void removeDriverLocation(String driverId) {
        redisTemplate.opsForGeo().remove(DRIVER_GEO_KEY, driverId);
        redisTemplate.opsForHash().delete(DRIVER_STATUS_KEY, driverId);
        redisTemplate.delete(DRIVER_LOCATION_KEY_PREFIX + driverId);
    }
}