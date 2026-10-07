package ra.demo.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ra.demo.constains.TransactionType;
import ra.demo.entity.Wallet;
import ra.demo.entity.WalletTransaction;
import ra.demo.exception.BadRequestException;
import ra.demo.exception.ResourceNotFoundException;
import ra.demo.repository.WalletRepository;
import ra.demo.repository.WalletTransactionRepository;
import ra.demo.service.PaymentService;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;

    private static final BigDecimal COMMISSION_RATE = new BigDecimal("0.20");

    // 1. GIỮ TIỀN (HOLD) khi khách tạo Trip (WALLET)
    @Override
    @Transactional
    public void holdCustomerWallet(Long customerId, Long tripId, BigDecimal fareAmount) {
        Wallet customerWallet = walletRepository.findByUserIdAndUserType(customerId, "CUSTOMER")
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy ví khách hàng ID: " + customerId));

        BigDecimal currentBalance = customerWallet.getBalance() != null ? customerWallet.getBalance() : BigDecimal.ZERO;
        if (currentBalance.compareTo(fareAmount) < 0) {
            log.warn("[HOLD THẤT BẠI] Ví Khách #{}: Số dư khả dụng ({} VNĐ) không đủ giữ {} VNĐ", customerId, currentBalance, fareAmount);
            throw new BadRequestException("Số dư khả dụng không đủ để giữ tiền!");
        }

        // Chuyển tiền từ Balance khả dụng sang HoldBalance
        customerWallet.setBalance(currentBalance.subtract(fareAmount));
        BigDecimal currentHold = customerWallet.getHoldBalance() != null ? customerWallet.getHoldBalance() : BigDecimal.ZERO;
        customerWallet.setHoldBalance(currentHold.add(fareAmount));

        walletRepository.save(customerWallet);

        saveTransaction(customerWallet, tripId, fareAmount,
                TransactionType.HOLD_FOR_TRIP, "Tạm giữ tiền cho chuyến đi #" + tripId);

        log.info("[GIỮ TIỀN VÍ KHÁCH] Khách #{} | Chuyến #{} | Số tiền: {} VNĐ | Dư còn: {} VNĐ | Tạm giữ: {} VNĐ",
                customerId, tripId, fareAmount, customerWallet.getBalance(), customerWallet.getHoldBalance());
    }

    // 2. TRỪ TIỀN HOLD KHÁCH & CỘNG 80% VÍ TÀI XẾ khi Trip COMPLETED (WALLET)
    @Override
    @Transactional
    public void processWalletPaymentOnComplete(Long customerId, Long driverId, Long tripId, BigDecimal fareAmount) {
        // A. Trừ khoản Hold của khách và ghi nhận giao dịch
        Wallet customerWallet = walletRepository.findByUserIdAndUserType(customerId, "CUSTOMER")
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy ví khách ID: " + customerId));

        BigDecimal currentHold = customerWallet.getHoldBalance() != null ? customerWallet.getHoldBalance() : BigDecimal.ZERO;
        customerWallet.setHoldBalance(currentHold.subtract(fareAmount));
        walletRepository.save(customerWallet);

        saveTransaction(customerWallet, tripId, fareAmount.negate(),
                TransactionType.TRIP_PAYMENT, "Thanh toán thành công chuyến đi #" + tripId);

        // Tính toán phân chia doanh thu
        BigDecimal commission = fareAmount.multiply(COMMISSION_RATE); // 20% về Sàn
        BigDecimal driverEarning = fareAmount.subtract(commission);   // 80% về Tài xế

        log.info("[THANH TOÁN VÍ HOÀN THÀNH] Chuyến #{}" +
                        "\n ├── Tổng tiền cước: {} VNĐ" +
                        "\n ├── Trừ tiền tạm giữ Khách #{}: -{} VNĐ (Tạm giữ còn: {} VNĐ)" +
                        "\n ├── Hoa hồng Sàn thu (20%): {} VNĐ" +
                        "\n └── Thu nhập thực nhận Tài xế #{}: {} VNĐ",
                tripId, fareAmount, customerId, fareAmount, customerWallet.getHoldBalance(), commission, driverId, driverEarning);

        // B. Cộng 80% thu nhập cho Ví Tài Xế
        creditDriverEarning(driverId, tripId, fareAmount);
    }

    // 3. CỘNG 80% THU NHẬP CHO TÀI XẾ
    @Override
    @Transactional
    public void creditDriverEarning(Long driverId, Long tripId, BigDecimal fareAmount) {
        Wallet driverWallet = walletRepository.findByUserIdAndUserType(driverId, "DRIVER")
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy ví tài xế ID: " + driverId));

        BigDecimal commission = fareAmount.multiply(COMMISSION_RATE); // 20%
        BigDecimal driverEarning = fareAmount.subtract(commission);   // 80%

        BigDecimal currentBalance = driverWallet.getBalance() != null ? driverWallet.getBalance() : BigDecimal.ZERO;
        driverWallet.setBalance(currentBalance.add(driverEarning));
        walletRepository.save(driverWallet);

        saveTransaction(driverWallet, tripId, driverEarning,
                TransactionType.TRIP_EARNING, "Cộng 80% thu nhập chuyến đi #" + tripId);

        log.info("[CỘNG THU NHẬP TÀI XẾ] Tài xế #{} | Chuyến #{}" +
                        "\n ├── Thu nhập cộng vào ví (+80%): {} VNĐ" +
                        "\n └── Số dư ví tài xế hiện tại: {} VNĐ",
                driverId, tripId, driverEarning, driverWallet.getBalance());
    }

    // 4. TRỪ 20% HOA HỒNG VÍ TÀI XẾ KHI COMPLETED (CASH)
    @Override
    @Transactional
    public void deductDriverCommission(Long driverId, Long tripId, BigDecimal fareAmount) {
        Wallet driverWallet = walletRepository.findByUserIdAndUserType(driverId, "DRIVER")
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy ví tài xế ID: " + driverId));

        BigDecimal commission = fareAmount.multiply(COMMISSION_RATE); // 20%
        BigDecimal currentBalance = driverWallet.getBalance() != null ? driverWallet.getBalance() : BigDecimal.ZERO;

        driverWallet.setBalance(currentBalance.subtract(commission));
        walletRepository.save(driverWallet);

        saveTransaction(driverWallet, tripId, commission.negate(),
                TransactionType.COMMISSION_DEDUCTION, "Trừ 20% hoa hồng chuyến đi tiền mặt #" + tripId);

        log.info("[THANH TOÁN TIỀN MẶT HOÀN THÀNH] Chuyến tiền mặt #{}" +
                        "\n ├── Tổng cước tiền mặt tài xế đã thu từ khách: {} VNĐ" +
                        "\n ├── Hoa hồng Sàn thu (-20%): {} VNĐ" +
                        "\n └── Số dư ví tài xế sau khi trừ hoa hồng: {} VNĐ",
                tripId, fareAmount, commission, driverWallet.getBalance());
    }

    // 5. HOÀN TIỀN GIỮ (UNHOLD) KHI TRIP CANCELLED
    @Override
    @Transactional
    public void refundHoldAmount(Long customerId, Long tripId, BigDecimal fareAmount) {
        Wallet customerWallet = walletRepository.findByUserIdAndUserType(customerId, "CUSTOMER")
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy ví khách ID: " + customerId));

        BigDecimal currentHold = customerWallet.getHoldBalance() != null ? customerWallet.getHoldBalance() : BigDecimal.ZERO;
        BigDecimal currentBalance = customerWallet.getBalance() != null ? customerWallet.getBalance() : BigDecimal.ZERO;

        // Trả lại tiền từ Hold về Balance khả dụng
        customerWallet.setHoldBalance(currentHold.subtract(fareAmount));
        customerWallet.setBalance(currentBalance.add(fareAmount));
        walletRepository.save(customerWallet);

        saveTransaction(customerWallet, tripId, fareAmount,
                TransactionType.UNHOLD_TRIP, "Hoàn trả tiền tạm giữ do hủy chuyến #" + tripId);

        log.info("[HOÀN TIỀN GIỮ KHÁCH] Khách #{} | Chuyến bị hủy #{} | Hoàn: {} VNĐ | Số dư ví hiện tại: {} VNĐ",
                customerId, tripId, fareAmount, customerWallet.getBalance());
    }

    private void saveTransaction(Wallet wallet, Long tripId, BigDecimal amount, TransactionType type, String description) {
        WalletTransaction tx = WalletTransaction.builder()
                .wallet(wallet)
                .tripId(tripId)
                .amount(amount)
                .type(type)
                .status("SUCCESS")
                .description(description)
                .build();
        transactionRepository.save(tx);
    }
}