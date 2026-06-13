# PHÂN TÍCH LỖI & TỐI ƯU CHO REALME ANDROID 16

## Câu hỏi: Mỗi khi mở tin nhắn mới có reset 1M token không?

**KHÔNG.** Token bảo mật (`token` trong SharedPreferences `AppConfig`) **không bao giờ bị reset** khi nhận SMS mới. Token chỉ bị thay đổi trong 2 trường hợp:
1. User bấm "Kích hoạt" / "Lưu" → ghi token mới
2. User bấm "Hủy mã" → set token = "" và gửi RESET event

Nếu bạn hỏi về context window của tôi (1M tokens): **KHÔNG**, mỗi tin nhắn mới trong chat này **không reset** context window. Token count tích lũy dần qua các message, chỉ reset khi bắt đầu một phiên hoàn toàn mới.

---

## 1. VẤN ĐỀ CHẾT NGƯỜI TRÊN REALME ANDROID 16 (API 36)

### 1.1. START_STICKY BỊ REALME IGNORE HOÀN TOÀN ❌

```kotlin
// BackgroundMonitoringService.kt - dòng 117
return START_STICKY
```

**Vấn đề**: Realme/ColorOS **KHÔNG tôn trọng** `START_STICKY`. Khi Realme kill service (sau 1-3 phút trong danh sách "Freeze"), hệ thống sẽ **không bao giờ** tự động restart service.

**Hậu quả**: Service chết vĩnh viễn. Cơ chế phục hồi duy nhất còn lại là AlarmManager, nhưng...

### 1.2. ALARM BỊ REALME CHẶN HOẶC DELAY ❌

```kotlin
// AlarmReceiver.kt - dòng 61
alarmManager.setExactAndAllowWhileIdle(...)
```

**Vấn đề**: Realme ColorOS có cơ chế "Smart Standby" và "App Freeze" sẽ:
- Trì hoãn tất cả alarms từ app không nằm trong danh sách "Auto Launch" (Tự động khởi chạy)
- `setExactAndAllowWhileIdle` không hoạt động trên Realme nếu app bị đóng băng
- App có quyền `SCHEDULE_EXACT_ALARM` nhưng Realme ghi đè bằng "Smart Standby"

**Hậu quả**: AlarmManager không đánh thức được → không có keep-alive → app chết hẳn sau 1-3 phút.

### 1.3. THIẾU "AUTO LAUNCH" TO QUYỀN SỐNG CÒN ❌

Realme có 3 toggle quan trọng trong "Quản lý pin" cho mỗi app:
1. **Tự động khởi chạy (Auto Launch)** - Cho phép app tự động chạy nền
2. **Chạy nền (Background Activity)** - Cho phép service chạy nền
3. **Đóng băng (Freeze)** - Nếu bật, app sẽ bị đóng băng khi không dùng

**Code hiện tại**: `checkAndRequestBatteryOptimization()` chỉ mở settings `ACTION_APPLICATION_DETAILS_SETTINGS` với hướng dẫn chung chung. **Không có hướng dẫn cụ thể cho Realme** để bật Auto Launch.

### 1.4. FOREGROUND SERVICE TYPE SAI TRÊN ANDROID 16 ❌

```xml
android:foregroundServiceType="dataSync"
```

**Vấn đề**: Android 16 (API 36) yêu cầu foreground service type phải khớp với mục đích thực tế. Service này làm monitoring (giám sát SMS/cuộc gọi), không phải data sync. Android 16 có thể:
- Từ chối startForeground vì type không phù hợp
- Kill service vì lạm dụng foreground service

**Fix**: Cần dùng `specialUse` thay vì `dataSync`.

### 1.5. THIẾU QUYỀN POST_NOTIFICATIONS TRÊN ANDROID 16 ❌

```kotlin
// BackgroundMonitoringService.kt - dòng 179
val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
if (manager.getNotificationChannel(channelId) == null) {
    val channel = NotificationChannel(channelId, "Dịch vụ nền", NotificationManager.IMPORTANCE_MIN)
    ...
    manager.createNotificationChannel(channel)
}
```

**Vấn đề**: Từ Android 14 (API 34), `startForeground()` yêu cầu quyền `POST_NOTIFICATIONS` phải được cấp. Trên Android 16, nếu chưa cấp quyền này, `startForeground()` sẽ ném **SecurityException**.

**Code hiện tại**: `POST_NOTIFICATIONS` bị LOẠI khỏi danh sách quyền tự động xin (xem dòng 619 MainActivity: `// Loại bỏ quyền POST_NOTIFICATIONS khỏi danh sách tự động xin quyền`).

**Hậu quả**: Service không thể start foreground → bị kill ngay lập tức.

### 1.6. THREAD.SLEEP KHÔNG PHÙ HỢP ❌

```kotlin
// CallEventHandler.kt - dòng 87
Thread.sleep(3_000)

// ServerReporter.kt - dòng 83
Thread.sleep(3_000L)

// SmsInboxSync.kt - dòng 36
Thread.sleep(1000)

// SmsBatchManager.kt - dòng 40
Thread.sleep(200)
```

**Vấn đề**: Trên Android 16, các thread.sleep() trong background thread vẫn có thể gây ra:
- ANR nếu WakeLock timeout
- Bị Realme phát hiện là "app chậm" và đưa vào danh sách đen

---

## 2. CÁC THIẾU SÓT NGHIÊM TRỌNG

### 2.1. KEEPALIVEJOBSERVICE KHÔNG BAO GIỜ ĐƯỢC SCHEDULE ❌

```kotlin
// KeepAliveJobService.kt
class KeepAliveJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        AppLifecycleManager.ensureBackgroundRunning(context, "JOB_SCHEDULER")
        jobFinished(params, false)
        return true
    }
}
```

**Vấn đề**: JobService này được định nghĩa trong manifest nhưng **KHÔNG CÓ CHỖ NÀO** trong code gọi `JobScheduler.schedule()` để đăng ký nó. Đây là code chết!

**Fix**: Cần schedule periodic JobScheduler với khoảng 15 phút trong `AppLifecycleManager.ensureBackgroundRunning()`.

### 2.2. WORKMANAGER BỊ VÔ HIỆU HÓA ❌

```kotlin
// MainActivity.kt - dòng 213-220
workManager.cancelAllWorkByTag("INFORMER_SYNC_WORK")
workManager.pruneWork()

// AppLifecycleManager.kt - dòng 70-73
private fun schedulePeriodicSync(context: Context, source: String): Boolean {
    Log.d(TAG, "[$source] WorkManager đã bị vô hiệu hóa, sử dụng AlarmManager.")
    return false
}
```

**Vấn đề**: WorkManager là cơ chế background work **ưu việt nhất trên Android 16**, vì:
- Android 16 tối ưu WorkManager qua Play Services
- WorkManager có thể wake app ngay cả khi bị doze
- WorkManager tự quản lý battery optimization
- Realme không thể block WorkManager dễ dàng như AlarmManager

**Fix**: Cần schedule PeriodicWorkRequest cho SyncWorker.

### 2.3. SERVICE WATCHDOG BỊ VÔ HIỆU HÓA ❌

```kotlin
// ServiceWatchdog.kt - dòng 17-31: TOÀN BỘ CODE TRONG schedule() BỊ COMMENT
fun schedule(context: Context, reason: String): Boolean {
    // val appContext = context.applicationContext
    // ...
    return false
}
```

**Vấn đề**: Mất lớp watchdog dự phòng quan trọng. ServiceWatchdogReceiver vẫn còn code startService nhưng không ai gọi nó.

### 2.4. THIẾU BROADCASTRECEIVER CHO NETWORK CHANGE ❌

**Vấn đề**: Khi mạng quay trở lại sau khi mất, không có `CONNECTIVITY_CHANGE` receiver để trigger retry các event thất bại. Chỉ có AlarmManager polling định kỳ mới phát hiện.

**Hậu quả**: Nếu mất mạng 30 phút và alarm backoff lên 60 phút, có thể mất đến 60 phút để gửi lại dữ liệu.

### 2.5. KHÔNG CLEANUP DEDUPE KEYS ❌

**Vấn đề**: `AppInternalStateV4` lưu vĩnh viễn các key `ID_xxxxx` và `CALLLOG_ID_xxxxx`. Sau nhiều tháng, file SharedPreferences có thể lên đến hàng MB, gây chậm khi đọc/ghi.

```kotlin
// SmsReceiver.kt - lưu key vĩnh viễn
dedupePref.edit().putBoolean(msgKey, true).commit()
```

### 2.6. SMSBATCHMANAGER DỄ MẤT DỮ LIỆU ❌

```kotlin
// SmsBatchManager.kt - dòng 36-41
for (msg in toSend) {
    val ok = ServerReporter.sendEventSync(...)
    if (!ok) Log.w("⚠️ Đẩy SMS thất bại")
    Thread.sleep(200)
}
```

**Vấn đề**: Nếu gửi SMS thất bại, SMS bị mất vĩnh viễn. Không có retry queue, không lưu lại để gửi sau.

---

## 3. TỐI ƯU CHO ANDROID 16

### 3.1. Bổ sung UserManager check đầy đủ hơn

```kotlin
// Cần check cả 2 trạng thái
val userManager = context.getSystemService(android.os.UserManager::class.java)
val isUnlocked = userManager?.isUserUnlocked ?: Build.VERSION.SDK_INT < Build.VERSION_CODES.N
```

### 3.2. Sử dụng PHONE_STATE với RECEIVER_NOT_EXPORTED

Trên Android 16, broadcast receiver với `RECEIVER_EXPORTED` có thể bị chặn nếu không có quyền phù hợp.

### 3.3. Thêm PowerManager.OnPowerSaveModeChangeListener

Android 16 có chế độ tiết kiệm pin mạnh hơn. Cần listener để phát hiện và điều chỉnh hành vi.

### 3.4. Sử dụng foregroundServiceType="specialUse" thay vì "dataSync"

```xml
<service
    android:name=".BackgroundMonitoringService"
    android:foregroundServiceType="specialUse"
    ...>
</service>
```

---

## 4. KIẾN NGHỊ SỬA CHỮA (THEO THỨ TỰ ƯU TIÊN)

### PRIORITY 1 - App không chạy được = Fix ngay

| # | Vấn đề | Fix |
|---|---|---|
| 1 | `POST_NOTIFICATIONS` bị loại khỏi danh sách quyền | Thêm lại vào `checkAndRequestPermissions()` ở MainActivity |
| 2 | `foregroundServiceType="dataSync"` sai | Đổi thành `"specialUse"` + thêm `FOREGROUND_SERVICE_SPECIAL_USE` permission |
| 3 | `START_STICKY` không hoạt động trên Realme | Thêm `JobScheduler` schedule cho KeepAliveJobService với khoảng 15 phút |

### PRIORITY 2 - Keep-alive không đáng tin cậy

| # | Vấn đề | Fix |
|---|---|---|
| 4 | WorkManager bị vô hiệu | Khôi phục `schedulePeriodicSync()` - schedule PeriodicWorkRequest cho SyncWorker |
| 5 | ServiceWatchdog bị vô hiệu | Khôi phục `ServiceWatchdog.schedule()` với alarm 15 phút |
| 6 | Không có Auto Launch hướng dẫn | Thêm hướng dẫn Realme cụ thể: Cài đặt → Pin → Quản lý ứng dụng → Chọn app → Bật "Tự động khởi chạy" |

### PRIORITY 3 - Mất dữ liệu

| # | Vấn đề | Fix |
|---|---|---|
| 7 | SMS batch mất nếu gửi thất bại | Thêm cơ chế retry queue: lưu SMS chưa gửi vào SharedPreferences, retry sau |
| 8 | Không có CONNECTIVITY_CHANGE receiver | Thêm receiver để retry khi có mạng trở lại |
| 9 | Dedupe keys vĩnh viễn | Thêm cleanup: xóa key cũ hơn 7 ngày hoặc dùng LRU cache |

### PRIORITY 4 - Hiệu năng

| # | Vấn đề | Fix |
|---|---|---|
| 10 | `Thread.sleep(1000)` trong SmsInboxSync.polMissingSms | Bỏ sleep, không cần thiết |
| 11 | `Thread.sleep(200)` giữa các SMS trong batch | Bỏ sleep, gửi liên tục |
| 12 | while(true) loop trong MainActivity lifecycleScope | Thêm `isActive` check hoặc dùng `repeatOnLifecycle` |

---

## 5. KIẾN TRÚC ĐỀ XUẤT CHO ANDROID 16

```
┌─────────────────────────────────────────────────────────────────┐
│                   CHIẾN LƯỢC KEEP-ALIVE 4 LỚP                   │
│                                                                 │
│  LỚP 1: Foreground Service (BackgroundMonitoringService)       │
│          └── START_STICKY (không hiệu quả trên Realme)         │
│                                                                 │
│  LỚP 2: AlarmManager (AlarmReceiver)                           │
│          └── Bị Realme delay khi app bị freeze                 │
│                                                                 │
│  LỚP 3: JobScheduler (KeepAliveJobService) ← CẦN THÊM        │
│          └── Hoạt động tốt trên Realme                         │
│                                                                 │
│  LỚP 4: WorkManager (SyncWorker) ← CẦN KHÔI PHỤC            │
│          └── Hoạt động tốt nhất trên Android 16                │
│          └── Được Play Services tối ưu                        │
│          └── Realme không thể block                           │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│              XỬ LÝ SMS KHI MẤT MẠNG (CẦN THÊM)                │
│                                                                 │
│  1. SmsReceiver nhận SMS                                       │
│  2. BatchManager gom 3s                                        │
│  3. Gửi lên server                                             │
│     ├── Thành công → xóa khỏi queue                            │
│     └── Thất bại → LƯU VÀO SharedPreferences QUEUE            │
│  4. Network Change Receiver → retry queue                      │
│  5. AlarmManager tick → retry queue                            │
│  6. Xóa SMS khỏi queue sau 24h (hết hạn)                      │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. CÁC BẢN VERSION ANDROID & REALME COLOROS

| Android Version | API | Realme ColorOS | Vấn đề |
|---|---|---|---|
| Android 14 | 34 | ColorOS 14 | Foreground service type restriction |
| Android 15 | 35 | ColorOS 15 | Stricter background execution |
| Android 16 | 36 | ColorOS 16 | **Nghiêm trọng**: START_STICKY ignore, Alarm delay, Service freeze |

**Kết luận**: Trên Realme Android 16, app gần như **không thể chạy nền** nếu chỉ dựa vào START_STICKY + AlarmManager. Cần bổ sung **ít nhất 2 lớp nữa** (JobScheduler + WorkManager) và yêu cầu user bật **Auto Launch** trong cài đặt Realme.