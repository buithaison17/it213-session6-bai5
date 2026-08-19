package com.example.bai5.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {
    @Async("auditTaskExecutor")
    public void writeAuditLog(String toAccount, double amount) {
        // Lấy thông tin xác thực từ SecurityContext (không còn bị null)
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String currentUser = (authentication != null) ? authentication.getName() : "ANONYMOUS_USER";

        System.out.printf("[%s] Audit Log: Processing transaction initiated by user: %s%n",
                Thread.currentThread().getName(), currentUser);

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Audit log task interrupted: " + e.getMessage());
        }

        System.out.printf("[%s] Audit Log: Successfully logged transfer of %.2f to account %s%n",
                Thread.currentThread().getName(), amount, toAccount);
    }
}
