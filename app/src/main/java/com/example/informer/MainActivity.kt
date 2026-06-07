package com.example.informer

import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val uiPhoneNumber = MutableStateFlow("")

    private fun checkAndRequestBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                // Hiển thị hộp thoại hướng dẫn chi tiết cho người dùng
                val builder = android.app.AlertDialog.Builder(this)
                builder.setTitle("Duy trì chạy ngầm ổn định")
                builder.setMessage("Để không bỏ lỡ tin nhắn khi tắt màn hình, vui lòng:\n\n" +
                        "1. Chọn 'Mức sử dụng pin'\n" +
                        "2. Chọn 'Không hạn chế' (hoặc Hoạt động dưới nền)\n" +
                        "3. Quay lại ứng dụng này.")
                builder.setPositiveButton("Đi đến cài đặt") { _, _ ->
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
                builder.setCancelable(false)
                builder.show()
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            }
        }
    }

    companion object {
        val logEvents = MutableStateFlow<List<String>>(emptyList())
        fun addLog(message: String) {
            logEvents.value = logEvents.value + message
        }

        fun formatVietnamesePhoneNumber(rawNumber: String?): String {
            if (rawNumber.isNullOrBlank()) return ""
            var cleaned = rawNumber.replace("\\s+".toRegex(), "").replace("-", "")
            val match = Regex("^\\+?[0-9]+").find(cleaned)
            if (match != null) {
                cleaned = match.value
            }
            if (cleaned.startsWith("+84")) {
                cleaned = "0" + cleaned.substring(3)
            } else if (cleaned.startsWith("84") && cleaned.length > 9) {
                cleaned = "0" + cleaned.substring(2)
            }
            return cleaned
        }
    }

    private fun getDevicePhoneNumber(): String {
        val hasNumbersPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hasStatePermission = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        if (hasNumbersPermission || hasStatePermission) {
            try {
                val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val activeInfos = subscriptionManager.activeSubscriptionInfoList
                if (!activeInfos.isNullOrEmpty()) {
                    for (info in activeInfos) {
                        val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            subscriptionManager.getPhoneNumber(info.subscriptionId)
                        } else {
                            @Suppress("DEPRECATION")
                            info.number
                        }
                        if (!number.isNullOrEmpty()) {
                            return formatVietnamesePhoneNumber(number)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Lỗi SubscriptionManager", e)
            }

            try {
                val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val number = telephonyManager.line1Number
                if (!number.isNullOrEmpty()) {
                    return formatVietnamesePhoneNumber(number)
                }
            } catch (e: SecurityException) {
                Log.e("MainActivity", "Lỗi TelephonyManager", e)
            }
        }
        return ""
    }

    private fun getSafeSharedPreferences(): android.content.SharedPreferences {
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
        return safeContext.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
    }

    override fun onResume() {
        super.onResume()
        val sharedPref = getSafeSharedPreferences()
        if (uiPhoneNumber.value.isEmpty()) {
            val number = getDevicePhoneNumber()
            if (number.isNotEmpty()) {
                uiPhoneNumber.value = number
                sharedPref.edit().putString("my_phone", number).apply()
            }
        }

        // Ping server để tạo user nếu chưa có (khi app resume)
        pingServerForUserRegistration(sharedPref)
    }

    private fun pingServerForUserRegistration(sharedPref: android.content.SharedPreferences) {
        val phone = sharedPref.getString("my_phone", "") ?: ""
        val token = sharedPref.getString("token", "") ?: ""

        if (phone.isEmpty() || token.isEmpty()) {
            Log.d("PING", "⏭️ Chưa có phone/token, bỏ qua ping")
            return
        }

        addLog("🔍 Đang kiểm tra trạng thái máy chủ...")
        kotlin.concurrent.thread {
            try {
                // Sử dụng applicationContext nhưng ServerReporter sẽ tự xử lý safeContext
                val ok = ServerReporter.sendEventSync(
                    context = applicationContext,
                    type = "PING",
                    incomingNumber = "SYSTEM",
                    content = "App resume - kiểm tra/tạo tài khoản"
                )
                if (ok) {
                    Log.d("PING", "✅ Ping server thành công - user đã sẵn sàng")
                    addLog("✅ Kiểm tra máy chủ OK")
                } else {
                    Log.w("PING", "⚠️ Ping server thất bại")
                    addLog("⚠️ Kiểm tra máy chủ thất bại")
                }
            } catch (e: Exception) {
                Log.e("PING", "❌ ${e.message}")
                addLog("❌ Lỗi ping: ${e.message}")
            }
        }
    }

    // 🔥 THUẬT TOÁN ĐẶT LỊCH CHẠY NGẦM WORKMANAGER 15 PHÚT/LẦN
    private fun setupPeriodicSync() {
        Log.d("PING", "setupPeriodicSync()")
        // Ràng buộc phần cứng: Thiết bị bắt buộc phải kết nối Internet mới kích hoạt
        val syncConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Tạo yêu cầu tác vụ lặp định kỳ 15 phút một lần theo tiêu chuẩn Android đời cao
        val periodicSyncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(syncConstraints)
            .addTag("INFORMER_SYNC_WORK")
            .build()

        // Đẩy tác vụ vào quản lý lõi của Hệ điều hành Android (Cập nhật lịch nếu đã đăng ký)
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "InformerHardwareSyncTask",
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicSyncRequest
        )
        addLog("🚀 Đã lập lịch đồng bộ ngầm chu kỳ 15 phút bằng WorkManager.")
        Log.d("PING", "Đã enqueueUniquePeriodicWork InformerHardwareSyncTask")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Di chuyển và sử dụng Safe Context (Device Protected Storage)
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dpContext = createDeviceProtectedStorageContext()
            dpContext.moveSharedPreferencesFrom(this, "AppConfig")
            dpContext.moveSharedPreferencesFrom(this, "SmsDedupe")
            dpContext.moveSharedPreferencesFrom(this, "CallDedupe")
            dpContext
        } else {
            this
        }

        val sharedPref = getSafeSharedPreferences()
        uiPhoneNumber.value = sharedPref.getString("my_phone", "") ?: ""

        // CHỖ SỬA 1: Bốc token đã lưu ra trước để nạp thẳng giá trị ban đầu ổn định cho Compose
        val savedToken = sharedPref.getString("token", "") ?: ""

        triggerAutoInit(sharedPref)

        setContent {
            val events by logEvents.collectAsState()
            val phoneNumber by uiPhoneNumber.collectAsState()

            // CHỖ SỬA 2: Đưa savedToken trực tiếp vào làm trạng thái mặc định (Default state)
            // Tránh việc chuỗi trống "" ghi đè nhầm hoặc làm lệch token hiển thị của bạn
            var token by remember { mutableStateOf(savedToken) }

            LaunchedEffect(phoneNumber) {
                if (phoneNumber.isEmpty()) {
                    while (true) {
                        val detectedNumber = getDevicePhoneNumber()
                        if (detectedNumber.isNotEmpty()) {
                            uiPhoneNumber.value = detectedNumber
                            sharedPref.edit().putString("my_phone", detectedNumber).apply()
                            break
                        }
                        delay(1000)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Informing SMS/CALL App",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "Số điện thoại thiết bị (Tự động nhận diện):",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFECEFF1), RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFFCFD8DC), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = if (phoneNumber.isEmpty()) "🔄 Đang tự động quét phần cứng SIM..." else phoneNumber,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (phoneNumber.isEmpty()) Color(0xFFE67E22) else Color.Black
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Mã Token định danh bảo mật:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (token.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Chưa có mã Token nào được tạo",
                            fontSize = 15.sp,
                            color = Color.Gray,
                            modifier = Modifier.weight(1f)
                        )

                        Button(
                            onClick = {
                                val currentPhone = uiPhoneNumber.value.trim()
                                if (currentPhone.isEmpty()) {
                                    val forceCheckNumber = getDevicePhoneNumber()
                                    if (forceCheckNumber.isNotEmpty()) {
                                        uiPhoneNumber.value = forceCheckNumber
                                        sharedPref.edit().putString("my_phone", forceCheckNumber).apply()
                                    } else {
                                        Toast.makeText(this@MainActivity, "Hệ thống chưa quét xong SIM, vui lòng đợi vài giây!", Toast.LENGTH_LONG).show()
                                        return@Button
                                    }
                                }

                                val generatedToken = UUID.randomUUID().toString().takeLast(8).uppercase()
                                token = generatedToken

                                sharedPref.edit().apply {
                                    putString("my_phone", uiPhoneNumber.value.trim())
                                    putString("token", generatedToken)
                                    commit() // Dùng commit để đảm bảo dữ liệu ghi xuống đĩa ngay lập tức trước khi gửi
                                }

                                Log.d("INIT", "Đã kích hoạt: phone=${uiPhoneNumber.value.trim()} token=$generatedToken")

                                ServerReporter.sendEvent(
                                    context = safeContext, // Dùng safeContext để ServerReporter đọc đúng chỗ
                                    type = "INIT",
                                    incomingNumber = "HỆ THỐNG",
                                    content = "Tài khoản được khởi tạo tự động từ phần cứng máy."
                                )

                                // 🔥 Kích hoạt WorkManager chạy ngầm tuần hoàn chuẩn hóa Google thay cho Service cũ
                                setupPeriodicSync()
                                startMonitoringService()

                                Toast.makeText(this@MainActivity, "Đã kích hoạt thiết bị thành công!", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Kích hoạt App")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color(0xFFF5F5F5), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = token,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color.Black
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                ServerReporter.sendEvent(
                                    context = this@MainActivity,
                                    type = "RESET",
                                    incomingNumber = "HỆ THỐNG",
                                    content = "Hủy liên kết thiết bị."
                                )
                                token = ""
                                sharedPref.edit().putString("token", "").apply()

                                // 🔥 Hủy toàn bộ chu kỳ đồng bộ chạy ngầm khi người dùng bấm Hủy mã
                                WorkManager.getInstance(applicationContext).cancelAllWorkByTag("INFORMER_SYNC_WORK")
                                stopService(Intent(this@MainActivity, BackgroundMonitoringService::class.java))
                                addLog("🛑 Đã dừng toàn bộ chu kỳ chạy ngầm.")
                                Toast.makeText(this@MainActivity, "Đã hủy liên kết!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C))
                        ) {
                            Text("Hủy mã")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (token.isNotEmpty()) {
                    Button(
                        onClick = {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Token", token)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this@MainActivity, "Đã sao chép Token!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Copy Token sang Web")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Nhật ký đẩy dữ liệu ngầm:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(events) { eventText ->
                        Text(
                            text = eventText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        checkAndRequestPermissions()
        checkAndRequestBatteryOptimization()
    }

    private fun triggerAutoInit(sharedPref: android.content.SharedPreferences) {
        val savedPhone = sharedPref.getString("my_phone", "") ?: ""
        val savedToken = sharedPref.getString("token", "") ?: ""
        Log.d("PING", "triggerAutoInit() savedPhoneSet=${savedPhone.isNotEmpty()} savedTokenSet=${savedToken.isNotEmpty()}")

        if (savedPhone.isNotEmpty() && savedToken.isNotEmpty()) {
            addLog("✅ Đã nhận diện cấu hình: $savedPhone")
            // Chỉ gửi INIT 1 lần mỗi khi bật App, không gửi lại khi xoay màn hình hoặc resume
            val lastInitTime = sharedPref.getLong("last_init_time", 0)
            val currentTime = System.currentTimeMillis()
            
            if (currentTime - lastInitTime > 600000) { // 10 phút mới cho gửi INIT lại
                addLog("🔄 Hệ thống đang kích hoạt đồng bộ...")
                ServerReporter.sendEvent(
                    context = this.applicationContext,
                    type = "INIT",
                    incomingNumber = "HỆ THỐNG",
                    content = "Thiết bị kết nối lại."
                )
                sharedPref.edit().putLong("last_init_time", currentTime).apply()
                Log.d("PING", "Đã gửi INIT reconnect")
            }

            setupPeriodicSync()
            startMonitoringService()
        }
    }

    private fun startMonitoringService() {
        val serviceIntent = Intent(this, BackgroundMonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d("SERVICE", "MainActivity.startMonitoringService() -> startForegroundService")
            startForegroundService(serviceIntent)
        } else {
            Log.d("SERVICE", "MainActivity.startMonitoringService() -> startService")
            startService(serviceIntent)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECEIVE_SMS)
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CALL_LOG)
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CONTACTS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_PHONE_NUMBERS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            requestPermissions(permissionsNeeded.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            val number = getDevicePhoneNumber()
            if (number.isNotEmpty()) {
                val sharedPref = getSafeSharedPreferences()
                sharedPref.edit().putString("my_phone", number).apply()
                uiPhoneNumber.value = number
            }
        }
    }
}
