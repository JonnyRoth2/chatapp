package com.chatapp.backend.group;

import com.chatapp.backend.message.MessageImageRepository;
import com.chatapp.backend.message.MessageKeyEnvelopeRepository;
import com.chatapp.backend.message.MessageRepository;
import com.chatapp.backend.message.MessageService;
import com.chatapp.backend.user.User;
import com.chatapp.backend.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupChatRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final MessageService messageService;
    private final MessageRepository messageRepository;
    private final MessageImageRepository imageRepository;
    private final MessageKeyEnvelopeRepository envelopeRepository;
    private final ObjectMapper objectMapper;
    private final int maxMembers;

    public GroupController(GroupChatRepository groupRepository, GroupMemberRepository memberRepository,
                           UserRepository userRepository, MessageService messageService,
                           MessageRepository messageRepository, MessageImageRepository imageRepository,
                           MessageKeyEnvelopeRepository envelopeRepository, ObjectMapper objectMapper,
                           @Value("${app.groups.max-members}") int maxMembers) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.messageService = messageService;
        this.messageRepository = messageRepository;
        this.imageRepository = imageRepository;
        this.envelopeRepository = envelopeRepository;
        this.objectMapper = objectMapper;
        this.maxMembers = maxMembers;
    }

    public record MemberDto(Long userId, String username) {}
    public record GroupDto(Long id, String name, String creatorUsername, List<MemberDto> members) {}
    public record CreateGroupRequest(@NotBlank @Size(min = 1, max = 64) String name) {}
    public record AddMemberRequest(@NotBlank String additionKey) {}

    @GetMapping
    public List<GroupDto> myGroups(Principal principal) {
        User me = currentUser(principal);
        return memberRepository.findAllForUser(me.getId()).stream()
                .map(gm -> toDto(gm.getGroup()))
                .toList();
    }

    @PostMapping
    @Transactional
    public GroupDto create(@Valid @RequestBody CreateGroupRequest req, Principal principal) {
        User me = currentUser(principal);
        GroupChat group = groupRepository.save(new GroupChat(req.name().trim(), me));
        memberRepository.save(new GroupMember(group, me));
        return toDto(group);
    }

    @PostMapping("/{groupId}/members")
    @Transactional
    public GroupDto addMember(@PathVariable Long groupId, @Valid @RequestBody AddMemberRequest req,
                              Principal principal) {
        User me = currentUser(principal);
        GroupChat group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        if (!memberRepository.existsByGroupIdAndUserId(groupId, me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this group");
        }
        User newcomer = userRepository.findByAdditionKey(req.additionKey().trim().toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No user with that addition key"));
        if (memberRepository.existsByGroupIdAndUserId(groupId, newcomer.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already in this group");
        }
        if (memberRepository.countByGroupId(groupId) >= maxMembers) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Group is full (" + maxMembers + " members)");
        }
        memberRepository.save(new GroupMember(group, newcomer));
        return toDto(group);
    }

    /** Leave the group. If the last member leaves, the group is cleaned up entirely. */
    @DeleteMapping("/{groupId}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void leave(@PathVariable Long groupId, Principal principal) {
        User me = currentUser(principal);
        if (!groupRepository.existsById(groupId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found");
        }
        if (memberRepository.deleteByGroupIdAndUserId(groupId, me.getId()) == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this group");
        }
        if (memberRepository.countByGroupId(groupId) == 0) {
            purgeGroup(groupId);
        }
    }

    /** Delete the group and everything in it. Creator only. */
    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long groupId, Principal principal) {
        User me = currentUser(principal);
        GroupChat group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Group not found"));
        if (!group.getCreator().getId().equals(me.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator can delete the group");
        }
        purgeGroup(groupId);
    }

    /** FK order: key envelopes -> images -> messages -> members -> group. */
    private void purgeGroup(Long groupId) {
        envelopeRepository.deleteForGroup(groupId);
        imageRepository.deleteForGroup(groupId);
        messageRepository.deleteByGroupId(groupId);
        memberRepository.deleteByGroupId(groupId);
        groupRepository.deleteById(groupId);
    }

    @GetMapping("/{groupId}/messages")
    public List<MessageService.MessageDto> history(@PathVariable Long groupId, Principal principal) {
        return messageService.groupHistory(principal.getName(), groupId);
    }

    /** Encrypted group image: {@code file} is the ciphertext, {@code envelopes} a
     *  JSON map of each member's userId to their wrapped content key. */
    @PostMapping("/{groupId}/image")
    public MessageService.MessageDto sendImage(@PathVariable Long groupId,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam("envelopes") String envelopesJson,
                                               Principal principal) {
        try {
            Map<Long, String> envelopes =
                    objectMapper.readValue(envelopesJson, new TypeReference<Map<Long, String>>() {});
            return messageService.sendGroupImage(principal.getName(), groupId, file.getBytes(), envelopes);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
    }

    private GroupDto toDto(GroupChat group) {
        List<MemberDto> members = memberRepository.findAllInGroup(group.getId()).stream()
                .map(gm -> new MemberDto(gm.getUser().getId(), gm.getUser().getUsername()))
                .toList();
        return new GroupDto(group.getId(), group.getName(), group.getCreator().getUsername(), members);
    }

    private User currentUser(Principal principal) {
        return userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));
    }
}
