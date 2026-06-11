package com.example.informer

import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val StarMaskTransformation = VisualTransformation { text ->
    TransformedText(
        AnnotatedString("*".repeat(text.text.length)),
        OffsetMapping.Identity
    )
}

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
        private val logLock = Any()
        fun addLog(message: String) {
            val now = Date()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(now)
            val entry = "[$time] $message"
            synchronized(logLock) {
                val next = logEvents.value + entry
                logEvents.value = next
            }
            AppContextHolder.context?.let { AppLogStore.append(it, now.time, entry) }
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
        return deviceProtectedContext().getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
    }

    override fun onResume() {
        super.onResume()
        Log.d("MAIN_ACTIVITY", "onResume()")
        val sharedPref = getSafeSharedPreferences()
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
        Log.d("MAIN_ACTIVITY", "onCreate(savedInstanceState=${savedInstanceState != null})")
        AppContextHolder.init(this)

        // Di chuyển và sử dụng Safe Context (Device Protected Storage)
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dpContext = deviceProtectedContext()
            dpContext.moveSharedPreferencesFrom(this, "AppConfig")
            dpContext.moveSharedPreferencesFrom(this, "SmsDedupe")
            dpContext.moveSharedPreferencesFrom(this, "CallDedupe")
            dpContext.moveSharedPreferencesFrom(this, "AppInternalStateV4")
            dpContext
        } else {
            this
        }

        val sharedPref = getSafeSharedPreferences()
        uiPhoneNumber.value = sharedPref.getString("my_phone", "") ?: ""
        AppLogStore.pruneExpired(this)
        logEvents.value = AppLogStore.load(this)

        // Nạp mật khẩu đã lưu để xác định trạng thái cấu hình ban đầu
        val savedToken = sharedPref.getString("token", "") ?: ""

        AppLifecycleManager.restoreIfActivated(this, "onCreate")
        
        // Ping silently
        ServerReporter.sendEvent(
            context = this,
            type = "HEARTBEAT_SILENT",
            incomingNumber = "SYSTEM",
            content = "App Started",
            timestamp = null,
            silent = true
        )

        setContent {
            val events by logEvents.collectAsState()
            val phoneNumber by uiPhoneNumber.collectAsState()

            var token by remember { mutableStateOf(savedToken) }
            var tokenInput by remember { mutableStateOf("") }
            var editingToken by remember { mutableStateOf(savedToken.isEmpty()) }
            var tokenVisible by rememberSaveable { mutableStateOf(false) }
            val serviceSnapshot by produceState(initialValue = ServiceWatchdog.snapshot(this@MainActivity)) {
                while (true) {
                    value = ServiceWatchdog.snapshot(this@MainActivity)
                    delay(1000)
                }
            }

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

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Informing SMS/CALL App",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Theo dõi SMS, cuộc gọi và đồng bộ nền",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when {
                                !serviceSnapshot.activated -> Color(0xFFF3F4F6)
                                serviceSnapshot.alive -> Color(0xFFEAF7EE)
                                else -> Color(0xFFFFF3E8)
                            }
                        ),
                        border = BorderStroke(
                            1.dp,
                            when {
                                !serviceSnapshot.activated -> Color(0xFFD1D5DB)
                                serviceSnapshot.alive -> Color(0xFF86EFAC)
                                else -> Color(0xFFF5C28B)
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Trạng thái service",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = when {
                                        !serviceSnapshot.activated -> "CHƯA KÍCH HOẠT"
                                        serviceSnapshot.alive -> "ĐANG CHẠY"
                                        else -> "ĐÃ CHẾT"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        !serviceSnapshot.activated -> androidx.compose.ui.graphics.Color(0xFF455A64)
                                        serviceSnapshot.alive -> androidx.compose.ui.graphics.Color(0xFF1B5E20)
                                        else -> androidx.compose.ui.graphics.Color(0xFFC62828)
                                    }
                                )
                            }

                            Text(
                                text = when {
                                    !serviceSnapshot.activated -> "Chưa có token hoặc số điện thoại nên watchdog chưa chạy."
                                    serviceSnapshot.lastSeenAt <= 0L -> "Chưa có heartbeat từ service."
                                    serviceSnapshot.alive -> "Heartbeat gần nhất: ${ServiceWatchdog.humanReadableAge(this@MainActivity)}."
                                    else -> "Heartbeat gần nhất: ${ServiceWatchdog.humanReadableAge(this@MainActivity)}. Service đang bị xem là stale."
                                },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Thiết bị",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (phoneNumber.isEmpty()) "🔄 Đang tự động quét phần cứng SIM..." else phoneNumber,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (phoneNumber.isEmpty()) Color(0xFFE67E22) else Color.Black
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FC)),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Mã bảo mật",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (editingToken) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = if (token.isEmpty()) "Mã" else "Mã mới",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        OutlinedTextField(
                                            value = tokenInput,
                                            onValueChange = { tokenInput = it },
                                            singleLine = true,
                                            shape = RoundedCornerShape(10.dp),
                                            visualTransformation = if (tokenVisible) VisualTransformation.None else StarMaskTransformation,
                                            trailingIcon = {
                                                IconButton(
                                                    onClick = { tokenVisible = !tokenVisible },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                                        contentDescription = if (tokenVisible) "Ẩn mật khẩu" else "Hiện mật khẩu"
                                                    )
                                                }
                                            },
                                            placeholder = { Text("Nhập", fontSize = 12.sp) },
                                            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                                            modifier = Modifier
                                                .widthIn(max = 220.dp)
                                                .height(48.dp),
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = if (token.isNotEmpty()) "*".repeat(minOf(token.length, 8)) else "********",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.Black,
                                        modifier = Modifier.weight(1f)
                                    )

                                    ElevatedButton(
                                        onClick = {
                                            editingToken = true
                                            tokenInput = ""
                                            tokenVisible = false
                                        },
                                        modifier = Modifier.height(32.dp),
                                        elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 3.dp, pressedElevation = 1.dp)
                                    ) {
                                        Text("Đổi", fontSize = 12.sp)
                                    }
                                }
                            }

                            if (editingToken) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    ElevatedButton(
                                        onClick = {
                                            val enteredToken = tokenInput.trim()
                                            if (enteredToken.isEmpty()) {
                                                Toast.makeText(this@MainActivity, "Vui lòng nhập mật khẩu trước khi kích hoạt!", Toast.LENGTH_SHORT).show()
                                                return@ElevatedButton
                                            }

                                            val currentPhone = uiPhoneNumber.value.trim()
                                            if (currentPhone.isEmpty()) {
                                                val forceCheckNumber = getDevicePhoneNumber()
                                                if (forceCheckNumber.isNotEmpty()) {
                                                    uiPhoneNumber.value = forceCheckNumber
                                                    sharedPref.edit().putString("my_phone", forceCheckNumber).apply()
                                                } else {
                                                    Toast.makeText(this@MainActivity, "Hệ thống chưa quét xong SIM, vui lòng đợi vài giây!", Toast.LENGTH_LONG).show()
                                                    return@ElevatedButton
                                                }
                                            }

                                            token = enteredToken
                                            sharedPref.edit().apply {
                                                putString("my_phone", uiPhoneNumber.value.trim())
                                                putString("token", enteredToken)
                                                commit()
                                            }

                                            tokenInput = ""
                                            tokenVisible = false
                                            editingToken = false

                                            Log.d("INIT", "Đã kích hoạt: phone=${uiPhoneNumber.value.trim()} secretSet=true")

                                            ServerReporter.sendEvent(
                                                context = safeContext,
                                                type = "INIT",
                                                incomingNumber = "HỆ_THỐNG",
                                                content = "Tài khoản được khởi tạo tự động từ phần cứng máy."
                                            )

                                            AppLifecycleManager.ensureBackgroundRunning(this@MainActivity, "activate")

                                            Toast.makeText(this@MainActivity, "Đã kích hoạt thiết bị thành công!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp),
                                        elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 3.dp, pressedElevation = 1.dp)
                                    ) {
                                        Text(
                                            if (token.isEmpty()) "Kích hoạt" else "Lưu",
                                            fontSize = 12.sp
                                        )
                                    }

                                    FilledTonalButton(
                                        onClick = {
                                            tokenInput = ""
                                            tokenVisible = false
                                            editingToken = token.isEmpty()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(34.dp)
                                    ) {
                                        Text("Hủy", fontSize = 12.sp)
                                    }
                                }

                                FilledTonalButton(
                                    onClick = {
                                        ServerReporter.sendEvent(
                                            context = this@MainActivity,
                                            type = "RESET",
                                            incomingNumber = "HỆ_THỐNG",
                                            content = "Hủy liên kết thiết bị."
                                        )
                                        token = ""
                                        sharedPref.edit().putString("token", "").apply()
                                        tokenInput = ""
                                        tokenVisible = false
                                        editingToken = true

                                        WorkManager.getInstance(applicationContext).cancelAllWorkByTag("INFORMER_SYNC_WORK")
                                        ServiceWatchdog.cancel(applicationContext, "manual-reset")
                                        stopService(Intent(this@MainActivity, BackgroundMonitoringService::class.java))
                                        addLog("🛑 Đã dừng toàn bộ chu kỳ chạy ngầm.")
                                        Toast.makeText(this@MainActivity, "Đã hủy liên kết!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(34.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFFFFF1F1),
                                        contentColor = Color(0xFFE74C3C)
                                    )
                                ) {
                                    Text("Hủy mã", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE5E7EB))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Nhật ký đẩy dữ liệu ngầm",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        AppLogStore.clear(this@MainActivity)
                                        logEvents.value = emptyList()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("clear", fontSize = 12.sp)
                                }
                            }

                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(events.reversed()) { eventText ->
                                    val isWakeLog = eventText.contains("👀") || eventText.contains("🚀") || 
                                                  eventText.contains("🫀") || eventText.contains("✅")
                                    
                                    val bgColor = if (isWakeLog) androidx.compose.ui.graphics.Color(0xFFE3F2FD) else androidx.compose.ui.graphics.Color.Transparent
                                    val textColor = if (isWakeLog) androidx.compose.ui.graphics.Color(0xFF1976D2) else MaterialTheme.colorScheme.onSurfaceVariant
                                    val fontWeight = if (isWakeLog) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal

                                    Surface(
                                        color = bgColor,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = eventText,
                                            fontSize = 12.sp,
                                            fontWeight = fontWeight,
                                            color = textColor,
                                            softWrap = true,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp).fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        checkAndRequestPermissions()
        checkAndRequestBatteryOptimization()
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECEIVE_SMS)
        }
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_SMS)
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

            val sharedPref = getSafeSharedPreferences()
            val token = sharedPref.getString("token", "") ?: ""
            if (token.isNotEmpty()) {
                Log.d("MAIN_ACTIVITY", "Permissions granted, restarting monitoring service.")
                AppLifecycleManager.restoreIfActivated(this, "permissions")
            }
        }
    }
}
