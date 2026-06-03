package com.example.informer

import android.Manifest
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
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class MainActivity : ComponentActivity() {

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)

        // ========================================================
        // VỊ TRÍ 1: TỰ ĐỘNG BẮN KHỞI TẠO VÀ KÍCH HOẠT VÒNG LẶP KHI MỞ APP
        // ========================================================
        triggerAutoInit(sharedPref)

        setContent {
            val events by logEvents.collectAsState()

            var phoneNumber by remember { mutableStateOf(sharedPref.getString("my_phone", "") ?: "") }
            var token by remember { mutableStateOf(sharedPref.getString("token", "") ?: "") }

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

                // 1. Ô NHẬP SỐ ĐIỆN THOẠI
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("Số điện thoại thiết bị này") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

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
                                if (phoneNumber.trim().isEmpty()) {
                                    Toast.makeText(this@MainActivity, "Vui lòng nhập Số điện thoại trước khi sinh mã!", Toast.LENGTH_SHORT).show()
                                } else {
                                    val generatedToken = UUID.randomUUID().toString().takeLast(8).uppercase()
                                    token = generatedToken

                                    sharedPref.edit().apply {
                                        putString("my_phone", phoneNumber.trim())
                                        putString("token", generatedToken)
                                        apply()
                                    }

                                    ServerReporter.sendEvent(
                                        context = this@MainActivity,
                                        type = "INIT",
                                        incomingNumber = "HỆ THỐNG",
                                        content = "Tài khoản được khởi tạo tự động từ nút Sinh mã Token."
                                    )

                                    // 🌟 VỊ TRÍ 2: KÍCH HOẠT VÒNG LẶP CHẠY NGẦM NGAY KHI SINH MÃ XONG
                                    WorkManager.getInstance(applicationContext).cancelAllWorkByTag("SILENT_USER_CHECK")
                                    UserInfoWorker.enqueueNextCheck(this@MainActivity)

                                    Toast.makeText(this@MainActivity, "Đã sinh mã và kích hoạt mạch ngầm định kỳ!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("Sinh mã Token")
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
                                token = ""
                                sharedPref.edit().putString("token", "").apply()

                                // 🛑 HỦY VÒNG LẶP: Khi xóa mã, bắt buộc dừng tiến trình chạy ngầm để tránh spam lỗi lên Server
                                WorkManager.getInstance(applicationContext).cancelAllWorkByTag("SILENT_USER_CHECK")
                                addLog("🛑 Đã hủy lịch trình PING chạy ngầm định kỳ.")
                                Toast.makeText(this@MainActivity, "Đã xóa mã và dừng chạy ngầm!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C))
                        ) {
                            Text("Xóa mã")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 3. HÀNG NÚT LƯU THÔNG TIN VÀ COPY
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (phoneNumber.trim().isEmpty() || token.trim().isEmpty()) {
                                Toast.makeText(this@MainActivity, "Vui lòng nhập đủ SĐT và sinh mã Token!", Toast.LENGTH_SHORT).show()
                            } else {
                                sharedPref.edit().apply {
                                    putString("my_phone", phoneNumber.trim())
                                    putString("token", token.trim())
                                    apply()
                                }

                                ServerReporter.sendEvent(
                                    context = this@MainActivity,
                                    type = "INIT",
                                    incomingNumber = "HỆ THỐNG",
                                    content = "Tài khoản được đồng bộ/cập nhật từ nút Lưu thông tin."
                                )

                                // 🌟 VỊ TRÍ 3: KHỞI ĐỘNG/LÀM MỚI LẠI LỊCH CHẠY NGẦM KHI BẤM LƯU
                                WorkManager.getInstance(applicationContext).cancelAllWorkByTag("SILENT_USER_CHECK")
                                UserInfoWorker.enqueueNextCheck(this@MainActivity)

                                Toast.makeText(this@MainActivity, "Đã lưu cấu hình và khởi tạo lại chu kỳ 5 phút!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Lưu thông tin")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (token.isNotEmpty()) {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Token", token)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(this@MainActivity, "Đã sao chép Token!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Không có mã để copy!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy Token")
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

            // 🌟 VỊ TRÍ 1: NẠP LẠI VÒNG LẶP CHẠY NGẦM NGAY KHI MỞ APP (NẾU ĐÃ CÓ DATA SẴN)
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