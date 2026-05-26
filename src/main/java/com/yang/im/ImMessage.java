package com.yang.im;

/** 表示从 IM 通道收到的一条用户消息。 */
public record ImMessage(String chatId, String userId, String text) {
}
