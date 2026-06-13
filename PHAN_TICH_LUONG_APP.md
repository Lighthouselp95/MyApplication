# PHÂN TÍCH LUỒNG HOẠT ĐỘNG ỨNG DỤNG INFORMER

## TỔNG QUAN

**Informer** là ứng dụng Android giám sát và đồng bộ dữ liệu **SMS**, **cuộc gọi nhỡ** lên server từ xa (`https://portal-mirroring.onrender.com/api/push`). Ứng dụng chạy nền liên tục (Foreground Service) với cơ chế keep-alive qua **AlarmManager** để đảm bảo không bị kill bởi hệ thống.

---

## 1. KIẾN TRÚC THÀNH PHẦN

| Thành phần | Loại | Vai trò |
|---|---|---|
| `InformerApp` | Application | Khởi tạo AppContextHolder |
| `MainActivity` | Activity (Compose) | UI, xin quyền, kích hoạt, vòng lặp watchdog 5 phút |
| `BackgroundMonitoringService` | Foreground Service | Service nền chính: observers, polling, alarm scheduling |
| `BootReceiver` | BroadcastReceiver | Khởi động lại service sau boot |
| `SmsReceiver` | BroadcastReceiver | Bắt SMS real-time |
| `PhoneReceiver` | BroadcastReceiver | Bắt cuộc gọi real-time (manifest) |
| `AlarmReceiver` | BroadcastReceiver | Đánh thức định kỳ để kiểm tra và hồi sinh service |
| `ServiceWatchdogReceiver` | BroadcastReceiver | (Đã bị vô hiệu) |
| `HealthCheckReceiver` | BroadcastReceiver | Endpoint kiểm tra sức khỏe từ ngoài |
| `ScreenStateReceiver` | BroadcastReceiver | Phát hiện màn hình bật/tắt |
| `CallEventHandler` | Object | Xử lý logic cuộc gọi đến |
| `SmsBatchManager` | Object | Gom batch SMS trong 3s rồi gửi |
| `SmsInboxSync` | Object | Quét SMS từ inbox (backfill) |
| `CallLogSync` | Object | Quét call log (backfill cuộc gọi nhỡ) |
| `ServerReporter` | Object | Gửi HTTP POST lên server |
| `AppLifecycleManager` | Object | Quản lý vòng đời: khởi động service, migrate storage |
| `AppHealthMonitor` | Object | Ghi nhận heartbeat của các component |
| `AppLogStore` | Object | Lưu log history trong SharedPreferences |
| `HistoryScanBaseline` | Object | Thiết lập baseline khi mới cài đặt |
| `ServiceWatchdog` | Object | (Đã bị vô hiệu hóa - code bị comment) |
| `SyncWorker` | CoroutineWorker | (Đã bị vô hiệu - WorkManager không được schedule) |
| `KeepAliveJobService` | JobService | JobService để keep-alive |
| `AppContextHolder` | Object | Singleton giữ context application |
| `DeviceUtils` | Object | Utilities: format số điện thoại, lấy tên contact |
| `AppStorage` | - | (File chưa đọc, có thể là utility) |

---

## 2. SƠ ĐỒ LUỒNG CHÍNH

### 2.1. LUỒNG KHỞI ĐỘNG ỨNG DỤNG

```
Device Boot
     │
     ▼
BootReceiver.onReceive()
     │
     ├── ACTION_BOOT_COMPLETED / LOCKED_BOOT_COMPLETED
     │       └── AppLifecycleManager.startMonitoringService()
     │               └── startForegroundService(BackgroundMonitoringService)
     │
     ├── ACTION_USER_UNLOCKED
     │       └── AppLifecycleManager.restoreIfActivated()
     │               ├── syncProtectedStorage()
     │               ├── isActivated()? → ensureBackgroundRunning()
     │               │       ├── HistoryScanBaseline.ensureInitialized()
     │               │       ├── startMonitoringService()
     │               │       └── schedulePeriodicSync() [LUÔN trả về false]
     │               └── (nếu chưa activate → skip)
     │
     └── ACTION_PACKAGE_REPLACED / BATTERY_LOW / POWER_CONNECTED / POWER_DISCONNECTED
             └── (chỉ log, không xử lý đặc biệt)

User mở app
     │
     ▼
MainActivity.onCreate()
     │
     ├── Di chuyển SharedPreferences → Device Protected Storage
     ├── Load số điện thoại, token từ SharedPreferences
     ├── Xóa sạch WorkManager cũ (cancelAllWorkByTag + pruneWork)
     ├── AppLifecycleManager.restoreIfActivated(this, "onCreate")
     │       └── (xem luồng bên trên)
     ├── Khởi tạo coroutine loop (5 phút):
     │       ├── Kiểm tra SyncWorker còn sống không
     │       │       └── Nếu mất → ensureBackgroundRunning()
     │       └── Gửi HEARTBEAT_SILENT ping lên server
     ├── checkAndRequestPermissions() - xin 5-6 quyền
     └── checkAndRequestBatteryOptimization()
```

### 2.2. LUỒNG SMS ĐẾN (REAL-TIME)

```
SMS đến thiết bị
     │
     ▼
SmsReceiver.onReceive()  [priority=2147483647]
     │
     ├── Parse SMS (sender, body, timestamp)
     ├── Kiểm tra AppLifecycleManager.isActivated()?
     │       └── Nếu chưa kích hoạt → bỏ qua
     ├── goAsync()
     ├── Delay 300ms (chờ SMS được ghi vào DB)
     ├── getSmsIdFromDb() - truy vấn content://sms/inbox để lấy _id
     ├── De-duplicate: tạo key = "ID_{smsId}" hoặc "KEY_{number}_{body}_{timestamp}"
     │       └── Kiểm tra trong SharedPreferences "AppInternalStateV4"
     │       └── Nếu đã tồn tại → bỏ qua (trùng lặp)
     ├── Ghi key vào SharedPreferences (commit ngay để tránh crash)
     │
     └── SmsBatchManager.enqueue(sender, body, timestamp)
             │
             └── [SmsBatchManager]
                     ├── Gom tất cả SMS vào list
                     ├── Reset timer 3 giây mỗi khi có SMS mới
                     ├── Hết 3 giây → gửi batch
                     │       ├── Acquire WakeLock "Informer:BatchWakeLock" (60s)
                     │       ├── Thread: gửi từng SMS lên server
                     │       │       └── ServerReporter.sendEventSync(type="SMS")
                     │       │               ├── POST JSON lên /api/push
                     │       │               ├── Retry 2 lần, timeout 15s/lần, sleep 3s giữa các lần
                     │       │               └── Log kết quả vào MainActivity.addLog()
                     │       └── Release WakeLock
                     └── Kết thúc
```

### 2.3. LUỒNG CUỘC GỌI ĐẾN (REAL-TIME)

```
Cuộc gọi đến
     │
     ▼
PhoneReceiver.onReceive()  [manifest, priority=2147483647]
     │
     └── CallEventHandler.handleIncomingCall(context, intent, "MANIFEST")

Ngoài ra còn:
BackgroundMonitoringService đăng ký phoneStateReceiver (runtime)
     │
     └── CallEventHandler.handleIncomingCall(context, intent, "SERVICE")

     ▼
CallEventHandler.handleIncomingCall()
     │
     ├── Kiểm tra action = PHONE_STATE_CHANGED?
     ├── Kiểm tra state = RINGING?
     ├── Format số điện thoại
     ├── Bỏ qua nếu số === số của chính mình
     ├── De-duplicate: kiểm tra CallDedupe (60 giây)
     │       └── Nếu đã gửi trong 60s → bỏ qua
     ├── Acquire WakeLock "Informer:CallWakeLock" (30s)
     ├── Thread mới:
     │       ├── Sleep 3 giây (chờ user có thể nghe máy)
     │       ├── ServerReporter.sendEventSync(type="CALL")
     │       └── Release WakeLock
     └── finish() pendingResult
```

### 2.4. LUỒNG FOREGROUND SERVICE

```
BackgroundMonitoringService.onCreate()
     │
     ├── startForegroundNow() - tạo notification channel & hiển thị
     ├── Ghi "is_service_running=true" vào ServiceState
     ├── registerScreenStateReceiver()
     ├── HistoryScanBaseline.ensureInitialized()
     ├── registerPhoneStateReceiver() - đăng ký runtime PHONE_STATE
     │
     └── checkAndSetupSystemObservers("SERVICE_START")
             │
             ├── Kiểm tra isUserUnlocked?
             │       └── Nếu chưa → retry sau 5 giây
             │
             ├── Nếu đã unlock:
             │       ├── Nếu có READ_SMS permission → registerSmsObserver()
             │       │       └── ContentObserver trên content://sms/inbox
             │       │               └── onChange() → SmsInboxSync.pollMissingSms()
             │       │
             │       └── startPolling()
             │               ├── triggerBackfill("INITIAL_POLLING")
             │               │       ├── SmsInboxSync.pollMissingSms()
             │               │       └── CallLogSync.pollMissingCalls()
             │               │
             │               └── Đặt alarm đầu tiên sau 1 phút
             │                       └── AlarmManager.setExactAndAllowWhileIdle()

BackgroundMonitoringService.onStartCommand()
     │
     ├── startForegroundNow()
     └── Nếu intent == null (system restart) → checkAndSetupSystemObservers("RESTART_STICKY")
```

### 2.5. LUỒNG ALARM MANAGER (KEEP-ALIVE)

```
AlarmManager báo thức
     │
     ▼
AlarmReceiver.onReceive()
     │
     ├── AppLifecycleManager.ensureBackgroundRunning(context, "ALARM_TICK")
     │       ├── syncProtectedStorage()
     │       ├── HistoryScanBaseline.ensureInitialized()
     │       ├── startMonitoringService() (nếu service chết)
     │       └── (WorkManager schedule bị vô hiệu)
     │
     └── scheduleNext()
             │
             └── Đọc is_screen_on từ ServiceState
                     │
                     ├── Nếu màn hình SÁNG → alarm tiếp theo sau 1 PHÚT, backoff_level=0
                     │
                     └── Nếu màn hình TẮT → leo thang backoff:
                             ├── Level 0: 3 phút
                             ├── Level 1: 3 phút
                             ├── Level 2: 5 phút
                             ├── Level 3: 5 phút
                             ├── Level 4: 10 phút
                             ├── Level 5: 15 phút
                             ├── Level 6: 30 phút
                             └── Level 7+: 60 phút (giữ nguyên)
```

### 2.6. LUỒNG BACKFILL (QUÉT BÙ)

```
SmsInboxSync.pollMissingSms(source)
     │
     ├── Kiểm tra isUserUnlocked?
     ├── HistoryScanBaseline.ensureSmsBaseline() (nếu chưa)
     ├── Kiểm tra READ_SMS permission
     │
     ├── Load last_processed_sms_id từ SharedPreferences
     ├── Query content://sms/inbox
     │       WHERE _id > lastProcessedSmsId OR date >= (30 phút trước)
     │       ORDER BY _id ASC
     │
     └── Với mỗi SMS mới:
             ├── De-duplicate bằng "ID_{smsId}"
             ├── Update last_processed_sms_id
             └── SmsBatchManager.enqueue(sender, body, date)

CallLogSync.pollMissingCalls(source)
     │
     ├── Kiểm tra isUserUnlocked?
     ├── HistoryScanBaseline.ensureCallBaseline() (nếu chưa)
     ├── Kiểm tra READ_CALL_LOG permission
     │
     ├── Load last_processed_call_log_id
     ├── Query CallLog.Calls.CONTENT_URI
     │       WHERE _id > lastProcessedCallLogId
     │       ORDER BY _id ASC
     │
     └── Với mỗi call log mới:
             ├── De-duplicate bằng "CALLLOG_ID_{callId}"
             ├── Update last_processed_call_log_id
             └── CHỈ gửi nếu type == MISSED_TYPE
                     └── ServerReporter.sendEventSync(type="CALL")
```

### 2.7. LUỒNG SCREEN STATE

```
Màn hình BẬT
     │
     ▼
ScreenStateReceiver.onReceive(ACTION_SCREEN_ON)
     │
     ├── Ghi "is_screen_on=true" vào ServiceState
     ├── Reset "backoff_level=0"
     └── AppLifecycleManager.ensureBackgroundRunning(context, "SCREEN_ON_WAKE")

Màn hình TẮT
     │
     ▼
ScreenStateReceiver.onReceive(ACTION_SCREEN_OFF)
     │
     └── Ghi "is_screen_on=false", reset "backoff_level=0"
```

### 2.8. LUỒNG HEARTBEAT CHÍNH TỪ MAINACTIVITY

```
MainActivity coroutine loop (5 phút/lần)
     │
     ├── [1] Kiểm tra SyncWorker:
     │       └── AppLifecycleManager.isSyncWorkActive()?
     │               └── WorkManager.getWorkInfosForUniqueWork("InformerHardwareSyncTask_V2")
     │               └── Nếu worker không ENQUEUED hoặc RUNNING:
     │                       └── ensureBackgroundRunning() (hồi sinh)
     │
     └── [2] Gửi HEARTBEAT_SILENT:
             └── ServerReporter.sendEventSync(type="HEARTBEAT_SILENT", silent=true)
```

### 2.9. LUỒNG SỬ DỤNG DEVICE PROTECTED STORAGE

```
Tất cả các component dùng deviceProtectedContext() để:
     │
     ├── getSharedPreferences("AppConfig", MODE_PRIVATE)
     │       └── Lưu: my_phone, token
     │
     ├── getSharedPreferences("SmsDedupe", MODE_PRIVATE)
     │       └── Lưu: dedupe keys cho SMS
     │
     ├── getSharedPreferences("CallDedupe", MODE_PRIVATE)
     │       └── Lưu: dedupe keys cho cuộc gọi
     │
     ├── getSharedPreferences("AppInternalStateV4", MODE_PRIVATE)
     │       └── Lưu: last_processed_sms_id, last_processed_call_log_id,
     │                sms_scan_baseline_ready, call_scan_baseline_ready, các dedupe keys
     │
     ├── getSharedPreferences("AppLogHistory", MODE_PRIVATE)
     │       └── Lưu: log entries (JSON array, tối đa 300 entries, 7 ngày)
     │
     ├── getSharedPreferences("AppHealth", MODE_PRIVATE)
     │       └── Lưu: heartbeat timestamps
     │
     └── getSharedPreferences("ServiceState", MODE_PRIVATE)
             └── Lưu: is_service_running, is_screen_on, backoff_level
```

---

## 3. SƠ ĐỒ LUỒNG DỮ LIỆU TỔNG THỂ

```
┌─────────────────────────────────────────────────────────────────────┐
│                         THIẾT BỊ ANDROID                            │
│                                                                     │
│  ┌──────────┐   ┌──────────────┐   ┌─────────────────────────┐     │
│  │ SMS đến  │──▶│ SmsReceiver  │──▶│ SmsBatchManager (3s)   │     │
│  └──────────┘   └──────────────┘   └──────────┬──────────────┘     │
│                                                │                    │
│  ┌──────────┐   ┌──────────────┐              │                    │
│  │ Gọi đến  │──▶│ PhoneReceiver│              │                    │
│  └──────────┘   └──────┬───────┘              │                    │
│                        │                      │                    │
│                ┌───────▼────────┐             │                    │
│                │CallEventHandler│             │                    │
│                └───────┬────────┘             │                    │
│                        │                      │                    │
│  ┌─────────────────────▼──────────────────────▼──────────────┐     │
│  │                    ServerReporter                         │     │
│  │    POST https://portal-mirroring.onrender.com/api/push    │     │
│  │    Retry: 2 lần | Timeout: 15s | Sleep: 3s              │     │
│  └─────────────────────────┬────────────────────────────────┘     │
│                            │                                      │
│  ┌─────────────────────────▼────────────────────────────────┐     │
│  │                    SERVER REMOTE                          │     │
│  │              (portal-mirroring.onrender.com)              │     │
│  └──────────────────────────────────────────────────────────┘     │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              KEEP-ALIVE SYSTEM                               │   │
│  │                                                              │   │
│  │  ┌───────────────────────┐    ┌──────────────────────────┐   │   │
│  │  │ BackgroundMonitoring  │───▶│ AlarmManager +           │   │   │
│  │  │ Service (Foreground)  │    │ AlarmReceiver            │   │   │
│  │  └───────────────────────┘    │ (backoff: 1p→3p→...→60p)│   │   │
│  │                                 └──────────────────────────┘   │   │
│  │  ┌───────────────────────┐    ┌──────────────────────────┐   │   │
│  │  │ MainActivity          │───▶│ Coroutine loop 5 phút    │   │   │
│  │  │ (Watchdog)            │    │ + HEARTBEAT_SILENT ping  │   │   │
│  │  └───────────────────────┘    └──────────────────────────┘   │   │
│  │                                                              │   │
│  │  ┌───────────────────────┐                                   │   │
│  │  │ BootReceiver          │───▶ Khởi động service khi boot   │   │
│  │  └───────────────────────┘                                   │   │
│  │                                                              │   │
│  │  ┌───────────────────────┐                                   │   │
│  │  │ ScreenStateReceiver   │───▶ Điều chỉnh tần số ping       │   │
│  │  └───────────────────────┘                                   │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 4. CHI TIẾT CÁC THÀNH PHẦN

### 4.1. ServerReporter

- **Endpoint**: `POST https://portal-mirroring.onrender.com/api/push`
- **Headers**: `Content-Type: application/json; charset=utf-8`
- **Body JSON**:
  ```json
  {
    "myPhoneNumber": "0xxxxxxxxx",
    "token": "xxxx",
    "type": "SMS | CALL | INIT | RESET | HEARTBEAT_SILENT | HEARTBEAT_STRONG",
    "incomingNumber": "tên/số điện thoại",
    "content": "nội dung",
    "time": "HH:mm:ss d/M/yyyy"
  }
  ```
- **Retry**: Tối đa 2 lần, timeout 15s mỗi lần, sleep 3s giữa các lần
- **WakeLock**: KHÔNG tự quản lý WakeLock - caller phải tự acquite

### 4.2. Các loại Event

| Type | Nguồn gửi | Điều kiện | silent |
|---|---|---|---|
| `SMS` | SmsBatchManager (từ SmsReceiver hoặc SmsInboxSync) | App đã kích hoạt | false |
| `CALL` | CallEventHandler (từ PhoneReceiver) | RINGING, không trùng, không phải số mình | false |
| `CALL` | CallLogSync | MISSED_TYPE, call log mới | false |
| `INIT` | MainActivity | User bấm "Kích hoạt" | false |
| `RESET` | MainActivity | User bấm "Hủy mã" | false |
| `HEARTBEAT_SILENT` | MainActivity loop | 5 phút/lần | true |
| `HEARTBEAT_STRONG` | SyncWorker | (Bị vô hiệu) | true |

### 4.3. De-duplication

**SMS**:
- Key: `"ID_{smsId}"` (nếu lấy được ID từ DB) hoặc `"KEY_{number}_{body_no_space}_{timestampSec}"`
- Storage: `AppInternalStateV4` (Device Protected)
- Thời gian lưu: vĩnh viễn (không bao giờ xóa trừ khi clear app data)

**Cuộc gọi**:
- Key: số điện thoại
- Storage: `CallDedupe`
- Window: 60 giây
- Lưu timestamp, nếu trong vòng 60s thì bỏ qua

**Call Log backfill**:
- Key: `"CALLLOG_ID_{callId}"`
- Storage: `AppInternalStateV4`

### 4.4. HistoryScanBaseline

Khi app được cài đặt lần đầu:
- **SMS**: Tìm SMS cuối cùng có `date <= installTime`, lấy `_id` làm baseline
- **Call Log**: Tìm call log cuối cùng có `date <= installTime`, lấy `_id` làm baseline
- Chỉ chạy 1 lần, đánh dấu bằng flag `sms_scan_baseline_ready` / `call_scan_baseline_ready`

### 4.5. Quyền yêu cầu

| Permission | Mục đích |
|---|---|
| `RECEIVE_SMS` | Bắt SMS real-time qua BroadcastReceiver |
| `READ_SMS` | Đọc SMS từ inbox (backfill, ContentObserver) |
| `READ_PHONE_STATE` | Bắt trạng thái cuộc gọi, đọc số điện thoại SIM |
| `READ_CALL_LOG` | Đọc lịch sử cuộc gọi (backfill) |
| `READ_CONTACTS` | Hiển thị tên contact |
| `READ_PHONE_NUMBERS` (API 30+) | Đọc số điện thoại từ SIM |
| `POST_NOTIFICATIONS` (API 33+) | (Không tự động xin - để người dùng tự bật) |
| `FOREGROUND_SERVICE` | Chạy foreground service |
| `FOREGROUND_SERVICE_DATA_SYNC` | Foreground service type |
| `RECEIVE_BOOT_COMPLETED` | Khởi động lại sau boot |
| `WAKE_LOCK` | Giữ WakeLock khi gửi dữ liệu |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Yêu cầu không tối ưu pin |
| `SCHEDULE_EXACT_ALARM` | Đặt alarm chính xác |

---

## 5. CÁC VẤN ĐỀ KIẾN TRÚC & RỦI RO

### 5.1. WorkManager bị vô hiệu hóa hoàn toàn

- `AppLifecycleManager.schedulePeriodicSync()` luôn trả về `false`
- SyncWorker chỉ còn được gọi từ MainActivity loop (5 phút) để kiểm tra service
- **Nguyên nhân**: Có thể do lỗi WorkManager trước đây, tác giả đã tắt và chuyển sang AlarmManager
- **Rủi ro**: Mất lớp redundancy. Nếu MainActivity bị destroy, không còn cơ chế kiểm tra SyncWorker.

### 5.2. ServiceWatchdog bị vô hiệu hóa

- Toàn bộ code trong `ServiceWatchdog.schedule()` bị comment
- `schedule()` trả về `false`
- `cancel()` trống rỗng
- **Tác động**: Mất lớp watchdog dự phòng thứ hai.

### 5.3. Race condition giữa BootReceiver và unlock

- `BootReceiver` khi nhận `BOOT_COMPLETED/LOCKED_BOOT_COMPLETED`:
  - Chỉ gọi `startMonitoringService()` - không gọi `ensureBackgroundRunning()`
  - Không setup observers, không polling, không alarm
- Chỉ khi `ACTION_USER_UNLOCKED` mới chạy đầy đủ
- Nếu user khởi động máy và KHÔNG mở khóa (hoặc để lâu mới mở khóa):
  - Service có chạy nhưng chỉ có notification, không có chức năng giám sát

### 5.4. Backoff level một chiều

- Khi màn hình tắt, level tăng dần 0→1→2→...→7
- Khi màn hình bật, level reset về 0
- **Vấn đề**: Nếu màn hình tắt trong thời gian rất ngắn rồi bật lại rồi tắt tiếp, level sẽ reset về 0 mỗi lần bật → không tận dụng được backoff.
- Tuy nhiên đây có thể là behavior mong muốn.

### 5.5. ContentObserver có thể miss

- ContentObserver trên `content://sms/inbox` có thể miss nếu:
  - Service bị kill và restart (SmsObserver mất)
  - Ứng dụng khác xóa SMS quá nhanh
- **Giải pháp hiện tại**: AlarmManager polling định kỳ sẽ quét bù

### 5.6. SmsBatchManager thread safety

- `SmsBatchManager` dùng `synchronized` trên `messageList`
- WakeLock acquite 60s cho mỗi batch
- Nếu gửi thất bại, SMS sẽ bị mất (không có cơ chế retry cho batch)
- **Rủi ro**: Nếu server lỗi hoặc network chậm, batch có thể không được gửi lại

### 5.7. Xử lý Direct Boot

- Hầu hết code dùng `deviceProtectedContext()` để truy cập SharedPreferences
- `BackgroundMonitoringService` deferred setup cho đến khi unlock
- `BootReceiver` xử lý `LOCKED_BOOT_COMPLETED`
- Tuy nhiên `SmsReceiver` và `PhoneReceiver` có thể nhận broadcast ngay cả khi locked

### 5.8. Thiếu cơ chế queue khi network không available

- ServerReporter gửi trực tiếp HTTP request, không queue
- Nếu không có network, request thất bại ngay sau timeout
- Dữ liệu sẽ bị mất nếu:
  - SMS đến (real-time) khi không có mạng
  - SMS không được backfill kịp (polling phát hiện sau)
- **Không có cơ chế lưu tạm và gửi lại sau**

---

## 6. TÓM TẮT LUỒNG TRONG CÁC KỊCH BẢN

### 6.1. Kịch bản: User mới cài đặt app

1. App mở → MainActivity.onCreate()
2. Xin quyền (RECEIVE_SMS, READ_SMS, READ_PHONE_STATE, READ_CALL_LOG, READ_CONTACTS)
3. User nhập token, bấm "Kích hoạt"
4. Lưu `my_phone` + `token` vào SharedPreferences
5. Gửi INIT event lên server
6. `ensureBackgroundRunning()` → BackgroundMonitoringService start
7. HistoryScanBaseline thiết lập baseline (SMS & CallLog)
8. Observers + polling + alarm bắt đầu hoạt động

### 6.2. Kịch bản: Nhận SMS khi app đang chạy

1. SmsReceiver nhận broadcast (delay 300ms)
2. BatchManager gom trong 3 giây
3. Gửi lên server (retry 2 lần nếu thất bại)
4. ContentObserver cũng phát hiện và gọi pollMissingSms (dự phòng)

### 6.3. Kịch bản: Mất mạng trong thời gian dài

1. ServerReporter thất bại sau 2 lần retry (~33s)
2. SMS real-time: mất SMS đó (không queue)
3. SMS backfill: `last_processed_sms_id` vẫn được cập nhật trong DB local
4. Khi có mạng trở lại: polling qua AlarmManager + ContentObserver sẽ quét bù SMS chưa gửi
5. CallLog: tương tự, quét bù dựa vào `_id`

### 6.4. Kịch bản: Thiết bị reboot

1. BootReceiver nhận `BOOT_COMPLETED` → startMonitoringService()
2. Service chạy nhưng chưa có observers (vì chưa unlock)
3. Khi user mở khóa → `USER_UNLOCKED` → restoreIfActivated()
4. Service kiểm tra `isUserUnlocked` → setup đầy đủ

### 6.5. Kịch bản: App bị system kill

1. Service bị kill → onDestroy() ghi `is_service_running=false`
2. AlarmManager vẫn còn alarm đã đặt trước đó
3. AlarmReceiver nhận alarm → `ensureBackgroundRunning()` → startMonitoringService()
4. SyncWorker (nếu được WorkManager gọi) → phát hiện service chết → hồi sinh
5. MainActivity loop (nếu app đang mở) → phát hiện worker mất → hồi sinh

---

## 7. THỐNG KÊ

| Thành phần | Số lượng |
|---|---|
| Activity | 1 (MainActivity) |
| Service | 2 (BackgroundMonitoringService, KeepAliveJobService) |
| BroadcastReceiver | 6 (Boot, Phone, Sms, ServiceWatchdog, Alarm, HealthCheck, ScreenState) |
| Object/Manager | 12+ |
| Quyền yêu cầu | 13 |
| Loại event gửi server | 6 |
| Cơ chế keep-alive | 3 (AlarmManager, Foreground Service, MainActivity loop) |
| Cơ chế de-duplicate | 3 (SMS Dedupe, Call Dedupe, CallLog Dedupe) |

---

## 8. SƠ ĐỒ THỜI GIAN KEEP-ALIVE

```
Màn hình SÁNG:
├── 0 phút: Screen ON → ensureBackgroundRunning()
├── 1 phút: Alarm tick
├── 2 phút: Alarm tick
├── 3 phút: Alarm tick
├── 4 phút: Alarm tick
└── (tiếp tục mỗi 1 phút)

Màn hình TẮT:
├── 0 phút: Screen OFF
├── 3 phút: Alarm (level 0)
├── 6 phút: Alarm (level 1)
├── 11 phút: Alarm (level 2)
├── 16 phút: Alarm (level 3)
├── 26 phút: Alarm (level 4)
├── 41 phút: Alarm (level 5)
├── 71 phút: Alarm (level 6)
└── sau đó mỗi 60 phút (level 7+)

MainActivity (nếu app đang mở):
├── 5 phút: HEARTBEAT_SILENT + kiểm tra SyncWorker
├── 10 phút: HEARTBEAT_SILENT + kiểm tra SyncWorker
└── (tiếp tục mỗi 5 phút)