package com.jiuguan.ailink.model



/** 消息方向。 */
/** 消息方向。 */
enum class Direction(val value: String) {
    IN("IN"),
    OUT("OUT");

    companion object {
        fun of(value: String): Direction = if (value == OUT.value) OUT else IN
    }
}
