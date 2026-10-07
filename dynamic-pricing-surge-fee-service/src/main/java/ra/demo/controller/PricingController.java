package ra.demo.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ra.demo.dto.request.CalculateFareRequest;
import ra.demo.dto.response.ApiResponse;
import ra.demo.dto.response.CalculateFareResponse;
import ra.demo.service.impl.PricingServiceImpl;

@RestController
@RequestMapping("/api/v1/pricing")
@RequiredArgsConstructor
public class PricingController {

    private final PricingServiceImpl pricingService;

    @PostMapping("/calculate")
    public ResponseEntity<ApiResponse<CalculateFareResponse>> calculateFare(@RequestBody CalculateFareRequest request) {
        return new ResponseEntity<>(new ApiResponse<>(
                true,
                "Đã tính toán khoảng cách, thời gian dự kiến và tổng cước phí chuyến đi thành công",
                pricingService.calculateFare(request),
                null,
                HttpStatus.CREATED
        ),HttpStatus.CREATED);
    }
}
