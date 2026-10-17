package com.cvcompass.backend.ingestion.api;

import com.cvcompass.backend.ingestion.batch.*;
import com.cvcompass.backend.ingestion.upload.UploadRejectedException;
import jakarta.servlet.ServletException;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import java.io.IOException;
import java.net.URI;

@RestController
@RequestMapping("/api/batches")
public class CvBatchController {
    private final CvBatchService service;
    private final int maxFiles;
    public CvBatchController(CvBatchService service, @Value("${cv.batch.max-files:10}") int maxFiles) {
        if (maxFiles < 1 || maxFiles > 10) throw new IllegalArgumentException("Invalid batch file count");
        this.service = service; this.maxFiles = maxFiles;
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CvBatchService.ImportResult> upload(MultipartHttpServletRequest request)
            throws ServletException, IOException {
        var map = request.getMultiFileMap();
        var files = map.get("files");
        if (map.size() != 1 || files == null || files.isEmpty() || files.size() > maxFiles
                || request.getParts().size() != files.size() || !request.getParameterMap().isEmpty()) {
            throw new UploadRejectedException(UploadRejectedException.Reason.INVALID_MULTIPART,
                    "Expected between 1 and the configured maximum file parts named files");
        }
        var result = service.upload(files);
        return ResponseEntity.created(URI.create("/api/batches/" + result.batch().id())).body(result);
    }
    @GetMapping("/{id}")
    public BatchDetails find(@PathVariable @Positive long id) { return service.find(id); }
    @GetMapping
    public PageResult<BatchDetails> list(@RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.list(page, size); }
    @GetMapping("/{id}/items")
    public PageResult<BatchItem> items(@PathVariable @Positive long id,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.items(id, page, size); }
}
