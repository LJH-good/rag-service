package com.init.worker.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.init.worker.util.ChunkIdUuid;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * QA 인용(Citation) 엔티티 (rag_qa_citation).
 * - message_id 로 직접 연결 (rag_qa_log FK 없음).
 * - 청크 삭제 후에도 snippet 으로 내용을 보존하기 위해 chunk_id 에 FK 없음.
 */
@Entity
@Table(name = "rag_qa_citation")
public class RagQaCitation {

    @Id
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "id")
    private UUID id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    /** 청크 삭제 후에도 snippet 으로 내용 보존 — 의도적 FK 없음 */
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "chunk_id")
    private UUID chunkId;

    @Column(name = "document_id")
    private String documentId;

    @Column(name = "score", precision = 5, scale = 4, nullable = false)
    private BigDecimal score;

    @Column(name = "page")
    private Integer page;

    @Column(name = "slide")
    private Integer slide;

    @Column(name = "sheet")
    private String sheet;

    @Column(name = "source_uri")
    private String sourceUri;

    @Column(name = "snippet", columnDefinition = "text")
    private String snippet;

    protected RagQaCitation() {}

    public static RagQaCitation of(
            UUID messageId,
            String chunkId,
            String documentId,
            BigDecimal score,
            Integer page,
            Integer slide,
            String sheet,
            String sourceUri,
            String snippet) {
        RagQaCitation c = new RagQaCitation();
        c.id = UUID.randomUUID();
        c.messageId = messageId;
        c.chunkId = ChunkIdUuid.parseOrDerive(chunkId);
        c.documentId = documentId;
        c.score = score;
        c.page = page;
        c.slide = slide;
        c.sheet = sheet;
        c.sourceUri = sourceUri;
        c.snippet = snippet;
        return c;
    }

    public UUID getId() { return id; }
    public UUID getMessageId() { return messageId; }
    public UUID getChunkId() { return chunkId; }
    public String getDocumentId() { return documentId; }
    public BigDecimal getScore() { return score; }
    public Integer getPage() { return page; }
    public Integer getSlide() { return slide; }
    public String getSheet() { return sheet; }
    public String getSourceUri() { return sourceUri; }
    public String getSnippet() { return snippet; }
}
