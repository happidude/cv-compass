package com.cvcompass.backend.ingestion.api;

import com.cvcompass.backend.ingestion.upload.CvDocumentService;
import com.cvcompass.backend.ingestion.upload.DocumentMetadata;
import com.cvcompass.backend.ingestion.upload.UploadRejectedException;
import jakarta.servlet.ServletException;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.net.URI;

@RestController
@RequestMapping("/api/documents")
public class CvDocumentController {
    private final CvDocumentService service;

    public CvDocumentController(CvDocumentService service) {
        this.service = service;
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
        if (file.getSize() > 10485760L) {
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(10485760L);
        }
        try (var content = file.getInputStream()) {
            var metadata = service.upload(file.getOriginalFilename(), content);
            return ResponseEntity.created(URI.create("/api/documents/" + metadata.id())).body(metadata);
        }
    }

    @GetMapping("/{id}")
    public DocumentMetadata find(@PathVariable @Positive long id) {
        return service.find(id);
    }
}
