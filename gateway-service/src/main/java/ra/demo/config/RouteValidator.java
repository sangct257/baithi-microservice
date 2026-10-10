package ra.demo.config;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Predicate;

@Component
public class RouteValidator {

    public static final List<String> openApiEndpoints = List.of(
            "/user-service/api/v1/auth/login",
            "/user-service/api/v1/auth/driver/send-otp",
            "/user-service/api/v1/auth/login/driver",
            "/user-service/api/v1/auth/register/customer",
            "/user-service/api/v1/auth/register/driver",
            "/user-service/api/v1/auth/refresh-token",
            "/user-service/api/v1/auth/logout",
            "/eureka");

    public Predicate<ServerHttpRequest> isSecured = request -> openApiEndpoints
            .stream()
            .noneMatch(uri -> request.getURI().getPath().contains(uri));

    public boolean isAuthorized(String path, String method, List<String> roles) {
        // MẶC ĐỊNH: ROLE_ADMIN có FULL quyền truy cập tất cả API
        if (roles != null && roles.contains("ROLE_ADMIN")) {
            return true;
        }

        // 2. Nếu không phải Admin, kiểm tra theo các luật cụ thể bên dưới
        for (RouteRule rule : AUTHORIZATION_RULES) {
            if (rule.matches(path, method)) {
                if (!rule.isAuthorized(roles)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final List<RouteRule> AUTHORIZATION_RULES = List.of(
            new RouteRule("/user-service/api/v1/users/customer/customer-deactivate", List.of("DELETE"), List.of("ROLE_CUSTOMER")),
            new RouteRule("/user-service/api/v1/users/driver/driver-deactivate", List.of("DELETE"), List.of("ROLE_DRIVER")),

            new RouteRule("/user-service/api/v1/driver/profile", List.of("POST", "GET"), List.of("ROLE_DRIVER")),
            new RouteRule("/user-service/api/v1/driver/status", List.of("PATCH"), List.of("ROLE_DRIVER")),

            // ==================== LOCATION TELEMETRY SERVICE (BỔ SUNG) ====================
            // 1. Tài xế cập nhật tọa độ GPS (POST /location-telemetry-tracking-service/api/v1/location/update)
            new RouteRule("/location-telemetry-tracking-service/api/v1/location/update", List.of("POST"), List.of("ROLE_DRIVER")),

            // 2. Tìm tài xế xung quanh (GET /location-telemetry-tracking-service/api/v1/location/nearby) -> Dành cho Khách đặt xe
            new RouteRule("/location-telemetry-tracking-service/api/v1/location/nearby", List.of("GET"), List.of("ROLE_CUSTOMER", "ROLE_ADMIN")),

            // 3. Lấy vị trí hiện tại của 1 tài xế cụ thể
            new RouteRule("/location-telemetry-tracking-service/api/v1/location/driver/*", List.of("GET"), List.of("ROLE_DRIVER", "ROLE_CUSTOMER", "ROLE_ADMIN")),

            // 1. Đặt chuyến đi
            new RouteRule("/trip-dispatch-service/api/v1/trips", List.of("POST"), List.of("ROLE_CUSTOMER")),
            // 2. Lịch sử chuyến đi của khách
            new RouteRule("/trip-dispatch-service/api/v1/trips/customer/history", List.of("GET"), List.of("ROLE_CUSTOMER")),
            // 3. Chi tiết chuyến đi của khách (GET /trips/{tripId})
            new RouteRule("/trip-dispatch-service/api/v1/trips/*", List.of("GET"), List.of("ROLE_CUSTOMER")),
            // 4. Khách hàng hủy chuyến (PUT /trips/{tripId}/cancel hoặc /trips/{tripId}/customer-cancel)
            new RouteRule("/trip-dispatch-service/api/v1/trips/*/customer-cancel", List.of("PUT"), List.of("ROLE_CUSTOMER")),

            // 5. Lịch sử chuyến đi của tài xế
            new RouteRule("/trip-dispatch-service/api/v1/trips/driver/history", List.of("GET"), List.of("ROLE_DRIVER")),
            // 6. Tài xế nhận chuyến (PUT /trips/{tripId}/accept)
            new RouteRule("/trip-dispatch-service/api/v1/trips/*/accept", List.of("PUT"), List.of("ROLE_DRIVER")),
            // 7. Tài xế cập nhật trạng thái (PUT /trips/{tripId}/status)
            new RouteRule("/trip-dispatch-service/api/v1/trips/*/status", List.of("PUT"), List.of("ROLE_DRIVER")),
            // 8. Tài xế hủy chuyến (PUT /trips/{tripId}/driver-cancel)
            new RouteRule("/trip-dispatch-service/api/v1/trips/*/driver-cancel", List.of("PUT"), List.of("ROLE_DRIVER"))
            );
}
