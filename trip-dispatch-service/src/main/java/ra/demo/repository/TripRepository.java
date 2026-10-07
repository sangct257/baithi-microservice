package ra.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ra.demo.constants.TripStatus;
import ra.demo.entity.Trip;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {

    // Lấy lịch sử chuyến đi của khách hàng (mới nhất xếp trước)
    List<Trip> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    // Lấy lịch sử nhận chuyến của tài xế (mới nhất xếp trước)
    List<Trip> findByDriverIdOrderByCreatedAtDesc(Long driverId);

    // Lấy chi tiết chuyến đi thuộc về đúng khách hàng (dùng khi xem chi tiết/hủy chuyến)
    Optional<Trip> findByIdAndCustomerId(Long id, Long customerId);

    // Kiểm tra khách hàng có đang trong chuyến đi chưa hoàn thành không
    boolean existsByCustomerIdAndStatusIn(Long customerId, Collection<TripStatus> statuses);

    // Kiểm tra tài xế có đang trong chuyến đi chưa hoàn thành không
    boolean existsByDriverIdAndStatusIn(Long driverId, Collection<TripStatus> statuses);

    List<Trip> findByStatusAndRequestedAtBefore(TripStatus tripStatus, LocalDateTime timeout);
}