package com.example.informer

object PhoneUtils {
    /**
     * Chuẩn hóa số điện thoại về định dạng 10 số (Ví dụ: +84967684284 -> 0967684284)
     * Loại bỏ toàn bộ khoảng trống, ký tự đặc biệt hoặc mã token nếu có dính kèm.
     */
    fun formatVietnamesePhoneNumber(rawNumber: String?): String {
        if (rawNumber.isNullOrBlank()) return "Không rõ số"

        // Loại bỏ khoảng trắng, dấu gạch ngang nếu hệ thống trả về dạng dữ liệu thô
        var cleaned = rawNumber.replace("\\s+".toRegex(), "").replace("-", "")

        // Nếu chuỗi chứa token hoặc ký tự đặc biệt phức tạp phía sau, chỉ lấy phần số và dấu + đầu tiên
        val match = Regex("^\\+?[0-9]+").find(cleaned)
        if (match != null) {
            cleaned = match.value
        }

        // Chuyển đổi đầu số quốc tế +84 hoặc 84 về đầu số 0 chuẩn
        if (cleaned.startsWith("+84")) {
            cleaned = "0" + cleaned.substring(3)
        } else if (cleaned.startsWith("84") && cleaned.length > 9) {
            cleaned = "0" + cleaned.substring(2)
        }

        return cleaned
    }
}