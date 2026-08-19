# BÁO CÁO PHÂN TÍCH CHUYÊN SÂU NGUYÊN NHÂN GỐC RỄ (ROOT CAUSE ANALYSIS)

**Dự án:** RikkeiPay - Hệ thống Xử lý Giao dịch Chuyển khoản  
**Chủ đề:** Lỗi Self-Invocation & Hiện tượng Trôi Ngữ cảnh Security trong Xử lý Bất đồng bộ (`@Async`)  
**Tác giả:** Đội ngũ Kỹ thuật Backend RikkeiPay  
**Ngày lập:** 19/08/2026  

---

## 1. TỔNG QUAN VẤN ĐỀ (PROBLEM OVERVIEW)

Trong module xử lý giao dịch tài chính của RikkeiPay, luồng nghiệp vụ ghi nhận nhật ký kiểm toán (*Audit Log*) được thiết kế để chạy bất đồng bộ (`@Async`) nhằm tối ưu độ trễ (latency) của API chuyển tiền chính (`executeTransfer`). Tuy nhiên, trong quá trình vận hành thử nghiệm, hệ thống gặp phải hai lỗi nghiêm trọng:

1. **Lỗi chặn luồng chính (Main Thread Blocking):** Client gọi API chuyển tiền vẫn bị block đúng 2 giây thay vì phản hồi ngay lập tức, chứng minh tiến trình `@Async` đang thực thi hoàn toàn đồng bộ (*synchronous*).
2. **Lỗi sập ứng dụng do mất ngữ cảnh xác thực (Security Context Loss):** Khi cố gắng truy xuất định danh người dùng từ `SecurityContextHolder.getContext().getAuthentication().getName()`, hệ thống phát sinh lỗi `NullPointerException` (hoặc trả về `null`).

Dưới đây là phân tích chi tiết bản chất kỹ thuật ở tầng kiến trúc Spring Framework và Java Virtual Machine (JVM).

---

## 2. PHÂN TÍCH NGUYÊN NHÂN LỖI 1: SELF-INVOCATION (BỎ QUA SPRING AOP PROXY)

### 2.1. Bản chất cơ chế hoạt động của `@Async` trong Spring AOP

Spring Framework không can thiệp trực tiếp vào mã bytecode của target class tại thời điểm runtime (trừ khi sử dụng AspectJ compile-time / load-time weaving). Thay vào đó, Spring quản lý các cross-cutting concerns (như `@Async`, `@Transactional`, `@Secured`, `@Cacheable`) thông qua mô hình **Proxy Pattern** (CGLIB Dynamic Subclassing hoặc JDK Dynamic Proxies).

* Khi Spring IoC Container khởi tạo một bean có chứa phương thức đánh dấu `@Async` (và ứng dụng có `@EnableAsync`), Spring tự động bọc (*wrap*) instance thực tế (*Target Instance*) bên trong một **AOP Proxy Object** (`AsyncExecutionInterceptor`).
* **Kỳ vọng khi gọi qua Proxy:**
  ```text
  [Client / External Caller]
              │
              ▼
    ┌──────────────────┐
    │  AOP Proxy Bean  │ ──► [AsyncExecutionInterceptor]
    └──────────────────┘         │
              │                  │ 1. Đóng gói method call thành Runnable/Callable
              │                  │ 2. Submit task vào TaskExecutor / ThreadPool
              │                  ▼
              │            [New Thread from Pool] ──► [Target Instance.writeAuditLog()]
              ▼
    [Returns Immediately to Caller]
  ```

### 2.2. Cơ chế thất bại khi gọi nội bộ (`this.writeAuditLog(...)`)

Trong đoạn code gốc:
```java
public void executeTransfer(String toAccount, double amount) {
    System.out.println("Core Banking: Transferred " + amount + " to account " + toAccount);
    
    // GỌI NỘI BỘ (SELF-INVOCATION)
    this.writeAuditLog(toAccount, amount);
}
```

* **Vấn đề cốt lõi:** Con trỏ `this` trong Java đại diện trực tiếp cho tham chiếu bộ nhớ của instance hiện tại (*Target Object*), **hoàn toàn không phải là Proxy Object** mà Spring quản lý trong ApplicationContext.
* **Quy trình thực thi thực tế:**
  ```text
  [External Caller] ──► [Proxy.executeTransfer()]
                              │
                              ▼
                      [Target Instance.executeTransfer()]
                              │
                              │ (Gọi `this.writeAuditLog(...)` trực tiếp trong RAM)
                              ▼
                      [Target Instance.writeAuditLog()]  <── BỎ QUA HOÀN TOÀN PROXY!
                              │
                              ├─► Thực thi `Thread.sleep(2000)` trên CHÍNH Main Thread
                              └─► Phản hồi bị block 2 giây (Chạy đồng bộ 100%)
  ```
* **Hậu quả:** `AsyncExecutionInterceptor` không được kích hoạt; method `writeAuditLog` được thực thi tuần tự trên cùng một Call Stack của luồng xử lý HTTP request chính (`http-nio-8080-exec-*`).

---

## 3. PHÂN TÍCH NGUYÊN NHÂN LỖI 2: HIỆN TƯỢNG TRÔI NGỮ CẢNH THREAD (SECURITY CONTEXT LOSS)

### 3.1. Cấu trúc lưu trữ dữ liệu của `SecurityContextHolder`

Theo mặc định của Spring Security, chiến lược lưu trữ ngữ cảnh xác thực (`SecurityContextHolderStrategy`) được thiết lập là `MODE_THREADLOCAL`.

* Bản chất cơ chế `ThreadLocal`:
  * Mỗi `Thread` trong JVM sở hữu một biến nội tại `threadLocals` thuộc kiểu `ThreadLocal.ThreadLocalMap`.
  * Khi client gửi HTTP Request, filter chain của Spring Security (`SecurityContextPersistenceFilter` hoặc `SecurityContextHolderFilter`) xác thực token/session và lưu `SecurityContext` vào `ThreadLocalMap` của luồng xử lý request hiện tại (ví dụ `Thread-A`).
  * `SecurityContextHolder.getContext()` thực chất là thao tác `Thread.currentThread().threadLocals.get(...)`.

### 3.2. Sự xung đột giữa ThreadLocal và Cơ chế Thread Pool bất đồng bộ

Khi một phương thức `@Async` được thực thi đúng cách (tách ra luồng con), task sẽ được gửi vào một Worker Thread trong Thread Pool (ví dụ `Thread-B` / `AuditThread-1`).

```text
       LUỒNG REQUEST CHÍNH (Thread-A)                   THREAD POOL BẤT ĐỒNG BỘ (Thread-B)
┌──────────────────────────────────────────┐        ┌──────────────────────────────────────────┐
│ Thread.currentThread() = Thread-A         │        │ Thread.currentThread() = Thread-B         │
│                                          │        │                                          │
│ threadLocals:                            │        │ threadLocals:                            │
│ ┌──────────────────────────────────────┐ │        │ ┌──────────────────────────────────────┐ │
│ │ Key: SecurityContextHolder           │ │        │ │ Key: SecurityContextHolder           │ │
│ │ Value: Authentication [User: SonBT]  │ │   X    │ │ Value: NULL (Chưa được gán)          │ │
│ └──────────────────────────────────────┘ │        │ └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘        └──────────────────────────────────────────┘
                     │                                                   │
                     │  Submit Task (Runnable)                           │
                     └──────────────────────────────────────────────────►│
                                                                         ▼
                                                       SecurityContextHolder.getContext()
                                                                         │
                                                                         ▼
                                                           getAuthentication() == null
                                                                         │
                                                                         ▼
                                                           NÉM RA NullPointerException!
```

### 3.3. Vì sao không dùng `MODE_INHERITABLETHREADLOCAL` làm giải pháp mặc định?

Một số lập trình viên cố gắng cấu hình `SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL)`. Tuy nhiên, đây là một **anti-pattern** nguy hiểm trong môi trường Thread Pool:
1. `InheritableThreadLocal` chỉ sao chép dữ liệu từ thread cha sang thread con **tại thời điểm khởi tạo thread mới** (`new Thread()`).
2. Trong môi trường doanh nghiệp sử dụng **Thread Pool** (như `ThreadPoolTaskExecutor`), các luồng được khởi tạo sẵn và tái sử dụng liên tục (*thread reuse*).
3. **Hệ quả rò rỉ bảo mật (Security Leak / Context Poisoning):**
   * Thread con sẽ giữ mãi `SecurityContext` của request đầu tiên tạo ra nó.
   * Khi các request của người dùng khác được dispatch vào luồng này sau đó, hệ thống sẽ đọc nhầm danh tính của người dùng trước, dẫn đến sai lệch dữ liệu kiểm toán nghiêm trọng.

---

## 4. GIẢI PHÁP KIẾN TRÚC VÀ KHUYẾN NGHỊ (ARCHITECTURAL RECOMMENDATIONS)

Để khắc phục triệt để cả hai vấn đề trên theo tiêu chuẩn Clean Architecture và Defensive Programming:

1. **Khắc phục Lỗi 1 (Self-Invocation):**
   * **Nguyên tắc Single Responsibility (SRP):** Tách biệt `AuditLogService` thành một Spring Bean độc lập.
   * Khi `BankTransactionService` gọi `auditLogService.writeAuditLog(...)`, lời gọi đi qua Proxy của `AuditLogService`, kích hoạt chính xác `AsyncExecutionInterceptor`.

2. **Khắc phục Lỗi 2 (Context Propagation):**
   * Sử dụng `DelegatingSecurityContextAsyncTaskExecutor` (hoặc `DelegatingSecurityContextExecutor`) để bọc quanh `ThreadPoolTaskExecutor`.
   * **Cơ chế hoạt động:** Tại thời điểm `execute()` / `submit()`, wrapper sẽ snapshot `SecurityContext` từ luồng cha và chủ động đính kèm vào luồng con trước khi chạy task, sau đó dọn dẹp sạch sẽ (`clearContext()`) ngay khi task kết thúc nhằm ngăn ngừa rò rỉ bộ nhớ và rò rỉ ngữ cảnh.

---

## 5. TỔNG KẾT SO SÁNH

| Tiêu chí | Trước khi Refactor (Mã lỗi) | Sau khi Refactor (Chuẩn kiến trúc) |
| :--- | :--- | :--- |
| **Cơ chế gọi `@Async`** | Tự gọi nội bộ (`this.writeAuditLog`) | Ủy quyền qua Bean trung gian (`AuditLogService`) |
| **AOP Proxy** | Bị bỏ qua hoàn toàn | Proxy chặn và đẩy task vào Executor |
| **Thời gian phản hồi API** | Bị block ~2000ms | Phản hồi tức thì (~10 - 20ms) |
| **Quản lý Security Context** | ThreadLocal riêng biệt -> `null` NPE | Được clone an toàn qua `DelegatingSecurityContextAsyncTaskExecutor` |
| **Tính an toàn Thread Pool** | Nguy cơ NPE hoặc nhầm context | Cách ly hoàn toàn, tự động dọn dẹp sau khi kết thúc task |
