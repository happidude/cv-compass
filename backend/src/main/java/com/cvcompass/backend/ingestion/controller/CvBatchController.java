package com.cvcompass.backend.ingestion.controller;

import com.cvcompass.backend.ingestion.dto.BatchDetails;
import com.cvcompass.backend.ingestion.config.BatchProperties;
import com.cvcompass.backend.ingestion.config.PaginationProperties;
import com.cvcompass.backend.ingestion.dto.ImportFile;
import com.cvcompass.backend.ingestion.dto.BatchItem;
import com.cvcompass.backend.ingestion.service.CvBatchService;
import com.cvcompass.backend.ingestion.dto.ImportResult;
import com.cvcompass.backend.ingestion.dto.PageResult;

import com.cvcompass.backend.ingestion.exception.UploadRejectedException;
import jakarta.servlet.ServletException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.URI;

@RestController
@RequestMapping("/api/batches")
public class CvBatchController {
    private final CvBatchService service;
    private final int maxFiles;
    private final PaginationProperties pagination;

    public CvBatchController(CvBatchService service, BatchProperties properties, PaginationProperties pagination) {
        this.service = service;
        this.maxFiles = properties.getMaxFiles();
        this.pagination = pagination;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportResult> upload(MultipartHttpServletRequest request)
            throws ServletException, IOException {
        var map = request.getMultiFileMap();
        var files = map.get("files");
        if (map.size() != 1 || files == null || files.isEmpty() || files.size() > maxFiles
                || request.getParts().size() != files.size() || !request.getParameterMap().isEmpty()) {
            throw new UploadRejectedException(UploadRejectedException.Reason.INVALID_MULTIPART,
                    "Expected between 1 and the configured maximum file parts named files");
        }
        var inputs = files.stream().map(file -> new ImportFile(
                file.getOriginalFilename(), file.getSize(), file::getInputStream, null)).toList();
        var result = service.upload(inputs);
        return ResponseEntity.created(URI.create("/api/batches/" + result.batch().id())).body(result);
    }
    @GetMapping("/{id}")
    public BatchDetails find(@PathVariable @Positive long id) {
        return service.find(id);
    }

    @GetMapping
    public PageResult<BatchDetails> list(@RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(required = false) @Positive Integer size) {
        return service.list(page, pageSize(size));
    }

    @GetMapping("/{id}/items")
    public PageResult<BatchItem> items(@PathVariable @Positive long id,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(required = false) @Positive Integer size) {
        return service.items(id, page, pageSize(size));
    }

    private int pageSize(Integer requested) {
        int size = requested == null ? pagination.getDefaultPageSize() : requested;
        if (size < 1 || size > pagination.getMaxPageSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return size;
    }
}
