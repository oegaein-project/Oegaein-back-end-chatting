package com.likelion.oegaein.domain.chat.dto;

import com.likelion.oegaein.domain.chat.entity.MessageStatus;
import com.likelion.oegaein.global.dto.ResponseDto;

import java.time.LocalDateTime;

public class FindMessageResponse implements ResponseDto {
    private String roomId; // 채팅방 ID
    private String senderName; // 보낸 회원 이름
    private String message; // 메시지 내용
    private MessageStatus messageStatus; // 메시지 타입
    private LocalDateTime date; // 메시지 발신 날짜
}
