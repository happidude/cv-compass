package com.cvcompass.backend.ingestion.controller;

import com.cvcompass.backend.ingestion.service.CvDocumentService;
import com.cvcompass.backend.ingestion.service.DocumentQueryService;
import com.cvcompass.backend.ingestion.config.UploadProperties;
import com.cvcompass.backend.ingestion.dto.DocumentCursorPage;
import com.cvcompass.backend.ingestion.dto.DocumentDetails;
import com.cvcompass.backend.ingestion.dto.DocumentMetadata;
import com.cvcompass.backend.ingestion.exception.UploadRejectedException;
import jakarta.servlet.ServletException;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.net.URI;

@RestController
@RequestMapping("/api/documents")
public class CvDocumentController {
    private final CvDocumentService service;
    private final DocumentQueryService queries;
    private final UploadProperties properties;

    public CvDocumentController(CvDocumentService service, DocumentQueryService queries, UploadProperties properties) {
        this.service = service;
        this.queries = queries;
        this.properties = properties;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentMetadata> upload(MultipartHttpServletRequest request)
            throws IOException, ServletException {
        var files = request.getMultiFileMap();
        if (request.getParts().size() != 1 || files.size() != 1 || !files.containsKey("file")
                || files.get("file").size() != 1 || !request.getParameterMap().isEmpty()) {
            throw new UploadRejectedException(UploadRejectedException.Reason.INVALID_MULTIPART,
                    "Exactly one multipart file named file is required");
        }
        var file = files.get("file").getFirst();
        // The shared parser ceiling accommodates batch imports; retain M4's public 413 contract.
        if (file.getSize() > properties.getMaxFileBytes()) {
            throw new MaxUploadSizeExceededException(properties.getMaxFileBytes());
        }
        try (var content = file.getInputStream()) {
            var metadata = service.upload(file.getOriginalFilename(), content);
            return ResponseEntity.created(URI.create("/api/documents/" + metadata.id())).body(metadata);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentDetails> find(@PathVariable @Positive long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.find(id));
    }

    @GetMapping
    public ResponseEntity<DocumentCursorPage> list(@RequestParam(required = false) @Positive Long beforeId,
                                    @RequestParam(required = false) @Positive Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(beforeId, size));
    }
}
