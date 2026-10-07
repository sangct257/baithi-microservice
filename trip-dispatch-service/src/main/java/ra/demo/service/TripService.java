package ra.demo.service;

import ra.demo.dto.request.CreateTripRequest;
import ra.demo.dto.response.TripResponse;

import java.util.List;

public interface TripService {
    // --- Chức năng dành cho KHÁCH HÀNG ---
    // Khách đặt chuyến mới
    TripResponse createTrip(CreateTripRequest request);

    // Khách xem chi tiết 1 chuyến đi của mình
    TripResponse getCustomerTripDetail(Long tripId);

    // Khách xem danh sách lịch sử các chuyến đã đặt
    List<TripResponse> getCustomerTripHistory();

    // Khách hủy chuyến đi
    TripResponse cancelTripByCustomer(Long tripId, String reason);

    // --- Chức năng dành cho TÀI XẾ ---
    // Tài xế chấp nhận đơn đặt xe
    TripResponse acceptTrip(Long tripId);

    // Tài xế cập nhật trạng thái chuyến (Arrived -> InProgress -> Completed)
    TripResponse updateTripStatusByDriver(Long tripId);

    // Tài xế huỷ chuyến
    TripResponse cancelTripByDriver(Long tripId, String reason);

    // Tài xế xem danh sách lịch sử các chuyến đã nhận
    List<TripResponse> getDriverTripHistory();
}
