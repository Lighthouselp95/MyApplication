# KẾ HOẠCH TÁI CẤU TRÚC

## MỤC TIÊU
- App hoạt động ổn định trên **Realme Android 16**
- **Tinh gọn**: loại bỏ code chết, giảm số lượng file
- **Hiệu quả**: WorkManager là keep-alive chính, AlarmManager/JobScheduler dự phòng
- **Không mất dữ liệu**: queue retry khi mất mạng

## FILE CẦN SỬA

| File | Hành động |
|---|---|
| AndroidManifest.xml | Sửa foregroundServiceType → "specialUse" |
| MainActivity.kt | Thêm POST_NOTIFICATIONS, sửa while(true) → repeatOnLifecycle |
| BackgroundMonitoringService.kt | Xử lý lỗi thiếu POST_NOTIFICATIONS |
| AppLifecycleManager.kt | Khôi phục WorkManager + schedule JobScheduler |
| AppStorage.kt | Giữ nguyên |
| SmsBatchManager.kt | Thêm queue retry khi thất bại |
| CallEventHandler.kt | Bỏ Thread.sleep(3000) |
| SmsInboxSync.kt | Bỏ Thread.sleep(1000) |
| ServerReporter.kt | Bỏ Thread.sleep(3000), tối ưu |
| BootReceiver.kt | Gọi ensureBackgroundRunning() thay vì chỉ startService |

## FILE CẦN TẠO MỚI

| File | Mục đích |
|---|---|
| FailedSmsQueue.kt | Queue lưu SMS chưa gửi được + retry |
| ConnectivityReceiver.kt | Phát hiện mạng quay lại để retry |

## FILE CẦN XÓA (code chết/không dùng)

| File | Lý do |
|---|---|
| ServiceWatchdog.kt | Toàn bộ code bị comment, không dùng |
| ServiceWatchdogReceiver.kt | Không ai gọi schedule, không bao giờ nhận broadcast |
| AppHealthMonitor.kt | Chỉ đọc/ghi SharedPreferences, có thể inline |
| AppContextHolder.kt | Chỉ lưu context - dùng applicationContext trực tiếp |