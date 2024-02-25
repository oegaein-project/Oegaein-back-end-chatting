package com.likelion.oegaein.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
@AllArgsConstructor
public class FindMessagesResponse implements ResponseDto{
    private List<FindMessageData> data;
}