package com.cvcompass.backend.ingestion.upload;

import com.cvcompass.backend.ingestion.exception.UploadRejectedException;

/** Display-only filename validation shared by document and batch uploads. */
public final class UploadFilename {
    private UploadFilename() {}

    public static String safeFilename(String original) {
        if (original == null) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.INVALID_FILENAME, "A filename is required");
        }
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isBlank()
                || name.equals(".")
                || name.equals("..")
                || name.length() > 255
                || name.codePoints()
                        .anyMatch(
                                value ->
                                        Character.isISOControl(value)
                                                || Character.getType(value) == Character.FORMAT)) {
            throw new UploadRejectedException(
                    UploadRejectedException.Reason.INVALID_FILENAME, "Filename is invalid");
        }
        return name;
    }
}
