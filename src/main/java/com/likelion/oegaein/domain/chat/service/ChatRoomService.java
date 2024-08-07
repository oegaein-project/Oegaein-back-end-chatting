package com.likelion.oegaein.domain.chat.service;

import com.likelion.oegaein.domain.chat.dto.*;
import com.likelion.oegaein.domain.chat.entity.ChatRoom;
import com.likelion.oegaein.domain.chat.entity.ChatRoomMember;
import com.likelion.oegaein.domain.chat.entity.Message;
import com.likelion.oegaein.domain.chat.entity.MessageStatus;
import com.likelion.oegaein.domain.chat.repository.RedisRepository;
import com.likelion.oegaein.domain.matching.entity.MatchingPost;
import com.likelion.oegaein.domain.matching.repository.MatchingPostRepository;
import com.likelion.oegaein.domain.member.entity.member.Member;
import com.likelion.oegaein.domain.chat.repository.ChatRoomMemberRepository;
import com.likelion.oegaein.domain.chat.repository.ChatRoomRepository;
import com.likelion.oegaein.domain.chat.repository.MessageRepository;
import com.likelion.oegaein.domain.member.entity.profile.Profile;
import com.likelion.oegaein.domain.member.repository.MemberRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {
    // DI
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MessageRepository messageRepository;
    private final MemberRepository memberRepository;
    private final MatchingPostRepository matchingPostRepository;
    private final MessageService messageService;
    private final RedisRepository redisRepository;
    // constant
    private final String NOT_FOUND_MEMBER_ERR_MSG = "찾을 수 없는 사용자입니다.";
    private final String NOT_FOUND_MATCHING_POST_ERR_MSG = "찾을 수 없는 매칭글입니다.";
    private final String NOT_FOUND_CHAT_ROOM_ERR_MSG = "찾을 수 없는 채팅방입니다.";
    private final String NOT_FOUND_CHAT_ROOM_MEMBER_ERR_MSG = "찾을 수 없는 채팅방 참가자입니다.";
    private final String CHATROOM_LEAVE_MESSAGE = "님이 떠났습니다.";
    private final String CHAT_ROOM_NAME_POSTFIX = " 행성방";

    public FindChatRoomsResponse findChatRooms(Authentication authentication){
        // find login user
        Member authenticatedMember = memberRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MEMBER_ERR_MSG));
        // find ChatRoomMembers
        List<ChatRoomMember> chatRoomMembers = chatRoomMemberRepository.findByMember(authenticatedMember);
        // create FindChatRoomsData
        List<FindChatRoomsData> findChatRoomsData = chatRoomMembers.stream()
                .map((chatRoomMember) -> {
                    ChatRoom chatRoom = chatRoomMember.getChatRoom();
                    String roomId = chatRoom.getRoomId();
                    String roomName = chatRoom.getRoomName();
                    int memberCount = chatRoom.getMemberCount();
                    LocalDateTime disconnectedAt = chatRoomMember.getDisconnectedAt();
                    MatchingPost matchingPost = chatRoom.getMatchingPost();
                    Member author = matchingPost.getAuthor();

                    // find all of messages
                    FindMessagesResponse response = messageService.getMessages(roomId, authentication);
                    List<FindMessageData> allOfMessages = response.getData();

                    if(allOfMessages.isEmpty()){
                        return FindChatRoomsData.builder()
                                .id(chatRoom.getId())
                                .photoUrl(author.getPhotoUrl())
                                .roomId(roomId)
                                .roomName(roomName)
                                .memberCount(memberCount)
                                .build();
                    }
                    FindMessageData lastMessage = allOfMessages.get(allOfMessages.size()-1);
                    // find unread message
                    List<FindMessageData> unReadMessages = allOfMessages.stream()
                            .filter((message) -> message.getDate().isAfter(disconnectedAt)).toList();
                    // create response
                    return FindChatRoomsData.builder()
                            .id(chatRoom.getId())
                            .photoUrl(author.getPhotoUrl())
                            .roomId(roomId)
                            .roomName(roomName)
                            .memberCount(memberCount)
                            .unReadMessageCount(unReadMessages.size())
                            .lastMessageContent(lastMessage.getMessage())
                            .lastMessageDate(lastMessage.getDate())
                            .build();
                }).toList();
        return new FindChatRoomsResponse(findChatRoomsData.size(), findChatRoomsData);
    }

    public FindUnreadMessageResponse findUnreadMessage(Authentication authentication){
        // find login user
        Member authenticatedMember = memberRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MEMBER_ERR_MSG));
        // find ChatRoomMembers
        List<ChatRoomMember> chatRoomMembers = chatRoomMemberRepository.findByMember(authenticatedMember);
        FindUnreadMessageResponse response = new FindUnreadMessageResponse(0);
        chatRoomMembers.forEach((chatRoomMember -> {
            ChatRoom chatRoom = chatRoomMember.getChatRoom();
            String roomId = chatRoom.getRoomId();
            LocalDateTime disconnectedAt = chatRoomMember.getDisconnectedAt();
            // find unread messages
            List<Message> unReadMessages = messageRepository.findByRoomIdAndDateAfterOrderByDateAsc(roomId, disconnectedAt);
            if(redisRepository.get(roomId) != null){
                unReadMessages.addAll(redisRepository.get(roomId).stream().filter((message) -> message.getDate().isAfter(disconnectedAt)
                ).toList());
            }
            unReadMessages = unReadMessages.stream().distinct().toList(); // 중복 제거
            response.upTotalUnreadMessageCount(unReadMessages.size());
        }));
        return response;
    }

    @Transactional
    public CreateChatRoomResponse createChatRoom(CreateChatRoomData dto){
        // find matchingPost
        MatchingPost matchingPost = matchingPostRepository.findById(dto.getMatchingPostId())
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MATCHING_POST_ERR_MSG));
        // create chat room
        String chatRoomName = generateChatRoomName(matchingPost.getTitle());
        ChatRoom chatRoom = ChatRoom.builder()
                .roomId(UUID.randomUUID().toString())
                .roomName(chatRoomName)
                .memberCount(0)
                .matchingPost(matchingPost)
                .build();
        chatRoomRepository.save(chatRoom);
        return new CreateChatRoomResponse(chatRoom.getId(),chatRoom.getRoomId());
    }

    @Transactional
    public DeleteChatRoomResponse removeOneToOneChatRoom(String roomId, Authentication authentication){
        // find chat member
        Member authenticatedMember = memberRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_MEMBER_ERR_MSG));
        // find ChatRoom
        ChatRoom chatRoom = chatRoomRepository.findByRoomId(roomId)
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_CHAT_ROOM_ERR_MSG));
        // find ChatRoomMember
        ChatRoomMember chatRoomMember = chatRoomMemberRepository.findByChatRoomAndMember(chatRoom, authenticatedMember)
                        .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND_CHAT_ROOM_MEMBER_ERR_MSG));
        chatRoomMemberRepository.delete(chatRoomMember);
        decreaseMemberCount(chatRoom.getId());
        if(chatRoom.getMemberCount() == 0){ // 모두 방에서 나갔는지 확인
            redisRepository.delete(chatRoom.getRoomId());
            chatRoomRepository.delete(chatRoom);
        }
        return new DeleteChatRoomResponse(roomId, authenticatedMember.getId());
    }

    @Transactional
    public void decreaseMemberCount(Long id){
        ChatRoom chatRoom = chatRoomRepository.findByIdWithPessimisticLock(id).orElseThrow();
        chatRoom.downMemberCount();
    }

    // custom method
    private String generateChatRoomName(String matchingPostTitle){
        int titleLength = matchingPostTitle.length();
        if(titleLength > 10){
            String slicedTitle = matchingPostTitle.substring(0, 11);
            return slicedTitle + "..." + CHAT_ROOM_NAME_POSTFIX;
        }
        return matchingPostTitle + CHAT_ROOM_NAME_POSTFIX;
    }
}
