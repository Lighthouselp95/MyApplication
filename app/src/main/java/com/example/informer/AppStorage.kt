package com.example.informer

import android.content.Context
import android.os.Build

fun Context.deviceProtectedContext(): Context {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isDeviceProtectedStorage) {
        createDeviceProtectedStorageContext()
    } else {
        this
    }
}
