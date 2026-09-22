package com.szech.walls.server

/** 让界面能查到订阅服务是否在跑（服务在 onCreate/onDestroy 里登记自己）。 */
object SubServiceHolder {
    @Volatile
    var instance: SubService? = null
}
