package com.classos.files;

import java.util.UUID;

public record StoredFile(UUID id, String originalName, String contentType, long sizeBytes) {}
