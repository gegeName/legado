package com.script.rhino

import org.mozilla.javascript.Context

object VMBridgeReflect {

    val contextLocal: ThreadLocal<Any> by lazy {
        @Suppress("UNCHECKED_CAST")
        Context::class.java.getDeclaredField("currentContext").apply {
            isAccessible = true
        }.get(null) as ThreadLocal<Any>
    }

}
