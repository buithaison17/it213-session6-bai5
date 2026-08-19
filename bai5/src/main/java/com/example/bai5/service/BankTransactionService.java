package com.example.bai5.service;

import org.springframework.stereotype.Service;

@Service
public class BankTransactionService {
    private final AuditLogService auditLogService;

    // Sử dụng Constructor Injection chuẩn Best Practice
    public BankTransactionService(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public void executeTransfer(String toAccount, double amount) {
        // Nghiệp vụ xử lý chuyển tiền trong Core Banking DB
        System.out.printf("[%s] Core Banking: Transferred %.2f to account %s%n",
                Thread.currentThread().getName(), amount, toAccount);

        // Gọi qua instance bean riêng biệt (Spring AOP Proxy chặn và gửi task vào Executor)
        auditLogService.writeAuditLog(toAccount, amount);

        // Luồng chính kết thúc ngay lập tức mà không bị trễ 2 giây
        System.out.printf("[%s] Core Banking: Transfer business logic completed successfully.%n",
                Thread.currentThread().getName());
    }
}