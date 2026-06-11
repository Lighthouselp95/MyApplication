package com.example.informer

import android.content.Context

object AppContextHolder {
    @Volatile
    var context: Context? = null
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}
