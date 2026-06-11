package com.chatapp.backend.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Image bytes live in their own table so conversation queries never load
 * blobs. The FK points at the message; the purge job deletes images first.
 */
@Entity
@Table(name = "message_images")
public class MessageImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", unique = true)
    private Message message;

    @Column(nullable = false, length = 64)
    private String contentType;

    @Column(nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] data;

    protected MessageImage() {}

    public MessageImage(Message message, String contentType, byte[] data) {
        this.message = message;
        this.contentType = contentType;
        this.data = data;
    }

    public Long getId() { return id; }
    public Message getMessage() { return message; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
}
