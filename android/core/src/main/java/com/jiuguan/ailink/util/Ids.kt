package com.jiuguan.ailink.util

import java.util.UUID
import kotlin.random.Random

object Ids {
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    fun randomNumber(from: Int, to: Int): Int = Random.nextInt(from, to + 1)
}
