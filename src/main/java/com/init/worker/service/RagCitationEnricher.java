package com.init.worker.service;

import com.init.worker.config.RagStorageProperties;
import com.init.worker.domain.RagChunk;
import com.init.worker.domain.RagDocument;
import com.init.worker.domain.RagDocumentFile;
import com.init.worker.dto.CitationDto;
import com.init.worker.repo.RagChunkRepository;
import com.init.worker.repo.RagDocumentFileRepository;
import com.init.worker.repo.RagDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(name = "rag.app.role", havingValue = "api")
public class RagCitationEnricher {

    private final RagDocumentRepository ragDocumentRepository;
    private final RagDocumentFileRepository ragDocumentFileRepository;
    private final RagChunkRepository ragChunkRepository;
    private final RagStorageProperties ragStorageProperties;

    public RagCitationEnricher(
            RagDocumentRepository ragDocumentRepository,
            RagDocumentFileRepository ragDocumentFileRepository,
            RagChunkRepository ragChunkRepository,
            RagStorageProperties ragStorageProperties) {
        this.ragDocumentRepository = ragDocumentRepository;
        this.ragDocumentFileRepository = ragDocumentFileRepository;
        this.ragChunkRepository = ragChunkRepository;
        this.ragStorageProperties = ragStorageProperties;
    }

    public List<CitationDto> enrich(List<CitationDto> citations) {
        if (citations == null || citations.isEmpty()) {
            return List.of();
        }

        Map<String, DocumentMeta> metaByDocumentId = loadDocumentMeta(citations);

        List<UUID> chunkUuids = citations.stream()
                .map(CitationDto::chunkId)
                .filter(id -> id != null && !id.isBlank())
                .map(id -> { try { return UUID.fromString(id); } catch (Exception e) { return null; } })
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<UUID, RagChunk> chunkById = new HashMap<>();
        if (!chunkUuids.isEmpty()) {
            ragChunkRepository.findAllById(chunkUuids)
                    .forEach(chunk -> chunkById.put(chunk.getId(), chunk));
        }

        List<CitationDto> out = new ArrayList<>(citations.size());
        for (CitationDto c : citations) {
            DocumentMeta meta = metaByDocumentId.get(c.documentId());

            String sourceUri = c.sourceUri();
            if ((sourceUri == null || sourceUri.isBlank()) && meta != null) {
                sourceUri = meta.sourceUri();
            }

            Integer page = c.page();
            Integer slide = c.slide();
            String sheet = c.sheet();
            if (page == null && slide == null && sheet == null) {
                UUID chunkUuid = null;
                try { chunkUuid = UUID.fromString(c.chunkId()); } catch (Exception ignored) {}
                RagChunk ragChunk = chunkUuid != null ? chunkById.get(chunkUuid) : null;
                if (ragChunk != null && ragChunk.getLocation() != null) {
                    Location loc = resolveLocation(ragChunk.getLocation(), meta == null ? null : meta.documentType());
                    page  = loc.page();
                    slide = loc.slide();
                    sheet = loc.sheet();
                }
            }

            out.add(new CitationDto(c.chunkId(), c.documentId(), c.score(),
                    page, slide, sheet, sourceUri, c.snippet()));
        }
        return out;
    }

    private Map<String, DocumentMeta> loadDocumentMeta(List<CitationDto> citations) {
        List<String> documentIds = citations.stream()
                .map(CitationDto::documentId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();

        Map<String, DocumentMeta> out = new HashMap<>();
        if (documentIds.isEmpty()) return out;

        List<UUID> ids = new ArrayList<>();
        for (String rawId : documentIds) {
            try { ids.add(UUID.fromString(rawId)); } catch (IllegalArgumentException ignored) {}
        }
        if (ids.isEmpty()) return out;

        List<RagDocument> docs = ragDocumentRepository.findAllById(ids);
        List<UUID> fileIds = docs.stream()
                .map(RagDocument::getFileId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (fileIds.isEmpty()) return out;

        Map<UUID, RagDocumentFile> fileById = new HashMap<>();
        ragDocumentFileRepository.findAllById(fileIds)
                .forEach(f -> fileById.put(f.getId(), f));

        for (RagDocument doc : docs) {
            RagDocumentFile file = fileById.get(doc.getFileId());
            if (file == null) continue;
            String sourceUri = buildSourceUri(file.getStorageKey());
            DocumentType documentType = inferDocumentType(file.getOriginalFileName());
            out.put(doc.getId().toString(), new DocumentMeta(sourceUri, documentType));
        }
        return out;
    }

    private String buildSourceUri(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return null;
        String bucket = ragStorageProperties.getMinio() != null
                ? ragStorageProperties.getMinio().getBucket() : null;
        if (bucket == null || bucket.isBlank()) return null;
        return "s3://" + bucket.trim() + "/" + storageKey;
    }

    private DocumentType inferDocumentType(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) return DocumentType.OTHER;
        String lower = originalFileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc")
                || lower.endsWith(".hwp") || lower.endsWith(".hwpx")) return DocumentType.PDF;
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return DocumentType.PPT;
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return DocumentType.EXCEL;
        return DocumentType.OTHER;
    }

    private Location resolveLocation(String location, DocumentType type) {
        if (location == null || location.isBlank() || type == null) return new Location(null, null, null);
        return switch (type) {
            case PDF   -> new Location(parseLocationInt(location, "p."), null, null);
            case PPT   -> new Location(null, parseLocationInt(location, "slide."), null);
            case EXCEL -> new Location(null, null, location);
            default    -> new Location(null, null, null);
        };
    }

    private Integer parseLocationInt(String value, String prefix) {
        String s = value.trim();
        try { return Integer.parseInt((s.startsWith(prefix) ? s.substring(prefix.length()) : s).trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private record Location(Integer page, Integer slide, String sheet) {}
    private record DocumentMeta(String sourceUri, DocumentType documentType) {}
    private enum DocumentType { PDF, PPT, EXCEL, OTHER }
}
