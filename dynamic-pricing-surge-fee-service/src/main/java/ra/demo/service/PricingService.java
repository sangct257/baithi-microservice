package ra.demo.service;

import ra.demo.dto.request.CalculateFareRequest;
import ra.demo.dto.response.CalculateFareResponse;


public interface PricingService {
    /**
     * Tính toán khoảng cách, thời gian dự kiến và tổng cước phí chuyến đi
     */
    CalculateFareResponse calculateFare(CalculateFareRequest request);
}
