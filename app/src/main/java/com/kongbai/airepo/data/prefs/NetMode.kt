package com.kongbai.airepo.data.prefs

/** 每条消息的联网策略，用户在输入框上方切换 */
enum class NetMode(val label: String, val hint: String) {
    AUTO("自动", "AI 自己判断要不要搜"),
    ON("联网", "强制带上搜索工具"),
    OFF("离线", "只用仓库工具，不联网")
}
